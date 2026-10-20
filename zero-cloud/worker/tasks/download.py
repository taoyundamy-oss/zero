"""tasks.download — 媒体下载（C3.3 · E19：B站走 bilibili-api-python 正规 API）

两条路径：
  - bilibili.com/b23.tv → bilibili-api-python（WBI 签名正规 API，匿名可用 480p 级；
    E19：yt-dlp 网页路径被 B站风控 412）→ DASH 流（Referer+UA 头下载）→ video 模式
    ffmpeg 合并双流（先例：Alife 哔哩哔哩下载技能 V4.0）
  - 其他站点 → yt-dlp subprocess 通用兜底

产物 → /data/zero/downloads/{task_id}/ → 登记新 file_id（Z6 含 sha256）→ result 带 url。
配额：流式累计 + 完成复验（MAX_FILE_SIZE_MB）。
可选登录高清：/data/zero/.bili_credential.json（sessdata 等，参考 Alife 凭据格式；
缺省匿名）。凭据文件内容敏感——只读取不落日志。
取消局限：revoke 杀 celery 子进程时流下载/ffmpeg 子进程可能成孤儿——产物落
task_id 独立目录不污染他任务，残留由宿主清理（记 backlog）。
"""
import json
import os
import re
import subprocess
import time
from pathlib import Path
from urllib.parse import urlsplit

import httpx

from celery_app import app, update_meta
from ssrf import validate_url
from tasks.lib.files import register_file as _register_file, file_result as _file_result, MAX_FILE_SIZE_MB, guess_mime

DATA_DIR = os.getenv("DATA_DIR", "/data/zero")
TIMEOUT_S = 600
MAX_FILESIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024

# 画质封顶 1080p（控体积；更高画质需登录凭据）
MAX_HEIGHT = 1080
FORMAT_VIDEO = "b[height<=1080][ext=mp4]/bv*[ext=mp4]+ba[ext=m4a]/b[height<=1080]/b"
FORMAT_AUDIO = "ba[ext=m4a]/ba/b"

# S5.1：EXT_MIME 并入 tasks.lib.files.EXT_MIME（guess_mime 统一出口）
DESKTOP_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
_BV_RE = re.compile(r"BV[a-zA-Z0-9]{10}")


def _is_bilibili(url: str) -> bool:
    host = (urlsplit(url).hostname or "").lower()
    return host == "b23.tv" or host.endswith("bilibili.com")


# ═══════════ B站 分支（bilibili-api-python，E19）═══════════

def _download_stream(url: str, dest: Path, referer: str) -> None:
    """DASH 流下载（Referer+UA 头——缺头即 403）；流式累计配额上限"""
    headers = {"User-Agent": DESKTOP_UA, "Referer": referer}
    with httpx.stream("GET", url, headers=headers, follow_redirects=True,
                      timeout=httpx.Timeout(15.0, read=60.0)) as r:
        if r.status_code != 200:
            raise RuntimeError(f"流下载 HTTP {r.status_code}")
        total = 0
        with open(dest, "wb") as f:
            for chunk in r.iter_bytes(1 << 20):
                total += len(chunk)
                if total > MAX_FILESIZE_BYTES:
                    raise ValueError(f"流超过 {MAX_FILE_SIZE_MB}MB 配额上限")
                f.write(chunk)


def _resolve_bvid(url: str) -> str | None:
    """BV 号提取：URL 直配 → 短链跟随重定向后提取"""
    m = _BV_RE.search(url)
    if m:
        return m.group(0)
    r = httpx.get(url, headers={"User-Agent": DESKTOP_UA}, follow_redirects=True, timeout=15)
    m = _BV_RE.search(str(r.url)) or _BV_RE.search(r.text[:20000])
    return m.group(0) if m else None


def _bili_credential():
    """可选登录凭据（高清画质）：/data/zero/.bili_credential.json（Alife 同格式）"""
    p = Path(DATA_DIR) / ".bili_credential.json"
    if not p.is_file():
        return None
    try:
        from bilibili_api import Credential
        d = json.loads(p.read_text(encoding="utf-8"))
        return Credential(
            sessdata=d.get("sessdata", ""), bili_jct=d.get("bili_jct", ""),
            buvid3=d.get("buvid3", ""), dedeuserid=d.get("dedeuserid", ""),
            ac_time_value=d.get("ac_time_value", ""),
        )
    except Exception:
        return None


