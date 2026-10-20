#!/usr/bin/env python3
# zero_supervisor.py —— Zero 控制面 supervisor（Termux 原生层运行，D69 管理者）。
# 契约源：ZeroV2-模块化重构方案.md §15（三通道）/§十六（封板表）/§12.6（保活协议）。
# stdlib only（shim 零依赖精神同源）。supervisor 只管进程，不跑模块逻辑（§12.6.5）。
#
# 启动：python3 zero_supervisor.py [port]（TermuxBridge.ensureSupervisor 经 RUN_COMMAND）
# 日志：$PREFIX/zero/logs/supervisor.log
import json
import os
import signal
import struct
import subprocess
import sys
import threading
import time
import ast
import hashlib
import hmac
import selectors
import uuid
from pathlib import Path
from zero_release import ProcessRuntime, ReleaseStore, managed_socket_rpc, process_record, kill_process_tree, process_snapshot, process_tree
from zero_host import FILES
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ═══ 环境与常量（M1.4 教训：背景进程 PATH 不含 $PREFIX/bin——全部绝对路径 + 自补 PATH）═══
PREFIX = "/data/data/com.termux/files/usr"
os.environ["PATH"] = PREFIX + "/bin:" + os.environ.get("PATH", "")
ZERO_DIR = os.path.join(PREFIX, "zero")
AUTH_FILE = Path(ZERO_DIR) / '.control-token'
LOG_DIR = os.path.join(ZERO_DIR, "logs")
MODULES_DIR_DEB = "/root/zero/modules"          # Debian 侧（模块真身）
HTTP_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 7878
SOCKET_ABSTRACT = "\0zero-supervisor"            # §十六：AF_UNIX 抽象名双通道
EXEC_TIMEOUT_DEFAULT = 120                        # §15.1 默认
TRUNCATE_BYTES = 64 * 1024                        # 64KB 尾部保留（§15.1）
OUTPUT_DIR = "/root/zero/exec-results"
OUTPUT_FILE_LIMIT = 2 * 1024 * 1024
OUTPUT_KEEP = 32
EXEC_SLOTS = threading.BoundedSemaphore(4)
ACTIVE_OUTPUTS = set()
RESTART_MAX = 3                                   # A-II-3 局部层：3 次/5min
RESTART_WINDOW_S = 300


def detect_rootfs():
    """proot-distro 布局探测（M1.4 实测勘误）：5.x=containers/<d>/rootfs，旧版=install-rootfs/<d>/rootfs。"""
    base = os.path.join(PREFIX, "var/lib/proot-distro")
    for layout in ("containers/debian/rootfs", "install-rootfs/debian/rootfs"):
        p = os.path.join(base, layout)
        if os.path.isdir(p):
            return p
    return None


ROOTFS = detect_rootfs()
DEB_CMD = [os.path.join(PREFIX, "bin/proot-distro"), "login", "debian", "--"]  # D69：默认 Debian 内执行

# Freeze identity at process startup. Replacing files cannot authenticate an old process.
ASSET_HASHES = {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest() for name in FILES}
SDK_TREE = ast.parse(Path(__file__).with_name("zero_tool.py").read_bytes())
SDK_VERSION = next(ast.literal_eval(node.value) for node in SDK_TREE.body if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == "SDK_VERSION" for target in node.targets))


def bundle_metadata():
    try:
        installed = {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest() for name in FILES}
        debian = hashlib.sha256((Path(ROOTFS) / "root/zero/zero_sdk/zero_tool.py").read_bytes()).hexdigest() if ROOTFS else None
    except OSError:
        installed, debian = {}, None
    return {"sdk_version": SDK_VERSION, "asset_hashes": ASSET_HASHES,
            "installed_asset_hashes": installed, "debian_sdk_hash": debian}

for d in (ZERO_DIR, LOG_DIR):
    os.makedirs(d, exist_ok=True)
LOG_F = open(os.path.join(LOG_DIR, "supervisor.log"), "a", buffering=1)


def log(msg):
    LOG_F.write("[%d] %s\n" % (time.time(), msg))


