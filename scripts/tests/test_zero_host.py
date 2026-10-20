import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk"))
import zero_host


class HostBundleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.stage, self.live, self.debian = [self.root / part for part in ("stage", "live", "debian")]
        for path in (self.stage, self.live, self.debian):
            path.mkdir()
        for name in zero_host.FILES:
            (self.stage / name).write_text("version='new'\n")
            (self.live / name).write_text("version='old'\n")
        (self.debian / "zero_tool.py").write_text("version='old-debian'\n")
        self.hashes()
        self.bundle = zero_host.Bundle(self.stage, self.live, self.debian)

    def tearDown(self):
        self.tmp.cleanup()

    def hashes(self):
        (self.stage / "bundle.json").write_text(json.dumps({name: hashlib.sha256((self.stage/name).read_bytes()).hexdigest()
                                                         for name in zero_host.FILES}))

    def test_invalid_stage_preserves_both_installed_sides(self):
        (self.stage / "zero_tool.py").write_text("broken(")
        self.hashes()
        with self.assertRaises(SyntaxError):
            self.bundle.prepare()
        self.assertEqual("version='old'\n", (self.live / "zero_tool.py").read_text())
        self.assertEqual("version='old-debian'\n", (self.debian / "zero_tool.py").read_text())

    def test_success_and_explicit_restore_preserve_original_bytes(self):
        self.bundle.prepare()
        self.bundle.install()
        self.assertEqual((self.stage / "zero_tool.py").read_bytes(), (self.debian / "zero_tool.py").read_bytes())
        self.bundle.accept()
        self.bundle.restore()
        self.assertEqual("version='old-debian'\n", (self.debian / "zero_tool.py").read_text())
        self.assertTrue(all((self.live / name).read_text() == "version='old'\n" for name in zero_host.FILES))

    def test_interrupted_install_is_restored_before_next_snapshot(self):
        self.bundle.prepare()
        self.bundle.install()
        self.bundle.prepare()
        self.assertEqual("version='old'\n", (self.live / "zero_tool.py").read_text())
        self.bundle.install()
        self.bundle.restore()
        self.assertEqual("version='old'\n", (self.live / "zero_tool.py").read_text())

    def test_partial_write_failure_restores_all_files(self):
        self.bundle.prepare()
        original = zero_host.write
        failed = False
        def fail_once(path, data):
            nonlocal failed
            if path == self.live / "zero_release.py" and not failed:
                failed = True
                raise OSError("simulated disk failure")
            original(path, data)
        with patch.object(zero_host, "write", side_effect=fail_once):
            with self.assertRaises(OSError):
                self.bundle.install()
        self.assertTrue(all((self.live / name).read_text() == "version='old'\n" for name in zero_host.FILES))

    def test_changed_stage_or_backup_is_rejected(self):
        self.bundle.prepare()
        (self.stage / "zero_tool.py").write_text("version='altered'\n")
        self.hashes()  # Replacing the staged index cannot change the prepared identity.
        with self.assertRaisesRegex(ValueError, "host_bundle_changed"):
            self.bundle.install()
        (self.bundle.backup / "0.bak").write_text("changed")
        with self.assertRaisesRegex(ValueError, "host_backup_changed"):
            self.bundle.restore()