def _download_bilibili(url: str, mode: str, out_dir: Path) -> tuple[Path, str, dict]:
    """B站正规 API 下载。返回 (产物路径, mime, 附加结果字段)"""
    from bilibili_api import sync
    from bilibili_api import video as bvideo

    bvid = _resolve_bvid(url)
    if not bvid:
        raise ValueError("无法从链接提取 BV 号")
    cred = _bili_credential()
    v = bvideo.Video(bvid=bvid, credential=cred) if cred else bvideo.Video(bvid=bvid)
    info = sync(v.get_info())
    title = info.get("title", "") if isinstance(info, dict) else ""
    dl = sync(v.get_download_url(0))
    dash = dl.get("dash") or {}
    audios = dash.get("audio") or []
    videos = dash.get("video") or []
    if not audios:
        raise RuntimeError("无音频流（dash 为空——分区/权限/风控限制）")

    safe = re.sub(r'[\\/:*?"<>|]', "_", title)[:80] or bvid
    referer = f"https://www.bilibili.com/video/{bvid}"
    extra: dict = {"bvid": bvid, "title": title, "engine": "bilibili_api"}
    audio = max(audios, key=lambda x: x.get("bandwidth") or 0)
    audio_url = audio.get("base_url") or audio.get("baseUrl") or ""

    if mode == "audio":
        dest = out_dir / f"{safe}.m4a"
        _download_stream(audio_url, dest, referer)
        return dest, "audio/mp4", extra

    if not videos:
        raise RuntimeError("无视频流")
    cand = [x for x in videos if (x.get("height") or 0) <= MAX_HEIGHT] or videos
    best = max(cand, key=lambda x: (x.get("width") or 0) * (x.get("height") or 1))
    video_url = best.get("base_url") or best.get("baseUrl") or ""

    v_tmp, a_tmp = out_dir / "v.m4s", out_dir / "a.m4s"
    _download_stream(video_url, v_tmp, referer)
    _download_stream(audio_url, a_tmp, referer)
    dest = out_dir / f"{safe}.mp4"
    proc = subprocess.run(
        ["ffmpeg", "-y", "-i", str(v_tmp), "-i", str(a_tmp),
         "-c:v", "copy", "-c:a", "aac", "-b:a", "256k",
         "-movflags", "+faststart", str(dest)],
        capture_output=True, text=True, timeout=TIMEOUT_S)
    for t in (v_tmp, a_tmp):
        try:
            t.unlink()
        except OSError:
            pass
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg 合并失败: {proc.stderr.strip()[-200:]}")
    extra["resolution"] = f"{best.get('width', '?')}x{best.get('height', '?')}"
    return dest, "video/mp4", extra


# ═══════════ 通用 yt-dlp 兜底 ═══════════

def _download_ytdlp(url: str, mode: str, out_dir: Path) -> tuple[Path, str, dict]:
    args = [
        "yt-dlp",
        "--no-playlist",                       # 单条目（列表页误传防炸盘）
        "--max-filesize", str(MAX_FILESIZE_BYTES),
        "--retries", "3",
        "--no-progress", "--quiet", "--no-warnings",
        "-o", str(out_dir / "%(title).100B.%(ext)s"),
    ]
    if mode == "video":
        args += ["-f", FORMAT_VIDEO, "--merge-output-format", "mp4"]
    else:
        args += ["-f", FORMAT_AUDIO, "-x", "--audio-format", "mp3", "--audio-quality", "5"]
    args.append(url)

    try:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=TIMEOUT_S)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"下载超时（>{TIMEOUT_S}s）") from None
    if proc.returncode != 0:
        raise RuntimeError(f"yt-dlp 退出码 {proc.returncode}: {proc.stderr.strip()[-300:]}")

    files = [p for p in out_dir.glob("*") if p.is_file()]
    if not files:
        raise RuntimeError("下载流程完成但未找到产物文件（可能被 --max-filesize 预检拦截）")
    out = max(files, key=lambda p: p.stat().st_size)
    return out, guess_mime(out), {"engine": "yt-dlp"}


# ═══════════ 任务入口 ═══════════

@app.task(name="tasks.download", bind=True, max_retries=0)
def download(self, url: str, mode: str = "video"):
    task_id = self.request.id
    update_meta(task_id, status="running", started_at=int(time.time()), retry_count=0)
    out_dir = Path(DATA_DIR) / "downloads" / task_id
    try:
        if mode not in ("video", "audio"):
            raise ValueError(f"未知 mode: {mode}（video/audio）")
        okv, why = validate_url(url)  # D106 L2
        if not okv:
            raise ValueError(f"URL 不允许（SSRF L2）: {why}")

        out_dir.mkdir(parents=True, exist_ok=True)
        if _is_bilibili(url):
            out, mime, extra = _download_bilibili(url, mode, out_dir)
        else:
            out, mime, extra = _download_ytdlp(url, mode, out_dir)

        if out.stat().st_size > MAX_FILESIZE_BYTES:
            raise ValueError(f"产物 {out.stat().st_size // 1048576}MB 超过 {MAX_FILE_SIZE_MB}MB 配额")
        reg = _register_file(out, mime)
        result = {
            "url_source": url, "mode": mode, "filename": out.name,
            **extra, **_file_result(reg),
        }
        update_meta(task_id, status="done", finished_at=int(time.time()), result=result)
        return result
    except Exception as e:
        update_meta(task_id, status="failed", error=str(e)[:300], finished_at=int(time.time()))
        raise
