"""Durable release protocol. Staging is quality control, not a same-UID sandbox.

The atomic journal is also the active-file pointer: a committed active deployment
resolves to immutable module.py plus its private venv. No global pip is invoked.
All mutations (including legacy starts) share one reentrant operation lock.
"""
import ast
import copy
import hashlib
import json
import os
import re
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid
import urllib.request
from pathlib import Path


def sdk_matches(version, specification):
    def number(value):
        if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,3}", value):
            raise ValueError("invalid_sdk_version")
        parts = tuple(int(part) for part in value.split("."))
        return parts + (0,) * (4 - len(parts))
    actual = number(version)
    results = []
    for clause in specification.split(","):
        match = re.fullmatch(r"\s*(>=|<=|==|!=|>|<|=)\s*([0-9]+(?:\.[0-9]+){0,3})\s*", clause)
        if not match:
            raise ValueError("unsupported_sdk_range")
        expected = number(match[2])
        results.append({">=": actual >= expected, "<=": actual <= expected, "==": actual == expected,
                        "=": actual == expected, "!=": actual != expected, ">": actual > expected,
                        "<": actual < expected}[match[1]])
    return all(results)


def validate_manifest(manifest, transport):
    """Validate declarations before staging. This does not certify runtime schema or resource enforcement."""
    for name in ("inputSchema", "outputSchema", "resourceLimits"):
        if name in manifest and not isinstance(manifest[name], dict):
            raise ValueError("invalid_manifest_" + name)
    for name in ("capabilities", "termuxDeps"):
        if name in manifest and (not isinstance(manifest[name], list) or any(
                not isinstance(value, str) or not value.strip() for value in manifest[name])):
            raise ValueError("invalid_manifest_" + name)
    if "sdkRange" in manifest and (not isinstance(manifest["sdkRange"], str) or not manifest["sdkRange"].strip()):
        raise ValueError("invalid_manifest_sdkRange")
    if "sdkRange" in manifest:
        sdk_matches("0", manifest["sdkRange"])  # Syntax only; build reads the actual runtime SDK.
    if "entrypoint" in manifest and (not isinstance(manifest["entrypoint"], str) or not re.fullmatch(
            r"[A-Za-z_][A-Za-z0-9_]*", manifest["entrypoint"])):
        raise ValueError("invalid_manifest_entrypoint")
    if "dataSchemaVersion" in manifest and (type(manifest["dataSchemaVersion"]) is not int or
                                            manifest["dataSchemaVersion"] <= 0):
        raise ValueError("invalid_manifest_dataSchemaVersion")
    if "transport" in manifest and manifest["transport"] != transport:
        raise ValueError("manifest_transport_mismatch")
    seconds = manifest.get("probe_timeout_s", 15)
    if type(seconds) is not int or not 5 <= seconds <= 120:
        raise ValueError("invalid_probe_timeout")
    if "healthContract" in manifest:
        health = manifest["healthContract"]
        match = re.fullmatch(r"endpoint_window_([0-9]+)s", health) if isinstance(health, str) else None
        if not match or int(match[1]) != seconds:
            raise ValueError("manifest_health_mismatch")


def managed_socket_rpc(store, mid, body):
    """Relay only the current managed socket generation; never an arbitrary endpoint."""
    identifier(mid)
    if not isinstance(body, dict):
        raise ValueError("invalid_rpc_body")
    request = body.get("request")
    timeout = body.get("timeout_ms")
    if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
        raise ValueError("invalid_rpc")
    if request.get("method") not in ("initialize", "notifications/initialized", "tools/list", "tools/call"):
        raise ValueError("invalid_rpc_method")
    if type(request.get("id")) is not int or request["id"] <= 0:
        raise ValueError("invalid_rpc_id")
    if type(timeout) is not int or not 1 <= timeout <= 3600000:
        raise ValueError("invalid_rpc_timeout")
    if len(canonical(request)) > 1024 * 1024:
        raise ValueError("request_too_large")
    with store.lock, store.backend.lock:
        entry = store.backend.entries.get(mid)
        if store.data["emergency"]:
            raise ValueError("emergency_stop")
        if not entry or entry.get("state") != "ACTIVE":
            raise ValueError("module_not_active")
        endpoint = entry.get("endpoint", "")
        if not endpoint.startswith("unix://") or body.get("endpoint") != endpoint:
            raise ValueError("stale_module_endpoint")
    # Do not hold the global control-plane lock during a tool call.
    result = rpc(endpoint, request, timeout / 1000)
    with store.lock, store.backend.lock:
        if store.data["emergency"] or store.backend.entries.get(mid) is not entry or entry.get("endpoint") != endpoint:
            raise ValueError("module_generation_changed")
    return result


def atomic_json(path, value):
    path = Path(path)
    tmp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with tmp.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, allow_nan=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)
    if os.name != "nt":
        fd = os.open(str(path.parent), os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}", value):
        raise ValueError("invalid_identifier")
    return value


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def rpc(endpoint, request, timeout=3):
    data = canonical(request)
    if endpoint.startswith("http://127.0.0.1:"):
        # No proxy environment for module loopback traffic.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(urllib.request.Request(endpoint, data, {"Content-Type": "application/json"}), timeout=timeout) as r:
            return json.loads(r.read(1024 * 1024))
    if endpoint.startswith("unix://"):
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
            conn.settimeout(timeout)
            conn.connect("\0" + endpoint[7:])
            conn.sendall(struct.pack(">I", len(data)) + data)
            def read(n):
                out = b""
                while len(out) < n:
                    chunk = conn.recv(n - len(out))
                    if not chunk:
                        raise RuntimeError("socket_eof")
                    out += chunk
                return out
            size = struct.unpack(">I", read(4))[0]
            if size > 1024 * 1024:
                raise ValueError("response_too_large")
            return json.loads(read(size))
    raise ValueError("invalid_endpoint")


