"""Check Git-tracked publication files without printing matched secrets."""
from pathlib import Path
import re
import subprocess
import sys
import io
import argparse

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    "private-key": re.compile(rb"-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----"),
    "github-token": re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{40,})"),
    "api-key": re.compile(rb"\bsk-[A-Za-z0-9_-]{20,}"),
    "google-key": re.compile(rb"AIza[0-9A-Za-z_-]{35}"),
    "aws-key": re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    "deployment-bot-id": re.compile(rb"\b[0-9a-f]{12,}@im\.bot\b", re.I),
    "cloud-workspace-id": re.compile(rb"\bws-[a-z0-9]{12,}\b", re.I),
    "personal-windows-home": re.compile(
        rb"[A-Z]:(?:\\+|/+)Users(?:\\+|/+)(?!(?:USER|USERNAME|PUBLIC|DEFAULT)(?:[\\/]|\b))[^\\/\s\"'<>]+", re.I
    ),
}


def scan_entries(entries, label):
    unique = list(dict.fromkeys(oid for _, oid in entries))
    batch = subprocess.run(["git", "-C", str(ROOT), "cat-file", "--batch"],
                           input=b"\n".join(unique) + b"\n", capture_output=True, check=True, timeout=60)
    data = io.BytesIO(batch.stdout)
    blobs = {}
    for oid in unique:
        returned, kind, size = data.readline().split()
        if returned != oid or kind != b"blob":
            raise RuntimeError("Unexpected staged object")
        blobs[oid] = data.read(int(size))
        if data.read(1) != b"\n":
            raise RuntimeError("Malformed object stream")
    errors = []
    for name, oid in entries:
        path = Path(name)
        if any(part in {"artifacts", "backups", ".vendor-cache", "build", ".gradle", ".android", ".kotlin"} for part in path.parts):
            errors.append((name, "private-or-generated-directory"))
        if path.name.startswith(".env") and path.name != ".env.example":
            errors.append((name, "environment-file"))
        if path.suffix.lower() in {".db", ".sqlite", ".sqlite3", ".keystore", ".jks", ".p12", ".pfx", ".pem", ".key", ".apk", ".aab", ".onnx", ".so", ".gguf", ".safetensors", ".pt"} or path.name.endswith((".db-wal", ".db-shm")):
            errors.append((name, "private-or-unreviewed-binary"))
        if path.suffix.lower() == ".jar" and name != "gradle/wrapper/gradle-wrapper.jar":
            errors.append((name, "unreviewed-runtime-jar"))
        content = blobs[oid]
        for category, pattern in PATTERNS.items():
            if pattern.search(content):
                errors.append((name, category))
        if name == "zero-cloud/searxng/settings.yml" and re.search(rb"(?m)^\s*secret_key:\s*[\"'][^\"'\r\n]+[\"']", content):
            errors.append((name, "fixed-search-secret"))
        if name == "zero-cloud/deploy.sh" and re.search(rb"(?m)^\s*(?:echo|printf)\b[^\n]*(?:\$(?:\{)?TOKEN\b|grep[^\n]*TOKEN)", content):
            errors.append((name, "deployment-secret-logging"))
        if name == "zero-cloud/docker-compose.yml" and re.search(rb"\$\{[A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD)[A-Z0-9_]*:-[^}\r\n]+\}", content):
            errors.append((name, "fixed-compose-credential"))
    for name, category in sorted(set(errors)):
        print(category + ": " + name)
    print(label + " publication entries checked:", len(entries))
    return 1 if errors else 0


def check():
    records = subprocess.check_output(["git", "-C", str(ROOT), "ls-files", "--stage", "-z"]).split(b"\0")
    entries = []
    for record in filter(None, records):
        meta, path = record.split(b"\t", 1)
        _, oid, stage = meta.split()
        if stage != b"0":
            raise RuntimeError("Resolve merge conflicts before publication")
        entries.append((path.decode("utf-8"), oid))
    return scan_entries(entries, "index")


def check_history(ref):
    shallow = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "--is-shallow-repository"]).strip()
    if shallow == b"true":
        raise RuntimeError("Full history required; fetch with depth 0 before auditing")
    commits = subprocess.check_output(["git", "-C", str(ROOT), "rev-list", "--end-of-options", ref]).splitlines()
    entries = set()
    for commit in commits:
        records = subprocess.check_output(["git", "-C", str(ROOT), "ls-tree", "-r", "-z", commit.decode()]).split(b"\0")
        for record in filter(None, records):
            meta, path = record.split(b"\t", 1)
            _, kind, oid = meta.split()
            if kind == b"blob":
                entries.add((path.decode("utf-8"), oid))
    print("Reachable commits checked:", len(commits))
    return scan_entries(sorted(entries), "history")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", metavar="REF", help="Audit all trees reachable from REF instead of the index")
    args = parser.parse_args()
    sys.exit(check_history(args.history) if args.history else check())
