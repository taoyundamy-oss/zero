import sys
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk"))
from zero_release import ProcessRuntime, sdk_matches, rpc
import zero_release
import test_zero_release as baseline


class SdkRuntimeTest(unittest.TestCase):
    @unittest.skipIf(os.name == "nt", "Native process-group verification runs on the Debian device")
    def test_real_process_imports_frozen_sdk_after_global_source_breaks(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory) / "global-sdk"
            sdk.mkdir()
            source = sdk / "zero_tool.py"
            source.write_bytes((Path(zero_release.__file__).parent / "zero_tool.py").read_bytes())
            module = Path(directory) / "candidate/module.py"
            module.parent.mkdir()
            module.write_text("from zero_tool import tool,serve\n@tool\ndef echo(value:str):\n    return value\nserve()\n")
            runtime = ProcessRuntime(Path(directory) / "journal", python=sys.executable, sdk_path=str(sdk))
            release = {"path": str(module), "python": sys.executable, "transport": "http",
                       "manifest": {"sdkRange": ">=1,<2", "probe_timeout_s": 5}}
            runtime.freeze_sdk(release, runtime.check_sdk(release))
            source.write_text('SDK_VERSION="2.0.0"\nraise RuntimeError("broken global SDK")\n')
            try:
                endpoint = runtime.start("isolated-sdk", release)["endpoint"]
                response = rpc(endpoint, {"jsonrpc": "2.0", "id": "call", "method": "tools/call",
                                          "params": {"name": "echo", "arguments": {"value": "stable"}}})
                self.assertEqual("stable", json.loads(response["result"]["content"][0]["text"]))
                info = rpc(endpoint, {"jsonrpc": "2.0", "id": "init", "method": "initialize"})
                self.assertEqual("1.1.0", info["result"]["serverInfo"]["sdkVersion"])
            finally:
                runtime.stop("isolated-sdk")

    def test_frozen_sdk_survives_global_update_but_rejects_local_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory) / "sdk"
            sdk.mkdir()
            source = sdk / "zero_tool.py"
            source.write_text('SDK_VERSION = "1.0.0"\n')
            runtime = ProcessRuntime(Path(directory) / "releases", python=sys.executable, sdk_path=str(sdk))
            release = {"path": str(Path(directory) / "candidate/module.py"), "manifest": {"sdkRange": ">=1,<2"}}
            identity = runtime.check_sdk(release)
            runtime.freeze_sdk(release, identity)
            source.write_text('SDK_VERSION = "2.0.0"\n')
            self.assertEqual(identity, runtime.check_sdk(release))
            (Path(release["sdk_path"]) / "zero_tool.py").write_text('SDK_VERSION = "1.0.0"\n# altered\n')
            with self.assertRaisesRegex(ValueError, "sdk_changed_since_validation"):
                runtime.check_sdk(release)

    def test_packaged_sdk_identity_matches_actual_source_bytes(self):
        sdk = Path(zero_release.__file__).parent
        with tempfile.TemporaryDirectory() as directory:
            runtime = ProcessRuntime(directory, python=sys.executable, sdk_path=str(sdk))
            identity = runtime.check_sdk({"manifest": {"sdkRange": ">=1.0,<2.0"}})
            self.assertEqual("1.1.0", identity["version"])
            self.assertEqual(hashlib.sha256((sdk / "zero_tool.py").read_bytes()).hexdigest(), identity["sha256"])

    def test_numeric_version_ranges_are_conjunctive_and_explicit(self):
        self.assertTrue(sdk_matches("1.0.0", ">=1.0,<2.0"))
        self.assertTrue(sdk_matches("1.10", ">1.2,!=2,<=1.10.0"))
        self.assertFalse(sdk_matches("2.0", ">=1.0,<2.0"))
        for specification in ("*", "^1.0", ">=banana", ">=1.0,", ""):
            with self.assertRaises(ValueError):
                sdk_matches("1.0", specification)

    def test_actual_sdk_source_identity_is_read_without_executing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory) / "sdk"
            sdk.mkdir()
            source = sdk / "zero_tool.py"
            source.write_text('SDK_VERSION = "1.0.0"\nraise RuntimeError("must not execute")\n')
            runtime = ProcessRuntime(Path(directory) / "releases", python=sys.executable, sdk_path=str(sdk))
            release = {"manifest": {"sdkRange": ">=1.0,<2.0"}}
            identity = runtime.check_sdk(release)
            self.assertEqual("1.0.0", identity["version"])
            self.assertEqual(64, len(identity["sha256"]))
            release["validated_sdk"] = identity
            source.write_text('SDK_VERSION = "1.0.0"\n# modified SDK implementation\n')
            with self.assertRaisesRegex(ValueError, "sdk_changed_since_validation"):
                runtime.check_sdk(release)
            with self.assertRaisesRegex(ValueError, "sdk_incompatible"):
                runtime.check_sdk({"manifest": {"sdkRange": ">=2.0"}})
            source.write_text("# old SDK without declared version\n")
            with self.assertRaisesRegex(ValueError, "sdk_version_unknown"):
                runtime.check_sdk(release)
            self.assertIsNone(runtime.check_sdk({"manifest": {}}))


class SdkPromotionTest(unittest.TestCase):
    def setUp(self):
        self.fixture = baseline.ReleaseTest("test_invalid_candidate_preserves_active")
        self.fixture.setUp()
        self.store, self.backend = self.fixture.store, self.fixture.backend

    def tearDown(self):
        self.fixture.tearDown()

    def test_sdk_failure_before_promotion_keeps_old_process_and_pointer(self):
        old = self.fixture.publish()
        candidate = self.fixture.candidate("x = 2")["deployment_id"]
        self.assertTrue(self.store.dispatch("validate", {"deployment_id": candidate})["ok"])
        starts = self.backend.starts

        def changed_sdk(release):
            raise ValueError("sdk_changed_since_validation")
        self.backend.check_sdk = changed_sdk
        result = self.store.dispatch("activate", {"deployment_id": candidate})
        self.assertFalse(result["ok"])
        self.assertEqual(old, self.store.active("sample")["deployment_id"])
        self.assertEqual(old, self.backend.entries["sample"]["release"]["deployment_id"])
        self.assertEqual(starts, self.backend.starts)
        self.assertIsNone(self.store.data["pending"])

    def test_repeated_activate_still_checks_validated_identity(self):
        old = self.fixture.publish()
        starts = self.backend.starts
        def changed_sdk(release):
            raise ValueError("sdk_changed_since_validation")
        self.backend.check_sdk = changed_sdk
        self.assertFalse(self.store.dispatch("activate", {"deployment_id": old})["ok"])
        self.assertEqual(starts, self.backend.starts)
        self.assertEqual(old, self.store.active("sample")["deployment_id"])

    def test_schema_change_fails_before_build_or_stopping_old(self):
        old = self.fixture.publish()
        candidate = self.store.dispatch("prepare", {"module_id": "sample", "code": "x=2",
                                                     "manifest": {"dataSchemaVersion": 2}})["deployment_id"]
        builds, starts = self.backend.builds, self.backend.starts
        result = self.store.dispatch("validate", {"deployment_id": candidate})
        self.assertFalse(result["ok"])
        self.assertEqual("data_schema_change_requires_migration", result["error"])
        self.assertEqual((builds, starts), (self.backend.builds, self.backend.starts))
        self.assertEqual(old, self.store.active("sample")["deployment_id"])


if __name__ == "__main__":
    unittest.main()
