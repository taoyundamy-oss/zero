"""Read selected failure metadata and transcript shape; do not export message content or credentials."""
from contextlib import closing
import argparse
import json
import pathlib
import re
import sqlite3
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
ADB = r"adb"
OUT = ROOT / "artifacts/stability-20261001"
OUT.mkdir(parents=True, exist_ok=True)
parser = argparse.ArgumentParser()
parser.add_argument("--label", choices=("before", "after"), default="before")
label = parser.parse_args().label
with tempfile.TemporaryDirectory(prefix="stability-diagnostic-", dir=ROOT / ".test_tmp") as directory:
    target = pathlib.Path(directory) / "zero_v2.db"
    for suffix in ("", "-wal"):
        snapshot = subprocess.run([ADB, "exec-out", "run-as", "com.zero.v2", "cat",
                                   "databases/zero_v2.db" + suffix], capture_output=True, check=True, timeout=30)
        pathlib.Path(str(target) + suffix).write_bytes(snapshot.stdout)
    with closing(sqlite3.connect(target.as_uri() + "?mode=ro", uri=True)) as db:
        rows = db.execute("SELECT timestamp,sourceModule,summary FROM agent_trace_events WHERE "
                          "summary LIKE '%400%' OR summary LIKE 'vision_poke_%' OR summary LIKE 'image_%' "
                          "OR summary LIKE 'request_prefix:%' OR summary LIKE 'llm_call_done:%' "
                          "OR summary LIKE 'tool_execute_done:%' ORDER BY id DESC LIMIT 200").fetchall()
        rows += db.execute("SELECT timestamp,sourceModule,summary FROM agent_trace_events WHERE "
                           "nodeType = 'ERROR' OR summary LIKE 'http_400:%' "
                           "ORDER BY id DESC LIMIT 60").fetchall()
        events = []
        for timestamp, module, summary in reversed(rows):
            summary = re.sub(r"sk-[A-Za-z0-9_-]+", "[redacted]", summary)
            summary = re.sub(r"(?:content|file)://[^\s]+", "[attachment]", summary)
            if summary.startswith(("llm_call", "request_prefix:", "tool_execute_done:", "vision_", "image_", "http_", "LLM error:")) or "400" in summary:
                events.append({"timestamp": timestamp, "module": module, "summary": summary[:600]})
        shape = []
        for role, metadata, content, attachments in db.execute(
                "SELECT role,tool_calls,content,attachments FROM messages ORDER BY created_at DESC LIMIT 200"):
            try:
                info = json.loads(metadata) if metadata else {}
            except ValueError:
                info = {}
            shape.append({"role": role, "contentChars": len(content or ""),
                          "metadata": bool(metadata), "reasoningPresent": isinstance(info, dict) and "thinking" in info,
                          "toolCount": len(info.get("calls", [])) if isinstance(info, dict) else len(info),
                          "toolResultId": isinstance(info, dict) and bool(info.get("tool_call_id")),
                          "attachmentPresent": bool(attachments)})
            if isinstance(info, dict):
                for call in info.get("calls", []):
                    if call.get("name") == "invoke_tool":
                        try:
                            target_name = json.loads(call.get("arguments", "{}")).get("name", "")
                            if re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", target_name):
                                shape[-1].setdefault("invokeTargets", []).append(target_name)
                        except (ValueError, TypeError):
                            pass
    (OUT / f"device-{label}.json").write_text(json.dumps({"events": events, "shape": shape}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Events:", len(events), "Message shapes:", len(shape))
    for event in events:
        if "400" in event["summary"] or event["summary"].startswith(("vision_", "image_")):
            print(event["module"], event["summary"])
    print("Invoke targets:", sorted({n for row in shape for n in row.get("invokeTargets", [])}))