def deb_path(p):
    """Debian 绝对路径 → Termux 侧 rootfs 真实路径（文件通道用；无容器时原样返回）。"""
    if ROOTFS and os.path.isabs(p):
        return os.path.join(ROOTFS, p.lstrip("/"))
    return p


def tail_bytes(b, limit=TRUNCATE_BYTES):
    return b[-limit:]


# ═══ 状态（supervisor 内单一锁——量级小，不做细粒度）═══
LOCK = threading.RLock()
MODULES = {}    # id → {proc, pgid, state(ACTIVE|STOPPED|CRASHED), endpoint, transport, crashes:[ts], log}
SERVICES = {}   # name → {proc, pgid, cmd, cwd, log}（D51 登记表）
SESSIONS = {}   # session_id → cwd（P1-2 隔离，默认 default）
BOOT_TS = time.time()
HOST_STATE = {"ready": False, "phase": "starting", "error_type": None}
RUNTIME = None


def read_cmdline(pid):
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            return f.read().replace(b"\0", b" ").decode("utf-8", "ignore").strip()
    except Exception:
        return ""


def kill_group_verified(pgid, expect_sig, grace_s=3.0):
    """§十六 P0-2：进程组 TERM→3s→KILL；三校验（组存在 + cmdline 含期望签名 + 记录一致）。"""
    try:
        cmdline = read_cmdline(pgid)
        if expect_sig and expect_sig not in cmdline:
            log("kill_rejected: pgid=%d cmdline=%r 不含 %r（三校验防误杀）" % (pgid, cmdline[:120], expect_sig))
            return False
        record = process_record(pgid)
        if not record:
            return True
        return kill_process_tree(pgid, record["identity"], grace_s=min(grace_s, 1.0))
    except ProcessLookupError:
        return True
    except Exception as e:
        log("kill_error: pgid=%d %s" % (pgid, e))
        return False


# ═══ 模块生命周期（A-II-1：supervisor=控制面；模块=数据面独立进程组）═══

def module_start(mod_id, body):
    try:
        return RELEASES.start(mod_id, body)
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def module_stop(mod_id):
    return RELEASES.stop(mod_id)


def module_rpc(mod_id, body):
    try:
        return managed_socket_rpc(RELEASES, mod_id, body)
    except Exception as exc:
        message = str(exc) if isinstance(exc, ValueError) else "module_rpc_failed"
        request = body.get("request") if isinstance(body, dict) else None
        return {"jsonrpc": "2.0", "id": request.get("id") if isinstance(request, dict) else None,
                "error": {"code": -32000, "message": message}}


# ═══ 工作站三通道（§15）═══

class OutputCapture:
    """Bounded tail plus capped original prefix, never a full-output allocation."""
    def __init__(self, path):
        self.path, self.tail, self.total = path, bytearray(), 0
        self.file = None

    def __enter__(self):
        self.file = self.path.open('xb')
        return self

    def append(self, chunk):
        remaining = max(0, OUTPUT_FILE_LIMIT - self.total)
        if remaining:
            self.file.write(chunk[:remaining])
        self.total += len(chunk)
        self.tail.extend(chunk)
        del self.tail[:-TRUNCATE_BYTES]

    def __exit__(self, *args):
        self.file.close()


def collect_exec(proc, captures, timeout_s):
    deadline = time.monotonic() + timeout_s
    root = process_record(proc.pid)
    known = {}
    timed_out, cleanup_ok = False, True
    next_snapshot = 0
    try:
        with selectors.DefaultSelector() as poller:
            for pipe, capture in zip((proc.stdout, proc.stderr), captures):
                os.set_blocking(pipe.fileno(), False)
                poller.register(pipe, selectors.EVENT_READ, capture)
            while poller.get_map() or proc.poll() is None:
                now = time.monotonic()
                if root and now >= next_snapshot:
                    known.update({r['pid']: r for r in process_tree(proc.pid, root['identity'], process_snapshot())})
                    next_snapshot = now + .1
                if now >= deadline:
                    timed_out = True
                    cleanup_ok = bool(root) and kill_process_tree(proc.pid, root['identity'], known.values())
                    break
                for key, _ in poller.select(min(.1, max(0, deadline - now))):
                    chunk = os.read(key.fileobj.fileno(), TRUNCATE_BYTES)
                    if chunk:
                        key.data.append(chunk)
                    else:
                        poller.unregister(key.fileobj)
        if timed_out:
            # Read only what is currently available; escaped descendants cannot pin a reader.
            for pipe, capture in zip((proc.stdout, proc.stderr), captures):
                for _ in range(16):
                    try:
                        chunk = os.read(pipe.fileno(), TRUNCATE_BYTES)
                    except BlockingIOError:
                        break
                    if not chunk:
                        break
                    capture.append(chunk)
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            cleanup_ok = False
        return timed_out, cleanup_ok
    except BaseException:
        if root:
            kill_process_tree(proc.pid, root['identity'], known.values())
        raise
    finally:
        proc.stdout.close()
        proc.stderr.close()


