# ZERO_MANIFEST: {"deps": ["requests", "websocket-client"], "termuxDeps": [], "runtime": "python", "configSchema": [{"key": "group_whitelist", "label": "群白名单（逗号分隔群号）", "type": "text", "default": "", "description": "仅白名单内的群响应@消息；留空=不响应任何群（安全默认）"}, {"key": "reply_private", "label": "私聊自动回复", "type": "toggle", "default": true, "description": "QQ 私聊消息触发 Zero 处理"}, {"key": "qq_dir", "label": "QQ 安装目录", "type": "text", "default": "/opt/qq", "description": "QQ/NapCat 安装根目录（探针装配位）"}, {"key": "ws_port", "label": "OneBot 正向 WS 端口", "type": "number", "default": 3001, "min": 1024.0, "max": 65535.0, "description": "NapCat OneBot11 正向 WebSocket 服务端口（事件+API）"}]}
"""QQ 本地通道模块（E2-QQ · D6/D11-R/D12-a 裁定落地）。

架构：napcat（QQ 协议端，本模块子进程，xvfb 无头）+ OneBot v11 正向 WS（事件）
+ HTTP API（发送）+ 本地事件缝推送（127.0.0.1:7879 → Zero TriggerHub）。

职责：
  收：私聊（可开关）→ 缝推送 source=QQ_PRIVATE payload="[qq号] 文本"
      群聊@我 且 群在白名单 → source=QQ_GROUP payload="[群:群号] [qq号] 文本"
      其余群消息 → 仅本地环形缓存（qq_group_history 可查，零打扰）
  发：dyn_qqbot_send（私聊/群）——Zero QQ 指令（D12-a）的回复通道
  管理：napcat 生命周期（登录二维码经 qq_status 交付；登录后自动写 OneBot 网络配置并重启）
  扩展：群历史探查（get_group_msg_history）/ 戳一戳（group_poke）
"""
import json
import os
import re
import subprocess
import threading
import time

# supervisor 不注入 PYTHONPATH——模块须自举 SDK 路径（bili_downloader 先例/Zero 实证
# 2026-09-20：漏此行连崩 7 次 ModuleNotFoundError；指引模板已同步补此行）
import sys as _sys
_sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "zero_sdk"))

import requests
from zero_tool import tool, serve, serve_socket, get_config

DATA_DIR = os.environ.get("ZERO_MOD_DATA") or os.path.join(
    os.path.expanduser("~"), "zero", "data", "qqbot")
SEAM_URL = "http://127.0.0.1:7879/event"

_state = {
    "napcat_proc": None,
    "phase": "init",        # init/booting/qr/logged_in/online/error
    "qr_url": "",
    "self_id": "",
    "error": "",
    "group_ring": {},       # gid -> [(qq, name, text, ts)]
    "seen_seq": set(),
}
_lock = threading.Lock()


def _log(msg):
    print(f"[qqbot] {msg}", flush=True)


def _cfg():
    try:
        return get_config() or {}
    except Exception:
        return {}


def _qq_dir():
    return _cfg().get("qq_dir", "/opt/qq").rstrip("/") or "/opt/qq"


def _ws_port():
    try:
        return int(float(_cfg().get("ws_port", 3001)))
    except Exception:
        return 3001


def _whitelist():
    raw = str(_cfg().get("group_whitelist", "") or "")
    return {g.strip() for g in raw.replace("，", ",").split(",") if g.strip()}


# ═══ napcat 生命周期 ═══

def _find_qqbin():
    qq = _qq_dir()
    for cand in (os.path.join(qq, "usr", "local", "bin", "qq"),
                 os.path.join(qq, "opt", "QQ", "qq"), os.path.join(qq, "qq")):
        if os.path.isfile(cand):
            return cand
    out = subprocess.run(["find", qq, "-maxdepth", "5", "-type", "f", "-name", "qq"],
                         capture_output=True, text=True, timeout=20).stdout.strip()
    return out.split("\n")[0] if out else None


