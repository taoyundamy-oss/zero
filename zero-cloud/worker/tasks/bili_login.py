"""tasks.bili_login — B站扫码登录（C3.6 · 凭据刷新机制 D111）

背景：Alife 迁移凭据已过期（cred_valid=False，2026-09-05 实测）——QR 工具化为常驻刷新通道。

裸 passport 端点（不经 bilibili_api 实例状态——generate/poll 均无状态，qrcode_key 存 Redis）：
  ① POST /x/passport-login/web/qrcode/generate → {url(=QR 内容), qrcode_key}
  ② GET  /x/passport-login/web/qrcode/poll?qrcode_key= → data.code:
       86101 待扫码 · 86090 已扫码待确认 · 86038 过期 · 0 成功（Cookie 在返回 url 查询参数）
  ③ buvid3：/x/frontend/finger/spide Set-Cookie（风控基底）
成功 → 写 /data/zero/.bili_credential.json（600，Z19 待遇）→ cloud_download 立即生效（1080p）。
QR PNG 经 qrcode 库生成并登记 file_id（手机拉图扫码）。
"""
import io
import json
import os
import re
import time
import urllib.parse
from pathlib import Path

import httpx

from celery_app import app, rdb, update_meta
from tasks.lib.files import register_file as _register_file, file_result as _file_result

DATA_DIR = os.getenv("DATA_DIR", "/data/zero")
CRED_PATH = Path(DATA_DIR) / ".bili_credential.json"
QR_KEY_TTL = 300
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"}


def _http() -> httpx.Client:
    return httpx.Client(headers=UA, timeout=15, follow_redirects=True)


def _generate_qr(task_id: str) -> dict:
    with _http() as c:
        c.get("https://www.bilibili.com")  # 预热 Cookie（buvid3 未下发也不阻塞）
        r = c.get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
    data = (r.json().get("data") or {})
    url, key = data.get("url"), data.get("qrcode_key")
    if not url or not key:
        raise RuntimeError(f"QR 生成失败: {str(r.json())[:150]}")
    rdb.setex("zero:bili:qrkey", QR_KEY_TTL, key)

    import qrcode
    img = qrcode.make(url)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    out_dir = Path(DATA_DIR) / "process" / task_id
    out_dir.mkdir(parents=True, exist_ok=True)
    png = out_dir / "bili-login-qrcode.png"
    png.write_bytes(buf.getvalue())
    return {"status": "qrcode", "qr_file": _file_result(_register_file(png, "image/png")),
            "expire_s": 180, "next": "用哔哩哔哩 App 扫码后调 bili_login(action=wait)"}


def _poll_wait() -> dict:
    key = rdb.get("zero:bili:qrkey")
    if not key:
        raise ValueError("无进行中的登录会话（先调 action=qrcode；二维码 3 分钟有效）")
    with _http() as c:
        r = c.get("https://passport.bilibili.com/x/passport-login/web/qrcode/poll",
                  params={"qrcode_key": key})
    data = (r.json().get("data") or {})
    code = data.get("code", -1)
    if code == 0:
        raw_url = data.get("url") or ""
        unq = urllib.parse.unquote(urllib.parse.unquote(raw_url))  # 双重编码兼容
        # 兼容形态：标准 query 解析 / &amp; 转义 / 任意位置正则直取（键名级鲁棒）
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(unq).query))
        if "SESSDATA" not in q:
            q = dict(urllib.parse.parse_qsl(
                urllib.parse.urlsplit(unq.replace("&amp;", "&")).query))

        def grab(name: str) -> str:
            v = q.get(name)
            if v:
                return v
            m = re.search(re.escape(name) + r"=([^&#]+)", unq)
            return urllib.parse.unquote(m.group(1)) if m else ""

        # 兜底源：poll 响应 Set-Cookie（部分风控路径凭据不进 url）
        ck = r.cookies
        cred = {
            "sessdata": grab("SESSDATA") or (ck.get("SESSDATA") or ""),
            "bili_jct": grab("bili_jct") or (ck.get("bili_jct") or ""),
            "buvid3": "",
            "dedeuserid": grab("DedeUserID") or (ck.get("DedeUserID") or ""),
            "ac_time_value": "",
            "refresh_token": data.get("refresh_token", ""),
        }
        try:  # buvid3 补充（风控基底；失败不阻塞登录）
            with _http() as c2:
                cred["buvid3"] = c2.get(
                    "https://api.bilibili.com/x/frontend/finger/spide").cookies.get("buvid3") or ""
        except Exception:
            pass
        if not cred["sessdata"] or not cred["bili_jct"]:
            # 脱敏诊断（E25 二次缺陷）：只带键名与长度，不带任何凭据值
            keys = [p.split("=")[0] for p in re.split(r"&|&amp;", unq) if p]
            raise RuntimeError(
                f"登录响应缺少凭据字段（url_keys={keys[:15]} url_len={len(raw_url)} "
                f"setcookie={sorted(ck.keys())}）——凭据形态与预期不符，请反馈此诊断串")
        CRED_PATH.write_text(json.dumps(cred, ensure_ascii=False, indent=2), encoding="utf-8")
        os.chmod(CRED_PATH, 0o600)
        rdb.delete("zero:bili:qrkey")
        return {"status": "success", "note": "登录成功——凭据已落盘 600，cloud_download 即刻生效（1080p）"}
    return {
        86101: {"status": "waiting", "note": "等待扫码"},
        86090: {"status": "scanned", "note": "已扫码，等待 App 确认"},
        86038: {"status": "expired", "note": "二维码已过期——重新调 action=qrcode"},
    }.get(code, {"status": f"unknown_{code}", "note": str(data.get("message", ""))[:80]})


@app.task(name="tasks.bili_login", bind=True, max_retries=0)
def bili_login(self, action: str):
    task_id = self.request.id
    update_meta(task_id, status="running", started_at=int(time.time()), retry_count=0)
    try:
        if action == "qrcode":
            result = _generate_qr(task_id)
        elif action == "wait":
            result = _poll_wait()
        else:
            raise ValueError("action 须为 qrcode/wait")
        update_meta(task_id, status="done", finished_at=int(time.time()), result=result)
        return result
    except Exception as e:
        update_meta(task_id, status="failed", error=str(e)[:300], finished_at=int(time.time()))
        raise