def process_record(pid):
    """Kernel identity, never argv or a process-name guess about ownership."""
    try:
        p = Path("/proc/%d" % pid)
        fields = (p / "stat").read_text().rsplit(")", 1)[1].split()
        uid = int(next(line for line in (p / "status").read_text().splitlines()
                       if line.startswith("Uid:")).split()[1])
        return dict(pid=pid, identity=fields[19], parent=int(fields[1]), uid=uid,
                    name=(p / "comm").read_text().strip(), state=fields[0])
    except (OSError, ValueError, IndexError, StopIteration):
        return None


def process_snapshot():
    own = process_record(os.getpid())
    if not own:
        return {}
    result = {}
    for path in Path("/proc").iterdir():
        if not path.name.isdigit():
            continue
        try:
            # Native Termux can filter cheaply; fake-root PRoot must use kernel Uid.
            if os.getuid() == own["uid"] and path.stat().st_uid != own["uid"]:
                continue
        except OSError:
            continue
        record = process_record(int(path.name))
        if record and record["uid"] == own["uid"]:
            result[record["pid"]] = record
    return result


def process_tree(pid, identity, snapshot):
    root = snapshot.get(pid)
    if not root or root["identity"] != identity:
        return []
    depths = {pid: 0}
    pending = list(snapshot.values())
    while pending:
        found = [r for r in pending if r["pid"] not in depths and r["parent"] in depths]
        if not found:
            break
        for record in found:
            depths[record["pid"]] = depths[record["parent"]] + 1
        pending = [r for r in pending if r["pid"] not in depths]
    return [dict({k: v for k, v in snapshot[p].items() if k != "state"}, depth=depth)
            for p, depth in depths.items()]


def kill_process_tree(pid, identity, known=(), grace_s=.5):
    """Kill positively owned tracees before tracers, with kernel identity checks on every signal."""
    if pid <= 1 or pid == os.getpid():
        return False
    own = process_record(os.getpid())
    if not own:
        return False
    records = {r["pid"]: r for r in known}
    records.update({r["pid"]: r for r in process_tree(pid, identity, process_snapshot())})

    def alive(record):
        current = process_record(record["pid"])
        return current if (current and current["state"] != "Z" and current["uid"] == own["uid"]
                           and current["uid"] == record["uid"]
                           and current["identity"] == record["identity"]) else None

    def stop_batch(batch):
        for sig, wait in ((signal.SIGTERM, grace_s), (signal.SIGKILL, .5)):
            for record in batch:
                if alive(record):
                    try:
                        os.kill(record["pid"], sig)
                    except ProcessLookupError:
                        pass
            deadline = time.monotonic() + wait
            while any(alive(r) for r in batch) and time.monotonic() < deadline:
                time.sleep(.025)

    ordered = sorted(records.values(), key=lambda r: r.get("depth", 0), reverse=True)
    workers, tracers = [], []
    for record in ordered:
        current = alive(record)
        if current:
            (tracers if record["pid"] == pid or current["name"] in ("proot", "proot-distro")
             else workers).append(record)
    stop_batch(workers)
    # Workers must be gone before losing the tracer; otherwise PRoot tracees can spin.
    if any(alive(r) for r in workers):
        return False
    stop_batch(tracers)
    return not any(alive(r) for r in ordered)