def _napcat_log():
    return os.path.join(DATA_DIR, "napcat.log")


def _spawn_napcat():
    os.makedirs(DATA_DIR, exist_ok=True)
    qqbin = _find_qqbin()
    if not qqbin:
        _state["phase"], _state["error"] = "error", f"QQ 二进制未找到于 {_qq_dir()}"
        return
    logf = open(_napcat_log(), "ab")
    # 探针教训（--kill-on-exit）：本进程即 supervisor 托管的常驻锚，napcat 为子进程同会话存活
    p = subprocess.Popen(["xvfb-run", "-a", qqbin, "--no-sandbox"],
                         stdout=logf, stderr=subprocess.STDOUT,
                         cwd=DATA_DIR, start_new_session=True)
    _state["napcat_proc"] = p
    if _state["phase"] != "logged_in":
        _state["phase"] = "booting"   # logged_in=重拉快速通道（配置已写，跳过登录检测）
    _log(f"napcat spawned pid={p.pid} bin={qqbin} phase={_state['phase']}")


def _tail_log(n=4096):
    try:
        with open(_napcat_log(), "rb") as f:
            f.seek(0, 2); size = f.tell()
            f.seek(max(0, size - n))
            return f.read().decode("utf-8", "replace")
    except Exception:
        return ""


def _api(path, payload=None, timeout=8):
    """OneBot11 HTTP API（NapCat 本地）。"""
    r = requests.post(f"http://127.0.0.1:{_ws_port() - 1}/{path}", json=payload or {},
                      timeout=timeout)
    r.raise_for_status()
    return r.json()


def _write_onebot_config(uin):
    """登录成功后写 NapCat OneBot11 网络配置（正向 WS + HTTP API，仅本机）并重启。"""
    cfg_dir = os.path.join(_qq_dir(), "napcat", "config")
    path = os.path.join(cfg_dir, f"onebot11_{uin}.json")
    if os.path.isfile(path):
        try:
            if json.load(open(path)).get("network", {}).get(
                    "websocketServers", [{}])[0].get("enable"):
                return False  # 已配置
        except Exception:
            pass
    os.makedirs(cfg_dir, exist_ok=True)
    tok = os.urandom(8).hex()
    json.dump({
        "network": {
            "websocketServers": [{"name": "zero", "enable": True, "host": "127.0.0.1",
                                  "port": _ws_port(), "messagePostFormat": "array",
                                  "reportSelfMessage": False, "token": tok, "debug": False}],
            "httpServers": [{"enable": True, "host": "127.0.0.1",
                             "port": _ws_port() - 1, "token": tok, "debug": False}],
        }
    }, open(path, "w"), ensure_ascii=False)
    open(os.path.join(DATA_DIR, "onebot_token"), "w").write(tok)
    _log(f"onebot11 config written for {uin}, restarting napcat")
    _stop_napcat()
    return True


def _stop_napcat():
    p = _state.get("napcat_proc")
    if p:
        try:
            p.terminate(); p.wait(5)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
        _state["napcat_proc"] = None


