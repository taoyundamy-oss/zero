"""Trusted host bundle updates. Module releases never use this installer."""
import ast
import hashlib
import json
import os
import sys
from pathlib import Path

FILES = ("zero_tool.py", "zero_release.py", "zero_supervisor.py", "zero_host.py")


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".host-tmp")
    with temporary.open("wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


class Bundle:
    def __init__(self, staged, live, debian):
        self.staged, self.live, self.debian = map(Path, (staged, live, debian))
        self.backup = self.live.parent / ".host-backup"
        self.journal = self.backup / "transaction.json"

    def record(self, state, rows, expected=None):
        if expected is None and self.journal.exists():
            expected = json.loads(self.journal.read_text()).get("expected")
        write(self.journal, json.dumps({"state": state, "files": rows, "expected": expected}, sort_keys=True).encode())

    def restore(self):
        record = json.loads(self.journal.read_text())
        # Verify every backup before modifying either installed side.
        for row in record["files"]:
            if row["sha256"] is not None:
                data = (self.backup / row["backup"]).read_bytes()
                if hashlib.sha256(data).hexdigest() != row["sha256"]:
                    raise ValueError("host_backup_changed")
        for row in record["files"]:
            path = Path(row["path"])
            if row["sha256"] is None:
                path.unlink(missing_ok=True)
            else:
                write(path, (self.backup / row["backup"]).read_bytes())
        self.record("RESTORED", record["files"])

    def prepare(self):
        expected = json.loads((self.staged / "bundle.json").read_text())
        if set(expected) != set(FILES):
            raise ValueError("host_bundle_incomplete")
        for name in FILES:
            data = (self.staged / name).read_bytes()
            if hashlib.sha256(data).hexdigest() != expected[name]:
                raise ValueError("host_bundle_changed")
            ast.parse(data, filename=name)
        if self.journal.exists() and json.loads(self.journal.read_text())["state"] in ("PREPARED", "INSTALLING"):
            self.restore()
        rows = []
        targets = [self.live / name for name in FILES] + [self.debian / "zero_tool.py"]
        for index, path in enumerate(targets):
            if path.is_symlink():
                raise ValueError("host_asset_symlink")
            data = path.read_bytes() if path.exists() else None
            filename = str(index) + ".bak"
            if data is not None:
                write(self.backup / filename, data)
            rows.append({"path": str(path), "backup": filename,
                         "sha256": hashlib.sha256(data).hexdigest() if data is not None else None})
        self.record("PREPARED", rows, expected)

    def install(self):
        record = json.loads(self.journal.read_text())
        if record["state"] != "PREPARED":
            raise ValueError("host_not_prepared")
        expected = record["expected"]
        contents = {name: (self.staged / name).read_bytes() for name in FILES}
        if any(hashlib.sha256(data).hexdigest() != expected[name] for name, data in contents.items()):
            raise ValueError("host_bundle_changed")
        self.record("INSTALLING", record["files"])
        try:
            for name, data in contents.items():
                write(self.live / name, data)
            write(self.debian / "zero_tool.py", contents["zero_tool.py"])
        except Exception:
            self.restore()
            raise

    def accept(self):
        record = json.loads(self.journal.read_text())
        if record["state"] != "INSTALLING":
            raise ValueError("host_not_installed")
        if any(hashlib.sha256((self.live/name).read_bytes()).hexdigest() != record["expected"][name] for name in FILES):
            raise ValueError("host_installed_changed")
        if hashlib.sha256((self.debian/"zero_tool.py").read_bytes()).hexdigest() != record["expected"]["zero_tool.py"]:
            raise ValueError("host_debian_sdk_changed")
        self.record("ACCEPTED", record["files"])


def stop_owner(pid, expected_path, timeout_s=30):
    """Wait for the original kernel generation to exit, never infer death from HTTP."""
    import signal
    import time
    from zero_release import process_record
    if pid <= 1 or pid == os.getpid():
        return False
    record = process_record(pid)
    if record is None:
        return not (Path('/proc')/str(pid)).exists()
    caller = process_record(os.getpid())
    try:
        args = (Path('/proc')/str(pid)/'cmdline').read_bytes().split(b'\0')
    except OSError:
        return False
    if not caller or record['uid'] != caller['uid'] or len(args) < 2 or args[1] != os.fsencode(expected_path):
        return False
    # Validate again immediately before signalling the original identity.
    current = process_record(pid)
    if current is None:
        return not (Path('/proc')/str(pid)).exists()
    if current['identity'] != record['identity']:
        return True
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        current = process_record(pid)
        if current is None:
            if not (Path('/proc')/str(pid)).exists():
                return True
        elif current['identity'] != record['identity'] or current['state'] == 'Z':
            return True
        time.sleep(.1)
    return False


if __name__ == "__main__":
    if sys.argv[1] == 'stop-owner':
        sys.exit(0 if stop_owner(int(sys.argv[2]), sys.argv[3]) else 1)
    bundle = Bundle(*sys.argv[2:5])
    bundle.backup.mkdir(parents=True, exist_ok=True)
    with (bundle.backup / "owner.lock").open("a+b") as lock:
        import fcntl
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        getattr(bundle, sys.argv[1])()