class ProcessRuntime:
    """A separate process/session for every probe and active generation."""
    def __init__(self, root, command_prefix=(), map_path=lambda p: p, python="python3", sdk_path=None):
        self.root = Path(root)
        self.prefix = list(command_prefix)
        self.map_path = map_path
        self.python = python
        self.sdk_path = sdk_path or str(Path(__file__).parent)
        self.entries = {}
        self.on_crash = None
        self.may_start = lambda: True
        self.lock = threading.RLock()
        self.pid_file = self.root / "processes.json"
        self.root.mkdir(parents=True, exist_ok=True)
        self.pids = json.loads(self.pid_file.read_text()) if self.pid_file.exists() else {}
        self.tracker = None
        self.ownership_error = None
        self.journal_dirty = False

    def capture_owners(self):
        snapshot = process_snapshot()
        with self.lock:
            changed = False
            for key, entry in self.entries.items():
                record = self.pids.get(key)
                if not record or entry["proc"].poll() is not None:
                    continue
                tree = process_tree(record["pid"], record["identity"], snapshot)
                if not tree:
                    continue
                previous = {r["pid"]: r for r in record.get("children", [])
                            if snapshot.get(r["pid"], {}).get("identity") == r["identity"]
                            and snapshot[r["pid"]]["state"] != "Z"}
                previous.update({r["pid"]: r for r in tree if r["pid"] != record["pid"]})
                children = sorted(previous.values(), key=lambda r: r["pid"])
                if record.get("children") != children:
                    record["children"] = children
                    changed = True
            self.journal_dirty = self.journal_dirty or changed
            if self.journal_dirty:
                atomic_json(self.pid_file, self.pids)
                self.journal_dirty = False
            self.ownership_error = None

    def track_owners(self):
        if os.name == "nt":
            return
        with self.lock:
            if self.tracker is not None:
                return
            def track():
                while True:
                    with self.lock:
                        if not any(e["proc"].poll() is None for e in self.entries.values()):
                            self.tracker = None
                            return
                    try:
                        self.capture_owners()
                    except OSError as exc:
                        self.ownership_error = type(exc).__name__
                    time.sleep(.5)
            self.tracker = threading.Thread(target=track, daemon=True)
            self.tracker.start()

    def command(self, argv, env=None):
        if self.prefix:
            return self.prefix + ["env"] + [k + "=" + v for k, v in (env or {}).items()] + argv
        return argv

    def run(self, argv, timeout=180):
        # Output is spooled to disk to avoid unbounded pip output in memory.
        import tempfile
        with tempfile.TemporaryFile() as out:
            proc = subprocess.Popen(self.command(argv), stdout=out, stderr=subprocess.STDOUT,
                                    start_new_session=True)
            proc._zero_identity = self.identity(proc.pid)
            try:
                rc = proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                self._kill(proc)
                raise RuntimeError("command_timeout")
            if rc:
                out.seek(0, 2)
                out.seek(max(0, out.tell() - 4096))
                raise RuntimeError(out.read().decode("utf-8", "replace"))
            out.seek(0, 2)
            out.seek(max(0, out.tell() - 65536))
            return out.read().decode("utf-8", "replace")

    def sdk_identity(self, path=None):
        script = ("import ast,hashlib,json,sys;from pathlib import Path;"
                  "p=Path(sys.argv[1])/'zero_tool.py';b=p.read_bytes();tree=ast.parse(b);"
                  "v=[ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) "
                  "and any(isinstance(t,ast.Name) and t.id=='SDK_VERSION' for t in n.targets)];"
                  "print(json.dumps({'version':v[0] if len(v)==1 else None,'sha256':hashlib.sha256(b).hexdigest()}))")
        result = json.loads(self.run([self.python, "-c", script, path or self.sdk_path], timeout=15))
        if not isinstance(result.get("version"), str):
            raise ValueError("sdk_version_unknown")
        return result

    def check_sdk(self, release):
        specification = release.get("manifest", {}).get("sdkRange")
        if specification is None:
            return None  # Unmanaged legacy records have no compatibility declaration.
        identity = self.sdk_identity(release.get("sdk_path"))
        if not sdk_matches(identity["version"], specification):
            raise ValueError("sdk_incompatible")
        if release.get("validated_sdk") is not None and release["validated_sdk"] != identity:
            raise ValueError("sdk_changed_since_validation")
        return identity

    def freeze_sdk(self, release, identity):
        target = str(Path(release["path"]).parent / "sdk")
        script = ("import hashlib,os,sys;from pathlib import Path;"
                  "source=Path(sys.argv[1])/'zero_tool.py';b=source.read_bytes();"
                  "assert hashlib.sha256(b).hexdigest()==sys.argv[3],'sdk_changed_during_copy';"
                  "target=Path(sys.argv[2]);target.mkdir(parents=True,exist_ok=True);"
                  "p=target/'zero_tool.py.tmp';p.write_bytes(b);os.replace(p,target/'zero_tool.py')")
        self.run([self.python, "-c", script, self.sdk_path, target, identity["sha256"]], timeout=15)
        release["sdk_path"], release["validated_sdk"] = target, identity
        self.check_sdk(release)

    def env_identity(self, release):
        script = '''import hashlib,json,sys,sysconfig
from pathlib import Path
h=hashlib.sha256()
for folder in sorted(set((sysconfig.get_path('purelib'),sysconfig.get_path('platlib')))):
    base=Path(folder)
    for p in sorted(base.rglob('*')):
        if p.is_file() and '__pycache__' not in p.parts and p.suffix not in ('.pyc','.pyo'):
            h.update(str(p.relative_to(base)).encode());h.update(b'\\0')
            with p.open('rb') as stream:
                for block in iter(lambda:stream.read(65536),b''):
                    h.update(block)
            h.update(b'\\0')
lock=Path(sys.argv[1]);report=Path(sys.argv[2]);archives=[]
if report.exists():
    for item in json.loads(report.read_text())['install']:
        sha=item.get('download_info',{}).get('archive_info',{}).get('hashes',{}).get('sha256')
        if not isinstance(sha,str) or len(sha)!=64 or any(c not in '0123456789abcdefABCDEF' for c in sha):
            raise ValueError('dependency_archive_hash_missing')
        archives.append({'name':item['metadata']['name'],'version':item['metadata']['version'],'sha256':sha.lower()})
print(json.dumps({'site_sha256':h.hexdigest(),'lock_sha256':hashlib.sha256(lock.read_bytes()).hexdigest(),
                  'python_version':sys.version,'archives':sorted(archives,key=lambda x:x['name'])}))
'''
        lock = str(Path(release["path"]).parent / "requirements.lock")
        report = release["venv"] + "/install-report.json"
        return json.loads(self.run([release["python"], "-c", script, lock, report], timeout=30))

    def check_release(self, release):
        self.check_sdk(release)
        if "validated_env" in release and self.env_identity(release) != release["validated_env"]:
            raise ValueError("dependency_environment_changed")
        if "validated_termux" in release and self.termux_identity(release) != release["validated_termux"]:
            raise ValueError("termux_dependencies_changed")

    def termux_identity(self, release):
        names = release.get("manifest", {}).get("termuxDeps", [])
        if not names:
            return []
        if any(not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name) for name in names):
            raise ValueError("invalid_termux_dependency")
        # Native host packages, deliberately outside the per-release Debian prefix.
        result = subprocess.run(["dpkg-query", "-W", "-f=${Package}=${Version}:${Status}\n", *sorted(set(names))],
                                capture_output=True, text=True, timeout=10)
        rows = result.stdout.splitlines()
        if result.returncode or len(rows) != len(set(names)) or any(not row.endswith(":install ok installed") for row in rows):
            raise ValueError("termux_dependency_missing")
        return sorted(rows)

    def build(self, release):
        release["validated_termux"] = self.termux_identity(release)
        identity = self.check_sdk(release)
        if identity is not None:
            self.freeze_sdk(release, identity)
        self.run([self.python, "-m", "venv", release["venv"]])
        deps = release["manifest"].get("deps", [])
        if deps:
            self.run([release["python"], "-m", "pip", "--isolated", "install", "--require-virtualenv",
                      "--report", release["venv"] + "/install-report.json", "--disable-pip-version-check", *deps])
        self.run([release["python"], "-m", "pip", "--isolated", "check"])
        frozen = self.run([release["python"], "-m", "pip", "--isolated", "freeze", "--all"])
        (self.root / release["deployment_id"] / "requirements.lock").write_text(frozen, encoding="utf-8")
        release["validated_env"] = self.env_identity(release)

    @staticmethod
    def identity(pid):
        try:
            # comm can contain spaces/parentheses; field 22 is relative to last ).
            return Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1].split()[19]
        except OSError:
            return None

    @staticmethod
    def _kill(proc, record=None):
        if os.name == "nt":
            if proc.poll() is None:
                proc.kill()
        else:
            identity = (record or {}).get("identity", getattr(proc, "_zero_identity", None))
            if identity and not kill_process_tree(proc.pid, identity, (record or {}).get("children", [])):
                raise RuntimeError("owned_process_cleanup_incomplete")
        proc.wait(timeout=5)

    def recover(self):
        # Only kill a persisted group when kernel start identity still matches.
        for record in self.pids.values():
            pid, token = record["pid"], record["identity"]
            if token and not kill_process_tree(pid, token, record.get("children", [])):
                raise RuntimeError("owned_process_recovery_incomplete")
        self.pids = {}
        atomic_json(self.pid_file, self.pids)

    def start(self, key, release, probe=False):
        if not self.may_start():
            raise ValueError("emergency_stop")
        self.check_release(release)
        source_hash = hashlib.sha256(Path(self.map_path(release['path'])).read_bytes()).hexdigest()
        self.stop(key)
        sock = "zero-release-" + uuid.uuid4().hex
        env = dict(os.environ)
        values = {"ZERO_MOD_CONFIG": json.dumps(release.get("config", {})),
                  "ZERO_MOD_SOCKET": sock if release.get("transport") == "socket" else "",
                  "ZERO_RELEASE_PROBE": "1" if probe else "0", "PYTHONPATH": release.get("sdk_path", self.sdk_path),
                  "ZERO_RELEASE_MANIFEST": json.dumps(release.get("manifest", {}), ensure_ascii=False),
                  "PYTHONNOUSERSITE": "1", "PYTHONUNBUFFERED": "1"}
        env.update(values)
        proc = subprocess.Popen(self.command([release["python"], release["path"]], values),
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, start_new_session=True, env=env)
        proc._zero_identity = self.identity(proc.pid)
        entry = {"proc": proc, "pgid": proc.pid, "endpoint": None, "state": "STARTING",
                 "transport": release.get("transport", "http"), "crashes": [],
                 "path": release["path"], "release": copy.deepcopy(release), "stop_requested": False,
                 "source_hash": source_hash}
        with self.lock:
            self.entries[key] = entry
            self.pids[key] = {"pid": proc.pid, "identity": self.identity(proc.pid)}
            try:
                atomic_json(self.pid_file, self.pids)
            except BaseException:
                self._kill(proc)
                raise
        def reader():
            try:
                for raw in iter(proc.stdout.readline, b""):
                    line = raw.decode("utf-8", "replace").strip()
                    with self.lock:
                        if self.entries.get(key) is not entry or entry["proc"] is not proc:
                            continue
                        if line.startswith("ZERO_MOD_LISTENING "):
                            try:
                                port = int(line.split()[-1])
                                if 0 < port < 65536:
                                    entry["endpoint"] = "http://127.0.0.1:%d" % port
                            except ValueError:
                                pass
                        elif line == "ZERO_MOD_SOCKET " + sock:
                            entry["endpoint"] = "unix://" + sock
            finally:
                proc.stdout.close()
        def watcher():
            proc.wait()
            crashed = False
            with self.lock:
                # A watcher owns precisely one process generation. Never respawn
                # or mutate the replacement generation from a stale watcher.
                if self.entries.get(key) is entry and entry["proc"] is proc:
                    entry["state"] = "STOPPED" if entry["stop_requested"] else "CRASHED"
                    entry["endpoint"] = None
                    crashed = not entry["stop_requested"]
                    if crashed:
                        # Reap the previous generation before any restart callback.
                        self._kill(proc, self.pids.get(key))
            if crashed and self.on_crash and not probe:
                self.on_crash(key, release.get("deployment_id"))
        threading.Thread(target=reader, daemon=True).start()
        threading.Thread(target=watcher, daemon=True).start()
        self.track_owners()
        deadline = time.monotonic() + max(5, min(120, int(release.get("manifest", {}).get("probe_timeout_s", 15))))
        while time.monotonic() < deadline and proc.poll() is None and self.may_start():
            endpoint = entry["endpoint"]
            if endpoint:
                actual_transport = "socket" if endpoint.startswith("unix://") else "http"
                if actual_transport != release.get("transport", "http"):
                    self.stop(key)
                    raise ValueError("runtime_transport_mismatch")
                try:
                    response = rpc(endpoint, {"jsonrpc": "2.0", "id": "health", "method": "tools/list"})
                    if isinstance(response.get("result", {}).get("tools"), list) and proc.poll() is None:
                        if release.get("validated_sdk"):
                            info = rpc(endpoint, {"jsonrpc": "2.0", "id": "identity", "method": "initialize"})
                            actual = info.get("result", {}).get("serverInfo", {})
                            digest = hashlib.sha256(canonical(release.get("manifest", {}))).hexdigest()
                            if actual.get("sdkVersion") != release["validated_sdk"]["version"] or actual.get("contractDigest") != digest:
                                self.stop(key)
                                raise ValueError("runtime_contract_identity_mismatch")
                        entry["state"] = "ACTIVE"
                        if os.name != "nt":
                            self.capture_owners()
                        return {"ok": True, "pid": proc.pid, "pgid": proc.pid, "endpoint": endpoint, "state": "ACTIVE"}
                except ValueError:
                    raise
                except Exception:
                    pass
            time.sleep(.05)
        self.stop(key)
        raise RuntimeError("health_check_failed")

    def reuse(self, key, release):
        with self.lock:
            entry = self.entries.get(key)
            if not entry or entry['state'] != 'ACTIVE' or entry['release'] != release:
                return None
            proc = entry['proc']
            if proc.poll() is not None or not proc._zero_identity or self.identity(proc.pid) != proc._zero_identity:
                return None
        try:
            self.check_release(release)
            digest = hashlib.sha256(Path(self.map_path(release['path'])).read_bytes()).hexdigest()
            if digest != entry.get('source_hash'):
                return None
            response = rpc(entry['endpoint'], {'jsonrpc':'2.0','id':'reattach','method':'tools/list'})
            if not isinstance(response.get('result',{}).get('tools'), list):
                return None
        except (OSError, ValueError, RuntimeError):
            return None
        with self.lock:
            if self.entries.get(key) is not entry or entry['state'] != 'ACTIVE' or proc.poll() is not None:
                return None
            return dict(ok=True,pid=proc.pid,pgid=entry['pgid'],endpoint=entry['endpoint'],state='ACTIVE',reused=True)

    def stop(self, key):
        with self.lock:
            entry = self.entries.get(key)
            if entry:
                entry["stop_requested"] = True
                self._kill(entry["proc"], self.pids.get(key))
                entry["endpoint"] = None
                entry["state"] = "STOPPED"
            self.pids.pop(key, None)
            atomic_json(self.pid_file, self.pids)
        return {"ok": True}

    def probe(self, release):
        key = "probe-" + uuid.uuid4().hex
        try:
            self.start(key, release, probe=True)
        finally:
            self.stop(key)
            self.entries.pop(key, None)