def _manager_loop():
    """napcat 状态机：拉起→捕二维码→登录→写配置→WS 连接→在线；死亡自愈（任意相位重拉）。"""
    while True:
        try:
            p = _state.get("napcat_proc")
            if p is None or p.poll() is not None:
                # ⭐ 自愈（Zero 评审抓出的命门）：online 死亡不再静默——重拉。
                # online→logged_in 快速通道（OneBot 配置已写，重拉后 API 探测即恢复，
                # 免重走二维码/登录检测）；其余相位照常 booting。
                if _state["phase"] == "online":
                    _log("napcat died while online — self-heal respawn")
                    _state["phase"] = "logged_in"
                _spawn_napcat()
                time.sleep(10)
                continue
            log = _tail_log()
            m = re.findall(r"https://txz\.qq\.com/p\?k=[^\s\"'）)]+", log)
            if m:
                _state["qr_url"] = m[-1]
                if _state["phase"] in ("booting",):
                    _state["phase"] = "qr"
            if "登录成功" in log or "Login success" in log:
                if _state["phase"] in ("booting", "qr"):
                    _state["phase"] = "logged_in"
                    _log("login detected, resolving uin...")
                    uin = ""
                    mu = re.findall(r"uinz?[:=]\s*(\d{5,12})", log) or \
                         re.findall(r"账号[:：]\s*(\d{5,12})", log)
                    if mu:
                        uin = mu[-1]
                    if not uin:
                        try:
                            uin = str(_api("get_login_info").get("data", {}).get("user_id", ""))
                        except Exception:
                            pass
                    if uin:
                        _state["self_id"] = uin
                        if _write_onebot_config(uin):
                            time.sleep(10); continue
            if _state["phase"] == "logged_in":
                # 配置已写且 napcat 重启后——探测 OneBot API 通即转在线并启 WS
                try:
                    info = _api("get_login_info")
                    if info.get("status") == "ok" or info.get("retcode") == 0:
                        _state["self_id"] = str(info.get("data", {}).get("user_id", _state["self_id"]))
                        _state["phase"] = "online"
                        _state["qr_url"] = ""
                        _log(f"online as {_state['self_id']}")
                        _start_ws_thread()
                except Exception:
                    pass
        except Exception as e:
            _log(f"manager error: {e}")
        time.sleep(6)


# ═══ WS 事件面（收）═══

def _push(source, payload):
    try:
        requests.post(SEAM_URL, json={"source": source, "payload": payload}, timeout=6)
        _log(f"seam push {source} len={len(payload)}")
    except Exception as e:
        _log(f"seam push failed: {e}")


def _msg_text(msg):
    """OneBot message（array 格式）→ 纯文本。"""
    out = []
    for seg in msg if isinstance(msg, list) else []:
        t = seg.get("type")
        if t == "text":
            out.append(seg.get("data", {}).get("text", ""))
        elif t == "at":
            out.append(f"@{seg.get('data', {}).get('qq', '')}")
        elif t == "image":
            out.append("[图片]")
        elif t == "record":
            out.append("[语音]")
        elif t == "face":
            out.append("[表情]")
    return "".join(out).strip()


def _on_event(ev):
    if ev.get("post_type") != "message":
        return  # notice/request（含戳一戳感知）后续按需扩展
    msg_id = ev.get("message_id")
    if msg_id in _state["seen_seq"]:
        return
    _state["seen_seq"].add(msg_id)
    if len(_state["seen_seq"]) > 5000:
        _state["seen_seq"] = set(list(_state["seen_seq"])[-2500:])
    text = _msg_text(ev.get("message"))
    if not text:
        return
    sender = str(ev.get("sender", {}).get("user_id", ""))
    if ev.get("message_type") == "private":
        if not _cfg().get("reply_private", True):
            return
        _push("QQ_PRIVATE", f"[{sender}] {text[:2000]}")
    elif ev.get("message_type") == "group":
        gid = str(ev.get("group_id", ""))
        ring = _state["group_ring"].setdefault(gid, [])
        ring.append((sender, ev.get("sender", {}).get("nickname", ""), text, ev.get("time", 0)))
        if len(ring) > 200:
            del ring[:100]
        if gid in _whitelist() and f"@{_state['self_id']}" in text:
            _push("QQ_GROUP", f"[群:{gid}] [{sender}] {text[:2000]}")


def _start_ws_thread():
    """单飞守卫：自愈/抖动重入时防 WS 线程累积（旧线程随 phase 变化自行退出）。"""
    with _lock:
        if _state.get("ws_running"):
            return
        _state["ws_running"] = True

    def run():
        try:
            _ws_loop()
        finally:
            with _lock:
                _state["ws_running"] = False
    threading.Thread(target=run, daemon=True).start()


