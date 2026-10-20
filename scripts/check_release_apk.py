"""Inspect the actual APK manifest, backup rules and payload before publication."""
import argparse
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile
import re

from check_public_tree import PATTERNS

ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"}


def check_policy(manifest, rules):
    application = manifest.find("application")
    if application is None:
        raise ValueError("Missing application manifest")
    if application.get(ANDROID + "debuggable", "false") != "false":
        raise ValueError("Debuggable APK cannot be published")
    for flag in ["allowBackup", "fullBackupContent"]:
        if application.get(ANDROID + flag) != "false":
            raise ValueError("Release backup policy missing: " + flag)
    if not application.get(ANDROID + "dataExtractionRules"):
        raise ValueError("Missing data extraction rules")
    if manifest.find("instrumentation") is not None:
        raise ValueError("Instrumentation APK cannot be published")
    if any(".acceptance." in node.get(ANDROID + "name", "") for node in application):
        raise ValueError("Test entry point in release manifest")
    if rules.tag != "data-extraction-rules":
        raise ValueError("Unexpected extraction rules")
    for section in ["cloud-backup", "device-transfer"]:
        node = rules.find(section)
        if node is None or node.findall("include"):
            raise ValueError("Unsafe backup section: " + section)
        excluded = {child.get("domain") for child in node.findall("exclude") if child.get("path") == "."}
        if not DOMAINS.issubset(excluded):
            raise ValueError("Incomplete backup exclusions: " + section)


def inspect(apk, analyzer, aapt):
    def xml(*arguments):
        output = subprocess.check_output([analyzer, *arguments], timeout=120)
        return ET.fromstring(output.decode("utf-8-sig"))
    manifest = xml("manifest", "print", str(apk))
    with zipfile.ZipFile(apk) as bundle:
        paths = bundle.namelist()
        abis = {name.split("/")[1] for name in paths if name.startswith("lib/") and name.endswith(".so")}
        if abis != {"arm64-v8a", "x86_64"}:
            raise ValueError("Unexpected native ABI set")
        for abi in abis:
            for library in ["libonnxruntime4j_jni.so", "libsherpa-onnx-jni.so"]:
                if "lib/" + abi + "/" + library not in paths:
                    raise ValueError("Missing native runtime for " + abi)
        # Release AAPT can shorten filenames; resolve the named resource instead of guessing a ZIP path.
        rules_path = subprocess.check_output([analyzer, "resources", "value", "--config", "default", "--type", "xml", "--name", "release_data_extraction_rules", str(apk)], timeout=120).decode("utf-8").strip()
        if rules_path not in paths:
            raise ValueError("Missing compiled extraction policy")
        table = subprocess.check_output([aapt, "dump", "resources", str(apk)], timeout=120).decode("utf-8")
        resource = re.search(r"resource (0x[0-9a-f]+) \S+:xml/release_data_extraction_rules:", table)
        reference = manifest.find("application").get(ANDROID + "dataExtractionRules", "")
        if resource is None or reference not in {"@ref/" + resource[1], "@xml/release_data_extraction_rules"}:
            raise ValueError("Manifest does not reference the inspected extraction policy")
        rules = xml("resources", "xml", "--file", rules_path, str(apk))
        check_policy(manifest, rules)
        for name in paths:
            path = Path(name)
            if any(part in {"a8media", "backups", "artifacts", "__pycache__"} for part in path.parts):
                raise ValueError("Private/test payload path: " + name)
            if path.name.startswith(".env") or path.suffix.lower() in {".db", ".sqlite", ".sqlite3", ".jks", ".keystore", ".p12", ".pfx", ".dpapi", ".pyc", ".pyo", ".log", ".onnx", ".gguf", ".safetensors"} or path.name.endswith((".db-wal", ".db-shm")):
                raise ValueError("Unexpected private/model asset: " + name)
            content = bundle.read(name)
            for category, pattern in PATTERNS.items():
                if pattern.search(content):
                    raise ValueError(category + ": " + name)
        root = Path(__file__).resolve().parents[1]
        if subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"]).strip():
            raise ValueError("Commit the reviewed source before publication")
        head = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"]).decode().strip()
        metadata = bundle.read("META-INF/version-control-info.textproto").decode("utf-8")
        revision = re.search(r'revision: "([0-9a-f]{40})"', metadata)
        if revision is None or revision[1] != head:
            raise ValueError("APK source revision differs from checkout; rebuild after committing")
    print("Release APK privacy policy and payload passed:", len(paths), "entries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--aapt", default="aapt")
    args = parser.parse_args()
    inspect(args.apk.resolve(), args.apkanalyzer, args.aapt)