class ReleaseStore:
    def __init__(self, root, backend, runtime_root=None):
        self.root = Path(root).absolute()
        self.root.mkdir(parents=True, exist_ok=True)
        self.runtime_root = runtime_root or str(self.root)
        self.backend = backend
        self.lock = threading.RLock()
        # An OS lock also prevents two supervisor processes owning one journal.
        self.owner = (self.root / "owner.lock").open("a+b")
        if os.name == "nt":
            import msvcrt
            self.owner.seek(0)
            if not self.owner.read(1):
                self.owner.write(b"0")
                self.owner.flush()
            self.owner.seek(0)
            msvcrt.locking(self.owner.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(self.owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.journal = self.root / "journal.json"
        self.data = json.loads(self.journal.read_text(encoding="utf-8")) if self.journal.exists() else {
            "version": 1, "emergency": False, "releases": {}, "active": {}, "previous": {}, "pending": None}
        self.data.setdefault("enabled", {})
        self.data.setdefault("legacy_specs", {})
        self.emergency_file = self.root / "emergency.json"
        self.emergency_requested = threading.Event()
        if self.emergency_file.exists():
            self.data["emergency"] = json.loads(self.emergency_file.read_text())
        if self.data["emergency"]:
            self.emergency_requested.set()
        self.backend.may_start = lambda: not self.emergency_requested.is_set()
        self.crashes = {}
        self.start_receipts = {}  # Supervisor-local, at most one receipt per module.
        self.backend.on_crash = self.on_crash
        self.reconcile()

    def save(self):
        now = int(time.time() * 1000)
        for row in self.data["releases"].values():
            if row.get("saved_state") != row["state"]:
                row["updated_at"], row["saved_state"] = now, row["state"]
        atomic_json(self.journal, self.data)

    def close(self):
        self.owner.close()

    def local(self, dep):
        identifier(dep)
        path = self.root / dep
        if path.is_symlink() or path.resolve().parent != self.root.resolve():
            raise ValueError("invalid_release_path")
        return path

    def verify(self, r):
        base = self.local(r["deployment_id"])
        source = base / "module.py"
        if source.is_symlink() or source.resolve().parent != base.resolve():
            raise ValueError("invalid_source_path")
        payload = {k: r[k] for k in ("module_id", "manifest", "config", "transport")}
        payload["code"] = source.read_text(encoding="utf-8")
        digest = hashlib.sha256(canonical(payload)).hexdigest()
        if digest != r["content_hash"] or r["deployment_id"] != digest:
            raise ValueError("content_hash_mismatch")
        expected = self.runtime_root.rstrip("/\\") + "/" + digest
        if r["path"] != expected + "/module.py" or r["venv"] != expected + "/venv":
            raise ValueError("invalid_runtime_path")
        interpreter = "/Scripts/python.exe" if os.name == "nt" and not self.backend.prefix else "/bin/python"
        expected_python = self.backend.python if r.get("legacy") else r["venv"] + interpreter
        if r["python"] != expected_python or (base / "venv").is_symlink():
            raise ValueError("invalid_venv_path")
        return payload["code"]

    def result(self, r=None, error=None, **extra):
        return dict(ok=error is None, deployment_id=r["deployment_id"] if r else None,
                    state=r["state"] if r else "FAILED", content_hash=r["content_hash"] if r else None,
                    error=error, **extra)

    def prepare(self, body):
        mid = identifier(body["module_id"])
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,39}", mid):
            raise ValueError("invalid_module_id")
        code = body["code"]
        manifest, config = body.get("manifest", {}), body.get("config", {})
        transport = body.get("transport", "http")
        if not isinstance(code, str) or not isinstance(manifest, dict) or not isinstance(config, dict):
            raise ValueError("invalid_prepare_body")
        if not code.strip() or len(code.encode("utf-8")) > 512 * 1024:
            raise ValueError("empty_or_oversize_source")
        if self.data["emergency"]:
            raise ValueError("emergency_stop")
        if transport not in ("http", "socket"):
            raise ValueError("invalid_transport")
        validate_manifest(manifest, transport)
        deps = manifest.get("deps", [])
        if not isinstance(deps, list) or any(not isinstance(d, str) or not re.fullmatch(
                r"[A-Za-z0-9][A-Za-z0-9._-]*(?:\[[A-Za-z0-9_,.-]+\])?==[A-Za-z0-9][A-Za-z0-9.!+_-]*", d) for d in deps):
            raise ValueError("dependencies_require_exact_pins")
        payload = dict(module_id=mid, code=code, manifest=manifest, config=config, transport=transport)
        digest = hashlib.sha256(canonical(payload)).hexdigest()
        if digest in self.data["releases"]:
            r = self.data["releases"][digest]
            self.verify(r)
            return self.result(r, r.get("error"))
        base = self.local(digest)
        base.mkdir(exist_ok=True)
        source = base / "module.py"
        if source.is_symlink():
            raise ValueError("invalid_source_path")
        with source.open("w", encoding="utf-8", newline="") as stream:
            stream.write(code)
            stream.flush()
            os.fsync(stream.fileno())
        path = self.runtime_root.rstrip("/\\") + "/" + digest
        r = dict(payload, deployment_id=digest, content_hash=digest, state="STAGED", error=None,
                 created_at=int(time.time() * 1000), updated_at=int(time.time() * 1000),
                 code_size=len(code.encode("utf-8")), attempts=0,
                 path=path + "/module.py", venv=path + "/venv")
        del r["code"]
        r["python"] = r["venv"] + ("/Scripts/python.exe" if os.name == "nt" and not self.backend.prefix else "/bin/python")
        self.data["releases"][digest] = r
        self.save()
        return self.result(r)

    def validate(self, body):
        if self.data["emergency"]:
            raise ValueError("emergency_stop")
        r = self.data["releases"][identifier(body["deployment_id"])]
        if r["state"] in ("ACTIVE", "READY", "RETIRED"):
            self.verify(r)
            if hasattr(self.backend, "check_release"):
                self.backend.check_release(r)
            elif hasattr(self.backend, "check_sdk"):
                self.backend.check_sdk(r)
            return self.result(r)
        if r["state"] != "STAGED":
            return self.result(r, r.get("error") or "not_staged")
        try:
            code = self.verify(r)
            ast.parse(code)
            self.check_data_schema(r)
            r["state"] = "VALIDATING"
            r["attempts"] += 1
            self.save()
            self.backend.build(r)
            if self.emergency_requested.is_set():
                raise ValueError("emergency_stop")
            self.backend.probe(r)
            self.verify(r)
            r["state"], r["error"] = "READY", None
        except Exception as exc:
            r["state"], r["error"] = "FAILED", str(exc)
        self.save()
        return self.result(r, r["error"])

    def active(self, mid):
        dep = self.data["active"].get(mid)
        return self.data["releases"].get(dep)

    def check_data_schema(self, release):
        mid = release["module_id"]
        old = self.active(mid)
        legacy = self.root.parent / "modules" / (mid + ".py")
        if old is not None or legacy.exists():
            previous = old.get("manifest", {}).get("dataSchemaVersion", 1) if old else 1
            if release.get("manifest", {}).get("dataSchemaVersion", 1) != previous:
                raise ValueError("data_schema_change_requires_migration")

    def import_legacy(self, mid):
        if self.active(mid):
            return
        path = self.root.parent / "modules" / (identifier(mid) + ".py")
        if not path.is_file() or path.is_symlink():
            return
        running = self.backend.entries.get(mid, {}).get("release", {})
        result = self.prepare(dict(module_id=mid, code=path.read_text(encoding="utf-8"),
                                   manifest={}, config=running.get("config", {}),
                                   transport=running.get("transport", "http")))
        r = self.data["releases"][result["deployment_id"]]
        r.update(legacy=True, python=self.backend.python, state="ACTIVE")
        self.data["active"][mid] = r["deployment_id"]
        self.data["enabled"][mid] = True
        self.save()

    def activate(self, body):
        if self.data["emergency"]:
            raise ValueError("emergency_stop")
        r = self.data["releases"][identifier(body["deployment_id"])]
        mid = r["module_id"]
        self.verify(r)
        old = self.active(mid)
        if old is not r and r["state"] not in ("READY", "RETIRED"):
            return self.result(r, "not_ready")
        self.check_data_schema(r)
        if hasattr(self.backend, "check_release"):
            self.backend.check_release(r)
        elif hasattr(self.backend, "check_sdk"):
            self.backend.check_sdk(r)  # Gate before journaling intent or stopping the old generation.
        if old is r:
            return self.result(r)
        self.import_legacy(mid)
        old = self.active(mid)
        # Preserve an existing legacy module on the first managed deployment.
        legacy = copy.deepcopy(self.backend.entries.get(mid, {}).get("release")) if old is None else None
        previous = old["deployment_id"] if old else None
        self.data["pending"] = {"module_id": mid, "target": r["deployment_id"], "old": previous, "legacy": legacy}
        r["state"] = "ACTIVATING"
        self.save()  # write-ahead intent BEFORE stopping the old generation
        try:
            self.backend.stop(mid)
            self.backend.start(mid, r)
            self.verify(r)
        except Exception as exc:
            return self.abort(str(exc))
        if old:
            old["state"] = "RETIRED"
        r["state"], r["error"] = "CANARY", None
        r["calls"], r["canary_started_at"] = 0, int(time.time() * 1000)
        self.data["active"][mid] = r["deployment_id"]
        self.data["previous"][mid] = previous
        self.data["enabled"][mid] = True
        self.data["pending"] = None
        self.save()  # active pointer + final journal state are ONE atomic replace
        return self.result(r)

    def abort(self, reason):
        pending = self.data["pending"]
        mid = pending["module_id"]
        r = self.data["releases"][pending["target"]]
        self.backend.stop(mid)
        old = self.data["releases"].get(pending["old"])
        self.data["active"][mid] = pending["old"]
        r["state"], r["error"] = "FAILED", reason
        restore = old or pending.get("legacy")
        if restore and not self.data["emergency"]:
            try:
                if old:
                    self.verify(old)
                self.backend.start(mid, restore)
                if old:
                    old["state"] = "ACTIVE"
            except Exception as exc:
                r["error"] += "; rollback_failed: " + str(exc)
                if old:
                    old["state"], old["error"] = "FAILED", str(exc)
        self.data["pending"] = None
        self.save()
        return self.result(r, r["error"], active=copy.deepcopy(old))

    def rollback(self, body):
        mid = identifier(body["module_id"])
        dep = self.data["previous"].get(mid)
        if not dep:
            return self.result(error="no_previous_release", active=copy.deepcopy(self.active(mid)))
        result = self.activate({"deployment_id": dep})
        if result["ok"]:
            # Repeated rollback must not toggle between the two generations.
            self.data["previous"][mid] = None
            self.active(mid)["state"] = "ACTIVE"
            result["state"] = "ACTIVE"
            self.save()
        result["active"] = copy.deepcopy(self.active(mid))
        return result

    def reconcile(self):
        self.backend.recover()
        if self.data["pending"]:
            self.abort("interrupted_activation")
        for r in self.data["releases"].values():
            if r["state"] == "VALIDATING":
                r["state"], r["error"] = "FAILED", "interrupted_validation"
        if not self.data["emergency"]:
            for mid, dep in self.data["active"].items():
                if dep and self.data["enabled"].get(mid, True) and mid not in self.backend.entries:
                    r = self.data["releases"][dep]
                    try:
                        self.verify(r)
                        self.backend.start(mid, r)
                        r["error"] = None
                    except Exception as exc:
                        r["state"], r["error"] = "FAILED", str(exc)
            for mid, spec in self.data["legacy_specs"].items():
                if not self.active(mid) and self.data["enabled"].get(mid, False) and mid not in self.backend.entries:
                    try:
                        self.backend.start(mid, spec)
                    except Exception as exc:
                        self.data.setdefault("legacy_errors", {})[mid] = str(exc)
                        if mid in self.backend.entries:
                            self.backend.entries[mid]["state"] = "CRASHED"
        self.save()

    def start(self, mid, body):
        # An abandoned HTTP request must not sit behind a long validation/start
        # and mutate the runtime after its Android caller has already failed.
        if not self.lock.acquire(blocking=False):
            return {"ok": False, "error": "emergency_stop" if self.emergency_requested.is_set()
                    else "control_plane_busy"}
        try:
            request_id = body.get("request_id")
            if request_id is not None:
                identifier(request_id)
            result = self._start_locked(mid, body)
            if request_id and result.get("ok"):
                self.start_receipts[mid] = (request_id, dict(result))
            return result
        finally:
            self.lock.release()

    def start_status(self, mid, request_id):
        identifier(mid)
        identifier(request_id)
        if not self.lock.acquire(blocking=False):
            return {"ok": False, "error": "control_plane_busy"}
        try:
            if self.data["emergency"] or self.emergency_requested.is_set():
                return {"ok": False, "error": "emergency_stop"}
            receipt = self.start_receipts.get(mid)
            entry = self.backend.entries.get(mid)
            if not receipt or receipt[0] != request_id:
                return {"ok": False, "error": "start_request_unknown"}
            result = receipt[1]
            proc = entry.get("proc") if entry else None
            if not self.data["enabled"].get(mid, False) or not entry or entry.get("state") != "ACTIVE" or \
                    entry.get("pgid") != result.get("pgid") or entry.get("endpoint") != result.get("endpoint") or \
                    proc is None or proc.poll() is not None:
                return {"ok": False, "error": "start_process_changed"}
            return dict(result, request_id=request_id, reconciled=True)
        finally:
            self.lock.release()

    def _start_locked(self, mid, body):
        with self.lock:
            identifier(mid)
            if self.data["emergency"]:
                return {"ok": False, "error": "emergency_stop"}
            r = self.active(mid)
            if r:
                self.verify(r)
                r = dict(r, config=body.get("config", r["config"]), transport=body.get("transport", r["transport"]))
            else:
                r = {"path": body.get("path") or "/root/zero/modules/" + mid + ".py",
                     "python": self.backend.python, "config": body.get("config", {}),
                     "transport": body.get("transport", "http")}
            reuse = getattr(self.backend, 'reuse', None)
            result = reuse(mid, r) if body.get('reuse_active') is True and reuse else None
            if result is None:
                result = self.backend.start(mid, r)
            if not self.active(mid):
                self.data["legacy_specs"][mid] = r
            self.data["enabled"][mid] = True
            self.save()
            return result

    def stop(self, mid):
        with self.lock:
            identifier(mid)
            self.data["enabled"][mid] = False
            self.save()
            return self.backend.stop(mid)

    def on_crash(self, mid, deployment_id):
        with self.lock:
            active = self.active(mid)
            if not active or active["deployment_id"] != deployment_id or self.data["emergency"]:
                return
            if active["state"] == "CANARY":
                self.observe(dict(module_id=mid, success=False))
                return
            now = time.time()
            attempts = [t for t in self.crashes.get(mid, []) if now - t < 300]
            attempts.append(now)
            self.crashes[mid] = attempts
            if len(attempts) <= 3 and self.data["enabled"].get(mid, True):
                try:
                    self.verify(active)
                    self.backend.start(mid, active)
                    return
                except Exception as exc:
                    active["error"] = str(exc)
            active["state"] = "FAILED"
            self.save()

    def observe(self, body):
        mid = identifier(body["module_id"])
        if self.data["emergency"]:
            raise ValueError("emergency_stop")
        r = self.active(mid)
        if not r or r["state"] != "CANARY":
            return self.result(r)
        if body.get("success") is False:
            result = self.rollback({"module_id": mid})
            if not result["ok"]:
                self.backend.stop(mid)
                self.data["active"].pop(mid, None)
            r["state"], r["error"] = "FAILED", "canary_call_failed"
            self.save()
            return self.result(r, "canary_call_failed", rolled_back=result["ok"])
        if body.get("success") is True:
            r["calls"] += 1
        if r["calls"] >= 5:
            r["state"] = "ACTIVE"
        elif int(time.time() * 1000) - r["canary_started_at"] >= 600000:
            # An unobserved release never becomes healthy just because time passed.
            result = self.rollback({"module_id": mid})
            if not result["ok"]:
                self.backend.stop(mid)
                self.data["active"].pop(mid, None)
            r["state"], r["error"] = "FAILED", "canary_window_expired"
        self.save()
        return self.result(r, r.get("error"))

    def emergency(self, body):
        enabled = body.get("enabled")
        if type(enabled) is not bool:
            raise ValueError("enabled_must_be_boolean")
        self.data["emergency"] = enabled
        if enabled:
            self.emergency_requested.set()
        else:
            self.emergency_requested.clear()
        atomic_json(self.emergency_file, enabled)
        self.save()
        failures = []
        if enabled:
            for mid in list(self.backend.entries):
                try:
                    self.backend.stop(mid)
                except Exception as exc:
                    failures.append(mid + ": " + str(exc))
        return {"ok": not failures, "enabled": enabled, "error": "; ".join(failures) or None}

    def dispatch(self, action, body):
        if action == "emergency" and isinstance(body, dict) and body.get("enabled") is True:
            # Stop live processes immediately, even while another request holds the validation lock.
            self.emergency_requested.set()
            atomic_json(self.emergency_file, True)
            for mid in list(self.backend.entries):
                self.backend.stop(mid)
        with self.lock:
            try:
                if not isinstance(body, dict):
                    raise ValueError("body_must_be_object")
                if action == "status":
                    mid = identifier(body["module_id"])
                    return {"ok": True, "active": copy.deepcopy(self.active(mid)),
                            "history": [copy.deepcopy(r) for r in self.data["releases"].values() if r["module_id"] == mid],
                            "emergency": self.data["emergency"], "error": None}
                if action not in ("prepare", "validate", "activate", "rollback", "emergency", "observe"):
                    raise ValueError("unknown_release_action")
                return getattr(self, action)(body)
            except Exception as exc:
                return self.result(error=str(exc))
