"""Prepare local cloud credentials without deploying services or printing secrets."""
import os
from pathlib import Path
import secrets
import tempfile
import re

ROOT = Path(__file__).resolve().parents[1]
KEYS = ("ZERO_CLOUD_TOKEN", "SEARXNG_SECRET", "NAPCAT_WEBUI_TOKEN")


def prepare(root=ROOT):
    path = root / ".env"
    if path.is_symlink():
        raise ValueError("Refuse a symlink environment file")
    existed = path.exists()
    source = path.read_text(encoding="utf-8") if existed else (root / ".env.example").read_text(encoding="utf-8")
    lines = source.splitlines(keepends=True)
    positions = {}
    for index, line in enumerate(lines):
        stripped = re.sub(r"^export\s+", "", line.strip())
        key, separator, value = stripped.partition("=")
        key = key.strip()
        if separator and key in KEYS:
            if key in positions:
                raise ValueError("Duplicate credential key: " + key)
            positions[key] = (index, value.strip().strip("\"'"))
    changed = not existed
    for key in KEYS:
        if key in positions and positions[key][1] not in {"", "CHANGE_ME"}:
            continue
        replacement = key + "=" + secrets.token_urlsafe(32) + "\n"
        if key in positions:
            lines[positions[key][0]] = replacement
        else:
            if lines and not lines[-1].endswith("\n"):
                lines[-1] += "\n"
            lines.append(replacement)
        changed = True
    if changed:
        descriptor, temporary = tempfile.mkstemp(prefix=".env.prepare-", dir=root)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
                stream.writelines(lines)
            os.chmod(temporary, 0o600)
            os.replace(temporary, path)
        finally:
            Path(temporary).unlink(missing_ok=True)
    os.chmod(path, 0o600)
    print("Private .env prepared. Existing credentials preserved; values are never printed.")


if __name__ == "__main__":
    prepare()
