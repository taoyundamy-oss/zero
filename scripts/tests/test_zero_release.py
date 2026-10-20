import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk"))
from zero_release import ReleaseStore, ProcessRuntime


class Backend:
    prefix = []
    python = sys.executable

    def __init__(self):
        self.entries = {}
        self.fail_probe = False
        self.fail_start = None
        self.starts = 0
        self.builds = 0

    def build(self, release):
        self.builds += 1

    def probe(self, release):
        if self.fail_probe:
            raise RuntimeError("probe_failed")

    def start(self, key, release):
        if release.get("deployment_id") == self.fail_start:
            raise RuntimeError("start_failed")
        self.starts += 1
        self.entries[key] = {"release": release}
        return {"ok": True, "endpoint": "http://127.0.0.1:1234"}

    def stop(self, key):
        self.entries.pop(key, None)

    def recover(self):
        self.entries.clear()


class ReleaseTest(unittest.TestCase):
    def test_start_receipt_requires_exact_request_live_process_and_enabled_owner(self):
        class Proc:
            def poll(self):
                return None

        original = self.backend.start
        self.backend.fail_start = "never_matches_legacy"
        def start(mid, spec):
            result = dict(original(mid, spec), pgid=42, state="ACTIVE")
            self.backend.entries[mid].update(proc=Proc(), pgid=42, endpoint=result["endpoint"], state="ACTIVE")
            return result
        self.backend.start = start
        reply = self.store.start("receipt_owned", {"request_id": "request_one"})
        self.assertTrue(reply["ok"])
        self.assertTrue(self.store.start_status("receipt_owned", "request_one")["reconciled"])
        self.assertEqual(1, self.backend.starts)
        self.assertEqual("start_request_unknown", self.store.start_status("receipt_owned", "old_request")["error"])
        self.backend.entries["receipt_owned"]["pgid"] = 43
        self.assertEqual("start_process_changed", self.store.start_status("receipt_owned", "request_one")["error"])
        self.store.start("receipt_owned", {"request_id": "request_two"})
        self.assertEqual("start_request_unknown", self.store.start_status("receipt_owned", "request_one")["error"])
        self.store.stop("receipt_owned")
        self.assertEqual("start_process_changed", self.store.start_status("receipt_owned", "request_two")["error"])
        self.store.data["emergency"] = True
        self.assertEqual("emergency_stop", self.store.start_status("receipt_owned", "request_two")["error"])

    def test_start_reports_busy_instead_of_running_after_request_timeout(self):
        import threading
        entered, resume, returned = threading.Event(), threading.Event(), threading.Event()
        results = []
        original = self.backend.start
        def blocked(key, release):
            if key == "first":
                entered.set()
                self.assertTrue(resume.wait(3))
            return original(key, dict(release, deployment_id="owned-" + key))
        self.backend.start = blocked
        first = threading.Thread(target=lambda: self.store.start("first", {}))
        second = threading.Thread(target=lambda: (results.append(self.store.start("second", {})), returned.set()))
        first.start()
        try:
            self.assertTrue(entered.wait(1))
            second.start()
            responsive = returned.wait(.2)
        finally:
            resume.set()
            first.join(3)
            if second.ident:
                second.join(3)
        self.assertTrue(responsive, "Queued start outlives client timeout and mutates runtime later")
        self.assertEqual("control_plane_busy", results[0].get("error"))
        self.assertNotIn("second", self.backend.entries)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.backend = Backend()
        self.store = ReleaseStore(Path(self.tmp.name) / "releases", self.backend)

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def candidate(self, code="x = 1", **kw):
        return self.store.dispatch("prepare", dict(module_id="sample", code=code, **kw))

    def publish(self, code="x = 1"):
        result = self.candidate(code)
        dep = result["deployment_id"]
        self.assertTrue(self.store.dispatch("validate", dict(deployment_id=dep))["ok"])
        self.assertTrue(self.store.dispatch("activate", dict(deployment_id=dep))["ok"])
        return dep

    def test_invalid_candidate_preserves_active(self):
        old = self.publish()
        bad = self.candidate("def broken(")
        self.assertFalse(self.store.dispatch("validate", dict(deployment_id=bad["deployment_id"]))["ok"])
        self.assertEqual(old, self.store.active("sample")["deployment_id"])
        self.assertEqual(old, self.backend.entries["sample"]["release"]["deployment_id"])

    def test_manifest_metadata_changes_identity_and_survives_restart(self):
        declaration = dict(inputSchema={"type": "object"}, outputSchema={"type": "string"},
                           resourceLimits={"maxCalls": 5}, capabilities=["lookup"], termuxDeps=[],
                           sdkRange=">=1.0", entrypoint="serve", healthContract="endpoint_window_15s",
                           probe_timeout_s=15, dataSchemaVersion=1, transport="http", deps=[])
        first = self.candidate(manifest=declaration)["deployment_id"]
        changed = dict(declaration, outputSchema={"type": "integer"})
        second = self.candidate(manifest=changed)["deployment_id"]
        self.assertNotEqual(first, second)
        self.assertEqual(first, self.candidate(manifest=declaration)["deployment_id"])
        self.store.close()
        self.store = ReleaseStore(Path(self.tmp.name) / "releases", self.backend)
        self.assertEqual(declaration, self.store.data["releases"][first]["manifest"])
        self.assertEqual(changed, self.store.data["releases"][second]["manifest"])

    def test_invalid_declarations_preserve_active_before_staging(self):
        old = self.publish()
        before = set(self.store.data["releases"])
        files = set(self.store.root.iterdir())
        for declaration in ({"inputSchema": []}, {"resourceLimits": None}, {"capabilities": [1]},
                            {"dataSchemaVersion": True}, {"dataSchemaVersion": 0},
                            {"entrypoint": "../serve"}, {"sdkRange": ""}, {"probe_timeout_s": 999},
                            {"transport": "socket"}, {"healthContract": "endpoint_window_30s", "probe_timeout_s": 15}):
            result = self.candidate("x = 2", manifest=declaration)
            self.assertFalse(result["ok"], declaration)
            self.assertEqual(before, set(self.store.data["releases"]))
            self.assertEqual(files, set(self.store.root.iterdir()))
            self.assertEqual(old, self.store.active("sample")["deployment_id"])
            self.assertEqual(old, self.backend.entries["sample"]["release"]["deployment_id"])

    def test_probe_failure_preserves_active(self):
        old = self.publish()
        self.backend.fail_probe = True
        dep = self.candidate("x = 2")["deployment_id"]
        self.assertFalse(self.store.dispatch("validate", dict(deployment_id=dep))["ok"])
        self.assertEqual(old, self.store.active("sample")["deployment_id"])

    def test_activation_failure_restores_previous_process(self):
        old = self.publish()
        dep = self.candidate("x = 2")["deployment_id"]
        self.store.dispatch("validate", dict(deployment_id=dep))
        self.backend.fail_start = dep
        self.assertFalse(self.store.dispatch("activate", dict(deployment_id=dep))["ok"])
        self.assertEqual(old, self.backend.entries["sample"]["release"]["deployment_id"])

    def test_hash_tamper_rejected(self):
        dep = self.candidate()["deployment_id"]
        (self.store.local(dep) / "module.py").write_text("x=99")
        self.assertFalse(self.store.dispatch("validate", dict(deployment_id=dep))["ok"])
        self.assertEqual(0, self.backend.builds)

    def test_exact_pins_and_paths(self):
        for dep in ("requests", "requests>=2", "$(touch bad)", "--target=/etc"):
            self.assertFalse(self.candidate(manifest={"deps": [dep]})["ok"])
        self.assertFalse(self.store.dispatch("prepare", dict(module_id="../x", code="x=1"))["ok"])
        self.assertFalse(self.candidate("中" * (512 * 1024))["ok"])
        self.assertTrue(self.candidate(manifest={"deps": ["requests==2.32.3"]})["ok"])

    def test_idempotent_activate(self):
        dep = self.publish()
        starts = self.backend.starts
        self.assertEqual(dep, self.candidate()["deployment_id"])
        self.assertTrue(self.store.dispatch("activate", dict(deployment_id=dep))["ok"])
        self.assertEqual(starts, self.backend.starts)

    def test_canary_real_calls_and_rollback(self):
        old = self.publish()
        for _ in range(5):
            self.store.dispatch("observe", dict(module_id="sample", success=True))
        self.assertEqual("ACTIVE", self.store.active("sample")["state"])
        self.publish("x=2")
        self.assertFalse(self.store.dispatch("observe", dict(module_id="sample", success=False))["ok"])
        self.assertEqual(old, self.store.active("sample")["deployment_id"])
        self.assertEqual("ACTIVE", self.store.active("sample")["state"])

    def test_emergency_blocks_probe_and_start(self):
        dep = self.candidate()["deployment_id"]
        self.store.dispatch("emergency", dict(enabled=True))
        self.assertFalse(self.store.dispatch("validate", dict(deployment_id=dep))["ok"])
        self.assertFalse(self.store.start("sample", {})["ok"])
        self.assertEqual(0, self.backend.builds)

    def test_restart_recovers_pending_old_generation(self):
        old = self.publish()
        new = self.candidate("x=2")["deployment_id"]
        self.store.data["pending"] = dict(module_id="sample", target=new, old=old, legacy=None)
        self.store.save()
        self.store.close()
        self.store = ReleaseStore(Path(self.tmp.name) / "releases", self.backend)
        self.assertEqual(old, self.store.active("sample")["deployment_id"])
        self.assertEqual("FAILED", self.store.data["releases"][new]["state"])

    def test_legacy_source_preserved(self):
        legacy = Path(self.tmp.name) / "modules"
        legacy.mkdir()
        (legacy / "sample.py").write_text("old=1")
        self.publish("new=1")
        self.assertTrue(self.store.dispatch("rollback", dict(module_id="sample"))["ok"])
        active = self.store.active("sample")
        self.assertEqual("old=1", self.store.verify(active))
        self.assertEqual("old=1", (legacy / "sample.py").read_text())

    def test_actual_process_probe_and_stop(self):
        runtime = ProcessRuntime(Path(self.tmp.name) / "runtime", python=sys.executable)
        code = Path(self.tmp.name) / "server.py"
        code.write_text('from http.server import BaseHTTPRequestHandler,HTTPServer\n'
                        'class H(BaseHTTPRequestHandler):\n'
                        ' def do_POST(self):\n'
                        '  b=b\'{"result":{"tools":[{"name":"echo"}]}}\'\n'
                        '  self.send_response(200); self.end_headers(); self.wfile.write(b)\n'
                        's=HTTPServer(("127.0.0.1",0),H)\n'
                        'print("ZERO_MOD_LISTENING",s.server_port,flush=True)\n'
                        's.serve_forever()\n')
        release = dict(python=sys.executable, path=str(code), transport="http", config={})
        result = runtime.start("actual", release)
        self.assertTrue(result["ok"])
        proc = runtime.entries["actual"]["proc"]
        runtime.stop("actual")
        self.assertIsNotNone(proc.poll())

    def test_disabled_module_stays_disabled_after_supervisor_restart(self):
        self.publish()
        self.store.stop("sample")
        self.store.close()
        self.store = ReleaseStore(Path(self.tmp.name) / "releases", self.backend)
        self.assertNotIn("sample", self.backend.entries)

    def test_empty_canary_window_expires_without_false_promotion(self):
        self.publish()
        self.store.active("sample")["canary_started_at"] = 0
        result = self.store.dispatch("observe", dict(module_id="sample"))
        self.assertFalse(result["ok"])
        self.assertNotIn("sample", self.backend.entries)

    def test_emergency_interrupts_validation_before_candidate_execution(self):
        import threading
        entered, resume = threading.Event(), threading.Event()
        def build(release):
            entered.set()
            resume.wait(2)
        self.backend.build = build
        dep = self.candidate()["deployment_id"]
        validation = threading.Thread(target=lambda: self.store.dispatch("validate", dict(deployment_id=dep)))
        validation.start()
        self.assertTrue(entered.wait(2))
        emergency = threading.Thread(target=lambda: self.store.dispatch("emergency", dict(enabled=True)))
        emergency.start()
        self.assertTrue(self.store.emergency_requested.wait(2))
        resume.set()
        validation.join(2)
        emergency.join(2)
        self.assertEqual("FAILED", self.store.data["releases"][dep]["state"])
        self.assertEqual(0, self.backend.starts)


if __name__ == "__main__":
    unittest.main()
