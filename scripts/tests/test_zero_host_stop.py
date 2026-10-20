import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'services/src/main/assets/zero_sdk'))
from zero_host import stop_owner


@unittest.skipUnless(sys.platform.startswith('linux'),'Requires kernel process identities')
class HostStopTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name)/'host.py'
        self.ready = Path(self.tmp.name)/'ready'
        self.path.write_text("import signal,time\nfrom pathlib import Path\n"
                            "def stop(*a): time.sleep(.4);raise SystemExit(0)\n"
                            "signal.signal(signal.SIGTERM,stop)\n"
                            f"Path({str(self.ready)!r}).touch()\n"
                            "while True: time.sleep(.1)\n")
        self.proc = subprocess.Popen([sys.executable,str(self.path)])
        deadline = time.monotonic()+3
        while not self.ready.exists() and time.monotonic()<deadline:
            time.sleep(.01)
        self.assertTrue(self.ready.exists())

    def test_waits_for_actual_exit_despite_missing_http(self):
        start=time.monotonic()
        self.assertTrue(stop_owner(self.proc.pid,str(self.path),2))
        self.assertGreaterEqual(time.monotonic()-start,.35)
        self.assertEqual(0,self.proc.wait(2))

    def test_timeout_does_not_claim_exit_or_force_kill_cleanup(self):
        self.assertFalse(stop_owner(self.proc.pid,str(self.path),.1))
        self.assertIsNone(self.proc.poll())
        self.assertEqual(0,self.proc.wait(2))

    def test_wrong_owner_path_is_preserved(self):
        self.assertFalse(stop_owner(self.proc.pid,str(self.path)+'-other',.1))
        self.assertIsNone(self.proc.poll())

    def tearDown(self):
        if self.proc.poll() is None:
            self.proc.kill()
        self.proc.wait(2)
        self.tmp.cleanup()


if __name__=='__main__':
    unittest.main()