def exec_cmd(body):
    if not EXEC_SLOTS.acquire(blocking=False):
        return {"error": {"error": "exec_busy: maximum four active commands"}}
    try:
        return _exec_cmd(body)
    finally:
        EXEC_SLOTS.release()


def _exec_cmd(body):
    """Dual-stream draining with bounded memory/disk and explicit original-output limits."""
    cmd = body.get("cmd", "")
    if not cmd.strip():
        return {"error": "empty cmd"}
    session_id = body.get("session_id", "default")
    timeout_s = max(.1, min(float(body.get("timeout_s", EXEC_TIMEOUT_DEFAULT)), 3600))
    with LOCK:
        cwd = body.get("cwd") or SESSIONS.get(session_id) or "/root/work"
    wrapped = 'cd %s >/dev/null 2>&1; { %s; }; __rc=$?; printf "\\n__ZERO_CWD__%%s" "$PWD" >&2; exit $__rc' % (_shq(cwd), cmd)
    directory = Path(deb_path(OUTPUT_DIR))
    directory.mkdir(parents=True, exist_ok=True)
    name = uuid.uuid4().hex
    paths = [directory / (name + '.' + stream) for stream in ('stdout', 'stderr')]
    with LOCK:
        completed = sorted((p for p in directory.iterdir() if p not in ACTIVE_OUTPUTS
                            and len(p.stem) == 32 and all(c in '0123456789abcdef' for c in p.stem)
                            and p.suffix in ('.stdout', '.stderr')), key=lambda p: p.stat().st_mtime)
        for old in completed[:max(0, len(completed) - OUTPUT_KEEP + 2)]:
            old.unlink()
        ACTIVE_OUTPUTS.update(paths)
    try:
        with OutputCapture(paths[0]) as out, OutputCapture(paths[1]) as err:
            proc = subprocess.Popen(DEB_CMD + ["bash", "-c", wrapped], stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, start_new_session=True,
                                    stdin=subprocess.DEVNULL, env=dict(os.environ))
            timed_out, cleanup_ok = collect_exec(proc, (out, err), timeout_s)
        err_text = err.tail.decode("utf-8", "ignore")
        new_cwd = cwd
        idx = err_text.rfind("__ZERO_CWD__")
        if idx >= 0:
            new_cwd = err_text[idx + len("__ZERO_CWD__"):].strip() or cwd
            err_text = err_text[:idx].rstrip("\n")
        with LOCK:
            SESSIONS[session_id] = new_cwd
        return {"stdout": out.tail.decode("utf-8", "ignore"), "stderr": err_text,
                "exit": -1 if timed_out else proc.returncode, "cwd": new_cwd,
                "timed_out": timed_out, "cleanup_ok": cleanup_ok,
                "output_files": {stream: {"path": OUTPUT_DIR + '/' + path.name,
                    "bytes": capture.total, "saved_bytes": min(capture.total, OUTPUT_FILE_LIMIT),
                    "truncated": capture.total > OUTPUT_FILE_LIMIT}
                    for stream, path, capture in zip(('stdout', 'stderr'), paths, (out, err))}}
    finally:
        with LOCK:
            ACTIVE_OUTPUTS.difference_update(paths)


def _shq(s):
    return "'" + str(s).replace("'", "'\\''") + "'"


