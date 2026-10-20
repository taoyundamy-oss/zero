"""Run isolated SDK metadata tests through a forwarded supervisor /exec endpoint."""
import base64
import hashlib
import io
import json
import shlex
import sys
import tarfile
import urllib.request
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
pattern = sys.argv[2] if len(sys.argv) > 2 else "test_*release*.py"
buffer = io.BytesIO()
with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
    for relative in ("services/src/main/assets/zero_sdk/zero_tool.py",
                     "services/src/main/assets/zero_sdk/zero_release.py",
                     "services/src/main/assets/zero_sdk/zero_host.py",
                     "scripts/tests/test_zero_host.py",
                     "scripts/tests/test_release_sdk.py", "scripts/tests/test_zero_release.py",
                     "scripts/tests/test_release_contract.py", "scripts/tests/test_release_lifecycle.py"):
        archive.add(repo / relative, arcname=relative)
payload = base64.b64encode(buffer.getvalue()).decode("ascii")
remote = f'''import base64,hashlib,io,json,subprocess,tarfile,tempfile
from pathlib import Path
with tempfile.TemporaryDirectory(prefix="zero-a6-sdk-") as root:
    with tarfile.open(fileobj=io.BytesIO(base64.b64decode("{payload}")),mode="r:gz") as archive:
        archive.extractall(root,filter="data")
    result=subprocess.run(["python3","-m","unittest","discover","-s","scripts/tests","-p",{pattern!r},"-v"],cwd=root,capture_output=True,text=True,timeout=600)
    print(json.dumps({{"exit":result.returncode,"stdout":result.stdout,"stderr":result.stderr,
       "candidateSdkSHA":hashlib.sha256((Path(root)/"services/src/main/assets/zero_sdk/zero_tool.py").read_bytes()).hexdigest(),
       "scope":"isolated_sdk_contract_dependency_environment_and_native_release_lifecycle","paidRequests":0}}))
'''
port = int(sys.argv[1])
body = json.dumps({"cmd": "python3 -c " + shlex.quote(remote), "timeout_s": 630}).encode()
request = urllib.request.Request(f"http://127.0.0.1:{port}/exec", body, {"Content-Type": "application/json"})
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with opener.open(request, timeout=650) as response:
    transport = json.load(response)
filename = {"test_*release*.py": "a6-sdk-device-report.json", "test_zero_host.py": "a6-host-device-report.json",
            "test_release_contract.py": "a6-compat-device-report.json"}.get(pattern, "a6-isolated-device-report.json")
output = repo / "artifacts/stability-20261007-a6" / filename
output.write_text(json.dumps(transport, ensure_ascii=False, indent=2), encoding="utf-8")
if transport.get("exit") != 0:
    raise RuntimeError("isolated_exec_failed")
report = json.loads(transport["stdout"])
expected = hashlib.sha256((repo / "services/src/main/assets/zero_sdk/zero_tool.py").read_bytes()).hexdigest()
if report["exit"] != 0 or report["candidateSdkSHA"] != expected:
    raise RuntimeError("isolated_sdk_test_failed")
print(report["stderr"])
