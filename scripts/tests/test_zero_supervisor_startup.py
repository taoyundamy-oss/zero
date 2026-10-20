"""Exercise production main ordering with a blocked real recovery boundary."""
import ast
import threading
import types
import unittest
from pathlib import Path


class StartupControlTest(unittest.TestCase):
    def test_control_listener_starts_before_blocked_module_recovery(self):
        source = Path(__file__).resolve().parents[2]/'services/src/main/assets/zero_sdk/zero_supervisor.py'
        nodes = [n for n in ast.parse(source.read_text(encoding='utf-8')).body if getattr(n,'name',None)=='main']
        entered, release, serving, stop = [threading.Event() for _ in range(4)]
        runtime = types.SimpleNamespace(lock=threading.RLock(), entries={})
        def store(*args):
            entered.set()
            release.wait(2)
            return types.SimpleNamespace(backend=runtime)
        class Server:
            def __init__(self, *args):
                pass
            def serve_forever(self):
                serving.set()
                stop.wait(3)
        env = dict(ProcessRuntime=lambda *args,**kwargs:runtime, deb_path=lambda p:p, DEB_CMD=[],
                   ReleaseStore=store, signal=types.SimpleNamespace(signal=lambda *a:None,SIGTERM=15,SIGINT=2),
                   shutdown=lambda *a:None, threading=threading, socket_server=lambda:None,
                   services_restore=lambda:None, ThreadingHTTPServer=Server, HTTP_PORT=0, Handler=object,
                   ROOTFS=True, os=types.SimpleNamespace(getpid=lambda:123), log=lambda *a:None,
                   HOST_STATE=dict(ready=False,phase='starting',error_type=None))
        exec(compile(ast.Module(body=nodes,type_ignores=[]),str(source),'exec'),env)
        thread = threading.Thread(target=env['main'],daemon=True)
        thread.start()
        try:
            self.assertTrue(entered.wait(1))
            self.assertTrue(serving.wait(.2), 'Module recovery blocks the control listener and causes upgrade EOF')
            self.assertFalse(env['HOST_STATE']['ready'])
        finally:
            release.set()
            serving.wait(1)
            stop.set()
            thread.join(2)


if __name__=='__main__':
    unittest.main()