def _ws_loop():
    import websocket  # websocket-client（manifest deps 已装）
    tok = ""
    try:
        tok = open(os.path.join(DATA_DIR, "onebot_token")).read().strip()
    except Exception:
        pass
    url = f"ws://127.0.0.1:{_ws_port()}"
    while _state["phase"] == "online":
        try:
            ws = websocket.create_connection(url, timeout=30,
                                             header=[f"Authorization: Bearer {tok}"] if tok else [])
            _log("ws connected")
            while True:
                raw = ws.recv()
                if not raw:
                    break
                try:
                    _on_event(json.loads(raw))
                except Exception:
                    pass
            ws.close()
        except Exception as e:
            _log(f"ws dropped: {e}")
        time.sleep(5)


# ═══ FC 工具面（发/管理/探查）═══

@tool
def qq_status() -> dict:
    """查询 QQ 通道状态（napcat 进程/登录态/二维码/WS 连接/本机 QQ 号）。
    未登录时返回最新登录二维码 URL——用手机 QQ 扫码完成登录。
    Returns:
        dict: {phase, qr_url, self_id, error, napcat_pid}
    """
    p = _state.get("napcat_proc")
    return {"phase": _state["phase"], "qr_url": _state["qr_url"],
            "self_id": _state["self_id"], "error": _state["error"],
            "napcat_pid": p.pid if p else None}


@tool
def send(type: str, target: str, text: str) -> dict:
    """发送 QQ 消息（Zero 的 QQ 回复通道——QQ_PRIVATE/QQ_GROUP 消息一律经本工具回复）。

    Args:
        type: "private"（私聊）或 "group"（群聊）
        target: 私聊=对方 QQ 号；群聊=群号
        text: 消息文本
    Returns:
        dict: {message_id}
    """
    if type == "private":
        r = _api("send_private_msg", {"user_id": int(target), "message": [{"type": "text", "data": {"text": text}}]})
    elif type == "group":
        r = _api("send_group_msg", {"group_id": int(target), "message": [{"type": "text", "data": {"text": text}}]})
    else:
        raise ValueError("type 须为 private 或 group")
    if r.get("status") not in ("ok", "async") and r.get("retcode") not in (0, 1):
        raise RuntimeError(f"OneBot 发送失败: {json.dumps(r, ensure_ascii=False)[:120]}")
    return {"message_id": r.get("data", {}).get("message_id")}


@tool
def group_history(group_id: str, count: int = 20) -> dict:
    """主动探查群聊历史记录（get_group_msg_history）——自驱"看群动态"/按需回溯用。

    Args:
        group_id: 群号
        count: 拉取条数（≤50）
    Returns:
        dict: {messages: [{sender_qq, sender_name, text, time}]}
    """
    r = _api("get_group_msg_history",
             {"group_id": int(group_id), "count": max(1, min(50, count))})
    msgs = []
    for m in (r.get("data") or {}).get("messages", [])[-count:]:
        s = m.get("sender", {})
        msgs.append({"sender_qq": str(s.get("user_id", "")),
                     "sender_name": s.get("nickname", ""),
                     "text": _msg_text(m.get("message", []))[:500],
                     "time": m.get("time", 0)})
    return {"messages": msgs}


@tool
def poke(group_id: str, user_id: str) -> dict:
    """QQ 群戳一戳（group_poke 扩展 API）。

    Args:
        group_id: 群号
        user_id: 被戳成员 QQ 号
    Returns:
        dict: {ok}
    """
    r = _api("group_poke", {"group_id": int(group_id), "user_id": int(user_id)})
    return {"ok": r.get("retcode") == 0}


if __name__ == "__main__":
    os.makedirs(DATA_DIR, exist_ok=True)
    threading.Thread(target=_manager_loop, daemon=True).start()
    sock = os.environ.get("ZERO_MOD_SOCKET")
    serve_socket(sock) if sock else serve()
