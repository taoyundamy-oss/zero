import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk"))
import zero_tool
from zero_release import ProcessRuntime, ReleaseStore, rpc


@unittest.skipIf(os.name == "nt", "Real venv and process recovery run on Debian")
class NativeReleaseLifecycleTest(unittest.TestCase):
    def test_publish_failure_integrity_rollback_emergency_and_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "releases"
            runtime = ProcessRuntime(root, python=sys.executable, sdk_path=str(Path(zero_tool.__file__).parent))
            store = ReleaseStore(root, runtime)
            manifest = {"sdkRange": ">=1.1,<2", "entrypoint": "serve", "probe_timeout_s": 5, "termuxDeps": ["python3"],
                        "inputSchema": {"type": "object"}, "outputSchema": {"type": "integer"}}

            def prepare(expression, declaration=None):
                code = "from zero_tool import tool,serve\n@tool\ndef echo(value:int):\n    return " + expression + "\nserve()\n"
                result = store.dispatch("prepare", {"module_id": "isolated_a6", "code": code,
                                                     "manifest": declaration or manifest})
                self.assertTrue(result["ok"], result)
                return result["deployment_id"]

            def call(value, mid="isolated_a6"):
                endpoint = runtime.entries[mid]["endpoint"]
                return rpc(endpoint, {"jsonrpc": "2.0", "id": "call", "method": "tools/call",
                                      "params": {"name": "echo", "arguments": {"value": value}}})["result"]

            def promote(deployment):
                result = store.dispatch("validate", {"deployment_id": deployment})
                self.assertTrue(result["ok"], result)
                self.assertTrue(store.dispatch("activate", {"deployment_id": deployment})["ok"])
                for _ in range(5):
                    self.assertNotIn("isError", call(1))
                    store.dispatch("observe", {"module_id": "isolated_a6", "success": True})
                self.assertEqual("ACTIVE", store.active("isolated_a6")["state"])

            def reopen():
                nonlocal runtime, store
                # A supervisor restart creates a new runtime; the old watcher no longer exists.
                runtime.on_crash = None
                store.close()
                runtime = ProcessRuntime(root, python=sys.executable, sdk_path=str(Path(zero_tool.__file__).parent))
                store = ReleaseStore(root, runtime)

            try:
                old = prepare("value")
                promote(old)
                original_pid = runtime.entries["isolated_a6"]["proc"].pid
                self.assertTrue(store.dispatch("activate", {"deployment_id": old})["ok"])
                self.assertEqual(original_pid, runtime.entries["isolated_a6"]["proc"].pid)
                with self.assertRaises(BlockingIOError):
                    ReleaseStore(root, ProcessRuntime(root, python=sys.executable))
                broken = prepare("(")
                self.assertFalse(store.dispatch("validate", {"deployment_id": broken})["ok"])
                self.assertEqual(original_pid, runtime.entries["isolated_a6"]["proc"].pid)
                invalid = prepare("value", dict(manifest, entrypoint="missing"))
                self.assertFalse(store.dispatch("validate", {"deployment_id": invalid})["ok"])
                self.assertEqual(original_pid, runtime.entries["isolated_a6"]["proc"].pid)

                candidate = prepare("value + 1")
                validated = store.dispatch("validate", {"deployment_id": candidate})
                self.assertTrue(validated["ok"], validated)
                lock = root / candidate / "requirements.lock"
                saved = lock.read_bytes()
                lock.write_bytes(saved + b"tamper\n")
                self.assertFalse(store.dispatch("activate", {"deployment_id": candidate})["ok"])
                self.assertEqual(original_pid, runtime.entries["isolated_a6"]["proc"].pid)
                lock.write_bytes(saved)
                library = next((root / candidate / "venv").rglob("site-packages")) / "tamper.py"
                library.write_text("altered=1\n")
                self.assertFalse(store.dispatch("activate", {"deployment_id": candidate})["ok"])
                self.assertEqual(old, store.active("isolated_a6")["deployment_id"])
                library.unlink()
                report = root / candidate / "venv" / "install-report.json"
                report.write_text(json.dumps({"install": [{"metadata": {"name": "fixture", "version": "1.0"},
                                                          "download_info": {"archive_info": {}}}]}))
                rejected = store.dispatch("activate", {"deployment_id": candidate})
                self.assertFalse(rejected["ok"])
                self.assertIn("dependency_archive_hash_missing", rejected["error"])
                self.assertEqual(original_pid, runtime.entries["isolated_a6"]["proc"].pid)
                report.unlink()

                self.assertTrue(store.dispatch("activate", {"deployment_id": candidate})["ok"])
                self.assertEqual("CANARY", store.active("isolated_a6")["state"])
                self.assertTrue(call("invalid")["isError"])
                store.dispatch("observe", {"module_id": "isolated_a6", "success": False})
                self.assertEqual(old, store.active("isolated_a6")["deployment_id"])
                self.assertEqual(1, json.loads(call(1)["content"][0]["text"]))
                store.stop("isolated_a6")
                reopen()
                self.assertNotIn("isolated_a6", runtime.entries)
                store.start("isolated_a6", {})
                self.assertNotIn("isError", call(1))
                store.dispatch("emergency", {"enabled": True})
                self.assertFalse(store.start("isolated_a6", {})["ok"])
                reopen()
                self.assertTrue(store.data["emergency"])
                self.assertFalse(store.start("isolated_a6", {})["ok"])
                store.dispatch("emergency", {"enabled": False})
                store.start("isolated_a6", {})
                reopen()
                self.assertNotIn("isError", call(1))
                self.assertEqual(old, store.dispatch("status", {"module_id": "isolated_a6"})["active"]["deployment_id"])
                legacy = Path(directory) / "legacy.py"
                legacy.write_text("from zero_tool import tool,serve\n@tool\ndef echo(value:int):\n    return value\nserve()\n")
                self.assertTrue(store.start("legacy_isolated", {"path": str(legacy)})["ok"])
                reopen()
                self.assertNotIn("isError", call(1, "legacy_isolated"))
                store.stop("legacy_isolated")
                reopen()
                self.assertNotIn("legacy_isolated", runtime.entries)
            finally:
                runtime.stop("legacy_isolated")
                runtime.stop("isolated_a6")
                store.close()
