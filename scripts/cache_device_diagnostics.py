"""Read only cache-related trace summaries. Temporary DB copies are deleted on exit."""
import json
from contextlib import closing
import pathlib
import re
import sqlite3
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
ADB = r"adb"
destination = ROOT / "artifacts/cache-p0-20261001/device-cache-events.json"
with tempfile.TemporaryDirectory(prefix="cache-diagnostic-", dir=ROOT / ".test_tmp") as directory:
    target = pathlib.Path(directory) / "zero_v2.db"
    # Copy DB before WAL. Only SELECTs are issued against the local snapshot.
    for suffix in ("", "-wal"):
        result = subprocess.run([ADB, "exec-out", "run-as", "com.zero.v2", "cat",
                                 "databases/zero_v2.db" + suffix], capture_output=True, check=True)
        pathlib.Path(str(target) + suffix).write_bytes(result.stdout)
    with closing(sqlite3.connect(target.as_uri() + "?mode=ro", uri=True)) as connection:
        rows = connection.execute(
            "SELECT summary FROM agent_trace_events WHERE summary LIKE 'llm_call_done:%' "
            "OR summary LIKE 'prefix_version:%' OR summary LIKE 'ctx_tail_trim:%' "
            "OR summary LIKE 'ctx_gen_switch:%' ORDER BY rowid DESC LIMIT 150"
        ).fetchall()
    # Never output arbitrary stored message content, credentials or tool arguments.
    safe = [row[0] for row in reversed(rows) if re.fullmatch(r"[\w\s:=./%()\-]+", row[0])]
    destination.write_text(json.dumps(safe, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Saved cache-only events:", len(safe))
    for row in safe[-30:]:
        print(row)
