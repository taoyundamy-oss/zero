"""Native process ownership regressions; Windows cannot emulate Linux /proc or sessions."""
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk"))
from zero_release import ProcessRuntime, ReleaseStore


@unittest.skipUnless(sys.platform.startswith("linux"), "Requires native Linux process identities")
class ProcessOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name)
        self.runtime = ProcessRuntime(self.path / "runtime", python=sys.executable)
        self.owned = []
        self.outside = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(60)"], start_new_session=True)
        self.owned.append((self.outside.pid, ProcessRuntime.identity(self.outside.pid)))

    def alive(self, pid, token):
        if ProcessRuntime.identity(pid) != token:
            return False
        return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0] != "Z"

    def fixture(self):
        child_file = self.path / "child.json"
        code = self.path / "fixture.py"
        code.write_text(
            "import subprocess,sys,json\nfrom http.server import BaseHTTPRequestHandler,HTTPServer\n"
            "from pathlib import Path\n"
            "child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],start_new_session=True)\n"
            f"Path({str(child_file)!r}).write_text(json.dumps(child.pid))\n"
            "class H(BaseHTTPRequestHandler):\n"
            " def do_POST(self):\n"
            "  self.rfile.read(int(self.headers.get('Content-Length','0')))\n"
            "  b=b'{\"result\":{\"tools\":[]}}'\n"
            "  self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)\n"
            " def log_message(self,*args): pass\n"
            "s=HTTPServer(('127.0.0.1',0),H)\n"
            "print('ZERO_MOD_LISTENING',s.server_port,flush=True)\ns.serve_forever()\n")
        self.runtime.start("owned", dict(python=sys.executable, path=str(code), transport="http", config={}))
        pid = json.loads(child_file.read_text())
        token = ProcessRuntime.identity(pid)
        self.owned.append((pid, token))
        self.assertTrue(self.alive(pid, token))
        self.assertNotEqual(os.getpgid(pid), self.runtime.entries["owned"]["pgid"])
        return pid, token

    def test_stop_cleans_escaped_descendant_and_preserves_unrelated_process(self):
        pid, token = self.fixture()
        self.runtime.stop("owned")
        self.assertFalse(self.alive(pid, token), "A different-session child survived successful module stop")
        self.assertIsNone(self.outside.poll())

    def test_recover_cleans_recorded_children_after_root_disappears(self):
        pid, token = self.fixture()
        time.sleep(1.2)
        entry = self.runtime.entries.pop("owned")  # Simulate the former supervisor being unavailable.
        entry["proc"].kill()
        entry["proc"].wait(timeout=5)
        recovered = ProcessRuntime(self.path / "runtime", python=sys.executable)
        recovered.recover()
        self.assertFalse(self.alive(pid, token), "Dead root cannot justify abandoning recorded children")
        self.assertIsNone(self.outside.poll())

    def test_recover_rejects_changed_process_identity(self):
        token = self.owned[0][1]
        record = dict(pid=self.outside.pid, identity=token + "-stale", children=[
            dict(pid=self.outside.pid, identity=token + "-stale", uid=int(next(x for x in Path("/proc/self/status").read_text().splitlines() if x.startswith("Uid:")).split()[1]), name="python", depth=1)])
        self.runtime.pids["stale"] = record
        self.runtime.recover()
        self.assertIsNone(self.outside.poll())

    def test_crash_cleanup_precedes_restart_callback(self):
        import threading
        pid, token = self.fixture()
        time.sleep(1.2)
        observed, done = [], threading.Event()
        def callback(*args):
            observed.append(self.alive(pid, token))
            done.set()
        self.runtime.on_crash = callback
        self.runtime.entries["owned"]["proc"].kill()
        self.assertTrue(done.wait(5))
        self.assertEqual([False], observed, "A restart callback ran while old children remained alive")

    def test_automatic_reattach_preserves_healthy_process_and_receipt(self):
        store = ReleaseStore(self.path/'runtime',self.runtime)
        try:
            self.fixture()
            entry=self.runtime.entries['owned']
            old=entry['pgid']
            body=dict(entry['release'],reuse_active=True,request_id='reattach_owned')
            result=store.start('owned',body)
            self.assertEqual(old,result['pgid'],'Automatic remount restarted a healthy process')
            self.assertTrue(result['reused'])
            self.assertTrue(store.start_status('owned','reattach_owned')['reconciled'])
        finally:
            store.close()

    def test_changed_source_or_config_and_explicit_restart_do_not_reuse(self):
        store=ReleaseStore(self.path/'runtime',self.runtime)
        try:
            self.fixture()
            entry=self.runtime.entries['owned']
            old=entry['pgid']
            body=dict(entry['release'],reuse_active=True)
            source=Path(body['path'])
            source.write_text(source.read_text()+'\n# new entry generation\n')
            result=store.start('owned',body)
            self.assertNotEqual(old,result['pgid'])
            old=result['pgid'];body['config']={'new_setting':True}
            result=store.start('owned',body)
            self.assertNotEqual(old,result['pgid'])
            old=result['pgid'];body.pop('reuse_active')
            self.assertNotEqual(old,store.start('owned',body)['pgid'])
            self.assertIsNone(self.outside.poll())
        finally:
            store.close()

    def tearDown(self):
        for key in list(self.runtime.entries):
            self.runtime.stop(key)
        for pid, token in self.owned:
            if self.alive(pid, token):
                os.kill(pid, signal.SIGKILL)
        self.outside.wait(timeout=5)
        self.tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