def ls_dir(path):
    real = deb_path(path)
    if not os.path.isdir(real):
        return {"error": "not_found: %s" % path}
    entries = []
    try:
        for name in sorted(os.listdir(real)):
            fp = os.path.join(real, name)
            st = os.stat(fp)
            entries.append({"name": name, "size": st.st_size, "mtime": int(st.st_mtime),
                            "is_dir": os.path.isdir(fp)})
    except Exception as e:
        return {"error": str(e)}
    return {"path": path, "entries": entries}


def read_file(path, offset=0, length=TRUNCATE_BYTES):
    real = deb_path(path)
    try:
        size = os.path.getsize(real)
        with open(real, "rb") as f:
            f.seek(max(0, offset))
            data = f.read(length)
        return {"text": data.decode("utf-8", "ignore"), "size": size, "offset": offset}
    except Exception as e:
        return {"error": str(e)}


# ═══ 进程与服务（§15.3 + D51 登记表）═══

def procs_list():
    out = []
    with LOCK:
        for mid, e in MODULES.items():
            alive = e.get("proc") and e["proc"].poll() is None
            out.append({"pgid": e["pgid"], "pid": e["proc"].pid if e.get("proc") else None,
                        "cmd": read_cmdline(e["proc"].pid) if alive else "",
                        "module_id": mid, "state": e["state"], "kind": "module"})
        for name, e in SERVICES.items():
            alive = e.get("proc") and e["proc"].poll() is None
            out.append({"pgid": e["pgid"], "pid": e["proc"].pid if e.get("proc") else None,
                        "cmd": e["cmd"], "service": name, "state": "ACTIVE" if alive else "DEAD",
                        "kind": "service"})
    return {"procs": out}


def proc_kill(pgid):
    """按 pgid 杀（校验：必须属管理域——module 或 service 记录在案）。"""
    with LOCK:
        known = any(e.get("pgid") == pgid for e in list(MODULES.values()) + list(SERVICES.values()))
    if not known:
        return {"ok": False, "error": "pgid 不在管理域（拒绝）"}
    return {"ok": kill_group_verified(pgid, None)}


SERVICES_FILE = os.path.join(ZERO_DIR, "services.json")


def services_save():
    with LOCK:
        data = [{"name": n, "cmd": e["cmd"], "cwd": e["cwd"]} for n, e in SERVICES.items() if e.get("autostart")]
    try:
        with open(SERVICES_FILE, "w") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
    except Exception as e:
        log("services_save_failed: %s" % e)


def service_start(body):
    name, cmd = body.get("name"), body.get("cmd")
    if not name or not cmd:
        return {"ok": False, "error": "name/cmd 必填"}
    cwd = body.get("cwd", "/root/work")
    log_path = os.path.join(LOG_DIR, "service_%s.log" % name)
    wrapped = 'cd %s >/dev/null 2>&1; exec bash -c %s' % (_shq(cwd), _shq(cmd))
    lf = open(log_path, "ab", buffering=0)
    try:
        proc = subprocess.Popen(DEB_CMD + ["bash", "-c", wrapped], stdout=lf, stderr=subprocess.STDOUT,
                                start_new_session=True, stdin=subprocess.DEVNULL, env=dict(os.environ))
    except Exception as e:
        lf.close()
        return {"ok": False, "error": str(e)}
    with LOCK:
        SERVICES[name] = {"proc": proc, "pgid": os.getpgid(proc.pid), "cmd": cmd, "cwd": cwd,
                          "log": log_path, "autostart": bool(body.get("autostart", True))}
    services_save()
    log("service_started: %s pid=%d" % (name, proc.pid))
    return {"ok": True, "pid": proc.pid, "pgid": SERVICES[name]["pgid"]}


def service_stop(name):
    with LOCK:
        e = SERVICES.get(name)
        if not e:
            return {"ok": False, "error": "not_found"}
        e["autostart"] = False
        ok = kill_group_verified(e["pgid"], None)
    services_save()
    return {"ok": ok}


def services_restore():
    """D51：supervisor 自身启动时恢复登记的 autostart 服务。"""
    try:
        with open(SERVICES_FILE) as f:
            for s in json.load(f):
                log("service_restore: %s" % s["name"])
                service_start({"name": s["name"], "cmd": s["cmd"], "cwd": s.get("cwd", "/root/work"),
                               "autostart": True})
    except FileNotFoundError:
        pass
    except Exception as e:
        log("services_restore_failed: %s" % e)


