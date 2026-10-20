"""Install hash-pinned runtime dependencies; no models, credentials or model API calls."""
from pathlib import Path
import argparse
import hashlib
import json
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def install(offline=False):
    manifest = json.loads((ROOT / "gradle/verification/runtime-downloads.json").read_text(encoding="utf-8"))
    cache = ROOT / ".vendor-cache"
    cache.mkdir(exist_ok=True)
    for asset in manifest["assets"]:
        targets = [(entry, ROOT / entry["target"]) for entry in asset["files"]]
        if all(path.is_file() and digest(path) == entry["sha256"] for entry, path in targets):
            continue
        archive = cache / asset["name"]
        if not archive.is_file():
            if offline:
                raise RuntimeError("Missing verified cache: " + asset["name"])
            partial = cache / (asset["name"] + ".part")
            request = urllib.request.Request(asset["url"], headers={"User-Agent": "Zero-runtime-bootstrap"})
            try:
                with urllib.request.urlopen(request, timeout=120) as response, partial.open("xb") as out:
                    size = 0
                    while chunk := response.read(1024 * 1024):
                        size += len(chunk)
                        if size > asset["size"]:
                            raise RuntimeError("Unexpected download size")
                        out.write(chunk)
                if digest(partial) != asset["sha256"]:
                    raise RuntimeError("Download checksum mismatch: " + asset["name"])
                partial.rename(archive)
            finally:
                partial.unlink(missing_ok=True)
        if digest(archive) != asset["sha256"]:
            raise RuntimeError("Cache checksum mismatch: " + asset["name"])
        for entry, target in targets:
            if not target.resolve().is_relative_to(ROOT):
                raise RuntimeError("Invalid target path")
            if target.exists():
                if digest(target) != entry["sha256"]:
                    raise RuntimeError("Existing dependency differs; review before replacing: " + entry["target"])
                continue
            if asset["kind"] == "tar":
                with tarfile.open(archive) as bundle:
                    member = bundle.getmember(entry["member"])
                    if not member.isfile():
                        raise RuntimeError("Expected regular archive member")
                    data = bundle.extractfile(member).read()
            elif asset["kind"] == "zip":
                with zipfile.ZipFile(archive) as bundle:
                    data = bundle.read(entry["member"])
            else:
                data = archive.read_bytes()
            if hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise RuntimeError("Member checksum mismatch: " + entry["target"])
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as out:
                out.write(data)
            print("Verified", entry["target"])
    print("Runtime ready. Optional voice model weights are not installed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    install(parser.parse_args().offline)
