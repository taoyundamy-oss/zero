import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import bootstrap_runtime as runtime


class RuntimeBootstrapTest(unittest.TestCase):
    def fixture(self, parent, target="deps/runtime.bin"):
        root = Path(parent) / "workspace"
        (root / ".vendor-cache").mkdir(parents=True)
        data = b"synthetic runtime"
        manifest = {"assets": [{"name": "fixture", "kind": "raw", "url": "https://example.invalid/fixture",
                               "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                               "files": [{"target": target, "sha256": hashlib.sha256(data).hexdigest()}]}]}
        (root / "gradle/verification").mkdir(parents=True)
        (root / "gradle/verification/runtime-downloads.json").write_text(json.dumps(manifest), encoding="utf-8")
        (root / ".vendor-cache/fixture").write_bytes(data)
        return root, data

    def run_install(self, root):
        with patch.object(runtime, "ROOT", root), contextlib.redirect_stdout(io.StringIO()):
            runtime.install(offline=True)

    def test_verified_offline_install_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            root, data = self.fixture(temp)
            self.run_install(root)
            self.run_install(root)
            self.assertEqual(data, (root / "deps/runtime.bin").read_bytes())

    def test_corrupt_cache_cannot_create_dependency(self):
        with tempfile.TemporaryDirectory() as temp:
            root, _ = self.fixture(temp)
            (root / ".vendor-cache/fixture").write_bytes(b"tampered")
            with self.assertRaisesRegex(RuntimeError, "checksum"):
                self.run_install(root)
            self.assertFalse((root / "deps/runtime.bin").exists())

    def test_existing_different_dependency_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root, _ = self.fixture(temp)
            (root / "deps").mkdir()
            (root / "deps/runtime.bin").write_bytes(b"keep existing")
            with self.assertRaisesRegex(RuntimeError, "Existing dependency differs"):
                self.run_install(root)
            self.assertEqual(b"keep existing", (root / "deps/runtime.bin").read_bytes())

    def test_target_must_remain_in_owned_workspace(self):
        with tempfile.TemporaryDirectory() as temp:
            root, _ = self.fixture(temp, "../outside.bin")
            with self.assertRaisesRegex(RuntimeError, "Invalid target path"):
                self.run_install(root)
            self.assertFalse((Path(temp) / "outside.bin").exists())


if __name__ == "__main__":
    unittest.main()