# ═══ HTTP 控制面（通道一）═══

def control_error(exc, method, path):
    return {"ok": False, "error": "control_plane_error", "error_type": type(exc).__name__,
            "operation": method + " " + path.split("?", 1)[0], "uncertain": method == "POST"}


def control_unavailable(path):
    if path.split("?", 1)[0] != "/health" and not HOST_STATE["ready"]:
        return {"ok": False, "error": "supervisor_not_ready", "phase": HOST_STATE["phase"],
                "uncertain": False, "retry_after_ms": 500}
    return None


def control_authorized(value):
    try:
        token = AUTH_FILE.read_text(encoding='ascii').strip()
        return (len(token) == 64 and all(c in '0123456789abcdef' for c in token)
                and isinstance(value, str) and len(value) == 64
                and all(c in '0123456789abcdef' for c in value) and hmac.compare_digest(value, token))
    except (OSError, UnicodeError):
        return False


def control_route(method):
    def decorate(route):
        def guarded(self):
            self._response_started = False
            try:
                if not control_authorized(self.headers.get('Authorization', '').removeprefix('Bearer ')):
                    self.close_connection = True
                    return self._json({'ok': False, 'error': 'unauthorized'}, 401)
                unavailable = control_unavailable(self.path)
                if unavailable:
                    # Reject before reading/dispatching; do not reuse unread POST bytes.
                    self.close_connection = True
                    return self._json(unavailable, 503)
                return route(self)
            except Exception as exc:
                error = control_error(exc, method, self.path)
                log("control_route_failed: %s %s" % (error["operation"], error["error_type"]))
                if self._response_started:
                    self.close_connection = True
                    return
                try:
                    self._json(error, 500)
                except (OSError, ValueError):
                    self.close_connection = True
        return guarded
    return decorate


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send_response(self, code, message=None):
        self._response_started = True
        return super().send_response(code, message)

    def _json(self, obj, status=200):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _serve_file(self, path, real):
        try:
            size = os.path.getsize(real)
        except Exception:
            self._json({"error": "not_found"}, 404)
            return
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            s = rng[6:].split("-")[0]
            start = int(s) if s else 0
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with open(real, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                buf = f.read(min(65536, remaining))
                if not buf:
                    break
                self.wfile.write(buf)
                remaining -= len(buf)

    @control_route("GET")
    def do_GET(self):
        from urllib.parse import urlparse, parse_qs
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path.startswith("/module/") and u.path.endswith("/start-status"):
            return self._json(RELEASES.start_status(u.path.split("/")[2], q.get("request_id", "")))
        if u.path == "/release/status":
            return self._json(RELEASES.dispatch("status", q))
        if u.path == "/health":
            with LOCK:
                mods = [{"id": i, "state": e["state"], "pgid": e["pgid"], "endpoint": e["endpoint"],
                         "transport": e["transport"], "crashes": len(e["crashes"])}
                        for i, e in MODULES.items()]
                self._json({"alive": True, "release_protocol": 1, "pid": os.getpid(), "uptime_s": int(time.time() - BOOT_TS),
                            "rootfs": bool(ROOTFS), "modules": mods, **bundle_metadata(), **HOST_STATE,
                            "services": list(SERVICES.keys()), "sessions": len(SESSIONS)})
        elif u.path == "/procs":
            self._json(procs_list())
        elif u.path == "/services":
            with LOCK:
                self._json({"services": [{"name": n, "cmd": e["cmd"], "cwd": e["cwd"]} for n, e in SERVICES.items()]})
        elif u.path == "/ls":
            self._json(ls_dir(q.get("path", "/root/work")))
        elif u.path == "/read":
            self._json(read_file(q.get("path", ""), int(q.get("offset", "0")), int(q.get("len", str(TRUNCATE_BYTES)))))
        elif u.path == "/file":
            p = q.get("path", "")
            self._serve_file(p, deb_path(p))
        else:
            self._json({"error": "unknown path"}, 404)

    @control_route("POST")
    def do_POST(self):
        from urllib.parse import urlparse, parse_qs
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        # /file/upload 的 body 是 RAW 字节流（§15.2）——必须在 JSON 解析之前分流
        # （M2.1b 实测 bug 修复：原实现无条件 json.loads 致 upload 恒 400）
        if u.path == "/file/upload":
            p = q.get("path", "")
            if not p:
                return self._json({"error": "path 必填"}, 400)
            real = deb_path(p)
            try:
                os.makedirs(os.path.dirname(real), exist_ok=True)
                with open(real, "wb") as f:
                    f.write(raw)
                return self._json({"ok": True, "path": p, "size": len(raw)})
            except Exception as e:
                return self._json({"error": str(e)}, 500)
        try:
            body = json.loads(raw) if raw else {}
        except Exception:
            return self._json({"error": "bad json"}, 400)

        if u.path.startswith("/release/"):
            return self._json(RELEASES.dispatch(u.path.split("/")[-1], body))
        if u.path == "/exec":
            return self._json(exec_cmd(body))
        if u.path.startswith("/module/") and u.path.endswith("/start"):
            return self._json(module_start(u.path.split("/")[2], body))
        if u.path.startswith("/module/") and u.path.endswith("/stop"):
            return self._json(module_stop(u.path.split("/")[2]))
        if u.path.startswith("/module/") and u.path.endswith("/rpc"):
            return self._json(module_rpc(u.path.split("/")[2], body))
        if u.path == "/proc/kill":
            return self._json(proc_kill(int(body.get("pgid", 0))))
        if u.path == "/service/start":
            return self._json(service_start(body))
        if u.path == "/service/stop":
            return self._json(service_stop(body.get("name", "")))
        return self._json({"error": "unknown path"}, 404)

    def log_message(self, fmt, *args):
        pass


# ═══ Socket 控制面（通道二：AF_UNIX 抽象名 \0zero-supervisor，§十六 P0-1）═══
# 帧格式与 §4.4 同源：4B 大端长度 + JSON。请求 {"method","path","query","body"} → {"status","body"}。

def handle_frame_request(req):
    if not control_authorized(req.get('token')):
        return {'status': 401, 'body': {'ok': False, 'error': 'unauthorized'}}
    class Fake:
        pass
    h = Handler.__new__(Handler)  # 复用路由逻辑（不真起 HTTP）
    h.headers = {}
    from urllib.parse import urlparse, parse_qs
    u = urlparse(req.get("path", "/"))
    q = {k: v[0] for k, v in parse_qs(u.query).items()}
    body = req.get("body", {}) or {}
    method = req.get("method", "GET")
    # 复用实现：把路由抽公共函数的开销大于此处小范围重复——直接调用核心函数
    try:
        unavailable = control_unavailable(u.path)
        if unavailable:
            return {"status": 503, "body": unavailable}
        if method == "GET":
            if u.path == "/release/status":
                return {"status": 200, "body": RELEASES.dispatch("status", q)}
            if u.path == "/health":
                with LOCK:
                    return {"status": 200, "body": {
                        "alive": True, "release_protocol": 1, "pid": os.getpid(), "uptime_s": int(time.time() - BOOT_TS),
                        "rootfs": bool(ROOTFS), **bundle_metadata(), **HOST_STATE, "modules": [
                            {"id": i, "state": e["state"], "pgid": e["pgid"], "endpoint": e["endpoint"],
                             "transport": e["transport"], "crashes": len(e["crashes"])}
                            for i, e in MODULES.items()],
                        "services": list(SERVICES.keys()), "sessions": len(SESSIONS)}}
            if u.path == "/procs":
                return {"status": 200, "body": procs_list()}
            if u.path.startswith("/module/") and u.path.endswith("/start-status"):
                return {"status": 200, "body": RELEASES.start_status(u.path.split("/")[2], q.get("request_id", ""))}
            if u.path == "/ls":
                return {"status": 200, "body": ls_dir(q.get("path", "/root/work"))}
            if u.path == "/read":
                return {"status": 200, "body": read_file(
                    q.get("path", ""), int(q.get("offset", "0")), int(q.get("len", str(TRUNCATE_BYTES))))}
        elif method == "POST":
            if u.path.startswith("/release/"):
                return {"status": 200, "body": RELEASES.dispatch(u.path.split("/")[-1], body)}
            if u.path == "/exec":
                return {"status": 200, "body": exec_cmd(body)}
            if u.path.startswith("/module/") and u.path.endswith("/start"):
                return {"status": 200, "body": module_start(u.path.split("/")[2], body)}
            if u.path.startswith("/module/") and u.path.endswith("/stop"):
                return {"status": 200, "body": module_stop(u.path.split("/")[2])}
            if u.path.startswith("/module/") and u.path.endswith("/rpc"):
                return {"status": 200, "body": module_rpc(u.path.split("/")[2], body)}
            if u.path == "/proc/kill":
                return {"status": 200, "body": proc_kill(int(body.get("pgid", 0)))}
        return {"status": 404, "body": {"error": "unknown"}}
    except Exception as e:
        error = control_error(e, method, u.path)
        log("control_route_failed: %s %s" % (error["operation"], error["error_type"]))
        return {"status": 500, "body": error}


def socket_server():
    import socket as sk
    srv = sk.socket(sk.AF_UNIX, sk.SOCK_STREAM)
    srv.bind(SOCKET_ABSTRACT)
    srv.listen(16)
    log("socket_channel_up: %s" % SOCKET_ABSTRACT)

    def recvn(c, n):
        d = b""
        while len(d) < n:
            b = c.recv(n - len(d))
            if not b:
                return None
            d += b
        return d

    while True:
        conn, _ = srv.accept()
        threading.Thread(target=_socket_client, args=(conn, recvn), daemon=True).start()


def _socket_client(conn, recvn):
    try:
        conn.settimeout(5)
        while True:
            hdr = recvn(conn, 4)
            if not hdr:
                break
            length = struct.unpack(">I", hdr)[0]
            if length > 1024 * 1024:
                break
            req = json.loads(recvn(conn, length))
            resp = json.dumps(handle_frame_request(req), ensure_ascii=False).encode()
            conn.sendall(struct.pack(">I", len(resp)) + resp)
    except Exception:
        pass
    finally:
        conn.close()


# ═══ 生命周期 ═══

def shutdown(signum, frame):
    log("supervisor_shutdown: signal=%d——清理已登记管理域进程" % signum)
    HOST_STATE.update(ready=False, phase="stopping")
    runtime = RUNTIME
    if runtime:
        runtime.may_start = lambda: False
    with LOCK:
        modules = list(MODULES)
        pgids = [e["pgid"] for e in SERVICES.values()]
    for mid in modules:
        try:
            # Stop the runtime owner, preserving durable enabled intent for the next supervisor.
            runtime.stop(mid)
        except Exception as exc:
            log("supervisor_cleanup_failed: module=%s error=%s" % (mid, type(exc).__name__))
    for pgid in pgids:
        kill_group_verified(pgid, None)
    sys.exit(0)


def main():
    global RELEASES, MODULES, LOCK, RUNTIME
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    srv = ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), Handler)
    http_thread = threading.Thread(target=srv.serve_forever, daemon=True)
    http_thread.start()
    threading.Thread(target=socket_server, daemon=True).start()
    log("supervisor_up: http=127.0.0.1:%d rootfs=%s pid=%d" % (HTTP_PORT, ROOTFS, os.getpid()))
    print("ZERO_SUP_HTTP %d" % HTTP_PORT, flush=True)
    try:
        HOST_STATE.update(ready=False, phase="recovering", error_type=None)
        RUNTIME = ProcessRuntime(deb_path("/root/zero/releases"), DEB_CMD,
                                 map_path=deb_path, sdk_path="/root/zero/zero_sdk")
        LOCK = RUNTIME.lock
        MODULES = RUNTIME.entries
        RELEASES = ReleaseStore(deb_path("/root/zero/releases"), RUNTIME, "/root/zero/releases")
        services_restore()
        HOST_STATE.update(ready=True, phase="ready")
    except Exception as exc:
        HOST_STATE.update(ready=False, phase="failed", error_type=type(exc).__name__)
        log("supervisor_recovery_failed: %s" % type(exc).__name__)
    http_thread.join()


if __name__ == "__main__":
    main()
