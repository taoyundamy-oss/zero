import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import check_public_tree as scanner


class PublicTreeTest(unittest.TestCase):
    def check(self, root):
        with patch.object(scanner, "ROOT", root), contextlib.redirect_stdout(io.StringIO()):
            return scanner.check()

    def test_scans_index_even_if_working_copy_has_been_cleaned(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            path = root / "fixture.txt"
            path.write_bytes(b"ghp_" + b"0" * 30)
            subprocess.run(["git", "-C", str(root), "add", "fixture.txt"], check=True)
            path.write_text("clean working copy", encoding="utf-8")
            self.assertEqual(1, self.check(root))
            subprocess.run(["git", "-C", str(root), "add", "fixture.txt"], check=True)
            self.assertEqual(0, self.check(root))

    def test_private_directory_is_rejected_even_without_secret_pattern(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            (root / "backups").mkdir()
            (root / "backups/fixture.txt").write_text("synthetic", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            self.assertEqual(1, self.check(root))

    def stage(self, root, name, content):
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        subprocess.run(["git", "-C", str(root), "add", "."], check=True)

    def test_bot_identifiers_and_private_home_paths_cannot_be_committed(self):
        for content in [b"d" * 12 + b"@im.bot", b"C:/Users/" + b"synthetic-owner/private", b"ws-" + b"b" * 16]:
            with self.subTest(content=content), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.stage(root, "fixture.txt", content)
                self.assertEqual(1, self.check(root))

    def test_synthetic_paths_and_bot_examples_remain_usable(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.stage(root, "fixture.txt", b"C:/Users/USER/private example-bot@im.bot ws-example")
            self.assertEqual(0, self.check(root))

    def test_private_key_database_sidecar_and_runtime_binaries_are_rejected(self):
        for name in ["capture.db-wal", "capture.db-shm", "credential.pem", "store-password.dpapi", "model.gguf", "weights.safetensors", "services/libs/runtime.jar"]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.stage(root, name, b"synthetic")
                self.assertEqual(1, self.check(root))

    def test_deployment_cannot_print_credentials_or_use_a_fixed_search_key(self):
        cases = [("zero-cloud/deploy.sh", b'echo "$TOKEN"\n'),
                 ("zero-cloud/searxng/settings.yml", b'server:\n  secret_key: "synthetic-shared"\n'),
                 ("zero-cloud/docker-compose.yml", b'WEBUI_TOKEN=${WEBUI_TOKEN:-synthetic-default}\n')]
        for name, content in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.stage(root, name, content)
                self.assertEqual(1, self.check(root))

    def test_history_detects_a_removed_identifier_after_current_index_is_clean(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.stage(root, "fixture.txt", b"e" * 12 + b"@im.bot")
            commit = ["git", "-C", str(root), "-c", "user.name=Synthetic", "-c", "user.email=synthetic@example.invalid", "commit", "-qm", "fixture"]
            subprocess.run(commit, check=True)
            (root / "fixture.txt").write_text("example-bot@im.bot", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            subprocess.run(commit, check=True)
            self.assertEqual(0, self.check(root))
            with patch.object(scanner, "ROOT", root), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, scanner.check_history("HEAD"))

    def test_diagnostic_output_does_not_repeat_the_matched_secret(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            secret = b"ghp_" + b"q" * 35
            self.stage(root, "fixture.txt", secret)
            output = io.StringIO()
            with patch.object(scanner, "ROOT", root), contextlib.redirect_stdout(output):
                self.assertEqual(1, scanner.check())
            self.assertNotIn(secret.decode(), output.getvalue())
            self.assertIn("github-token: fixture.txt", output.getvalue())


if __name__ == "__main__":
    unittest.main()
