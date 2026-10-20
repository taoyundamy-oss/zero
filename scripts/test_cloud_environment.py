import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "zero-cloud/scripts/prepare_env.py"
SPEC = importlib.util.spec_from_file_location("prepare_env", SOURCE)
environment = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(environment)


class CloudEnvironmentTest(unittest.TestCase):
    def test_generates_distinct_secrets_without_logging_them(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / ".env.example").write_text("ZERO_CLOUD_TOKEN=CHANGE_ME\nSEARXNG_SECRET=\n", encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                environment.prepare(root)
            values = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines())
            self.assertEqual(len(environment.KEYS), len(set(values.values())))
            for value in values.values():
                self.assertGreaterEqual(len(value), 40)
                self.assertNotIn(value, output.getvalue())
            self.assertEqual([".env", ".env.example"], sorted(p.name for p in root.iterdir()))

    def test_existing_credentials_and_other_configuration_survive_repeated_preparation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / ".env"
            path.write_text('export ZERO_CLOUD_TOKEN="synthetic-existing"\nOTHER=keep\n', encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                environment.prepare(root)
                first = path.read_bytes()
                environment.prepare(root)
            self.assertEqual(first, path.read_bytes())
            self.assertTrue(first.startswith(b'export ZERO_CLOUD_TOKEN="synthetic-existing"\nOTHER=keep\n'))
            self.assertIn(b"SEARXNG_SECRET=", first)

    def test_duplicate_keys_fail_without_changing_existing_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / ".env"
            original = b"ZERO_CLOUD_TOKEN=one\nZERO_CLOUD_TOKEN=two\n"
            path.write_bytes(original)
            with self.assertRaises(ValueError):
                environment.prepare(root)
            self.assertEqual(original, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
