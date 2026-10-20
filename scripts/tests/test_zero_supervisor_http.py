"""Execute the production HTTP handler without installing or starting a real host bundle."""
import ast
import json
import os
import threading
import time
import unittest
import tempfile
import hmac
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class ControlHttpTest(unittest.TestCase):
    def setUp(self):
        source = Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk/zero_supervisor.py"
        tree = ast.parse(source.read_text(encoding="utf-8"))
        names = {"Handler", "control_error", "control_route", "handle_frame_request", "control_unavailable", "control_authorized"}
        self.temp = tempfile.TemporaryDirectory()
        self.auth_file = Path(self.temp.name) / 'token'
        self.token = 'a' * 64
        self.auth_file.write_text(self.token)
        selected = [node for node in tree.body if getattr(node, "name", None) in names]
        self.env = dict(BaseHTTPRequestHandler=BaseHTTPRequestHandler, json=json, os=os, time=time,
                        hmac=hmac, AUTH_FILE=self.auth_file,
                        LOCK=threading.RLock(), MODULES={}, SERVICES={}, SESSIONS={}, ROOTFS=True,
                        BOOT_TS=time.time(), bundle_metadata=lambda: {}, log=lambda message: None,
                        HOST_STATE=dict(ready=True, phase='ready', error_type=None))
        exec(compile(ast.Module(body=selected, type_ignores=[]), str(source), "exec"), self.env)
        class QuietServer(ThreadingHTTPServer):
            def handle_error(self, *args):
                pass  # The client assertion, not a swallowed server traceback, decides this test.
        self.server = QuietServer(("127.0.0.1", 0), self.env["Handler"])
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def request(self, path, body=None, auth=True):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(f"http://127.0.0.1:{self.server.server_port}" + path, data=data)
        if auth:
            req.add_header('Authorization', 'Bearer ' + self.token)
        try:
            response = urllib.request.urlopen(req, timeout=2)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.code, json.load(response)

    def fault(self, *args):
        raise RuntimeError("private-precondition-detail")

    def test_unauthenticated_http_and_socket_are_rejected_before_dispatch(self):
        calls = []
        self.env['module_stop'] = lambda *args: calls.append(args) or {'ok': True}
        self.assertEqual(401, self.request('/module/owned/stop', {}, auth=False)[0])
        framed = self.env['handle_frame_request'](dict(method='POST', path='/module/owned/stop', body={}))
        self.assertEqual(401, framed['status'])
        self.assertFalse(calls)

    def test_health_failure_returns_typed_json_and_next_request_remains_usable(self):
        self.env["bundle_metadata"] = self.fault
        status, result = self.request("/health")
        self.assertEqual(500, status)
        self.assertEqual("control_plane_error", result["error"])
        self.assertEqual("RuntimeError", result["error_type"])
        self.assertNotIn("private-precondition-detail", json.dumps(result))
        self.env["bundle_metadata"] = lambda: {}
        self.assertTrue(self.request("/health")[1]["alive"])

    def test_mutating_failure_is_uncertain_instead_of_eof_or_false_committed_result(self):
        self.env["module_stop"] = self.fault
        status, result = self.request("/module/owned/stop", {})
        self.assertEqual(500, status)
        self.assertFalse(result["ok"])
        self.assertTrue(result["uncertain"])
        self.assertEqual("control_plane_error", result["error"])

    def test_http_and_socket_dispatch_share_failure_contract(self):
        self.env["module_stop"] = self.fault
        framed = self.env["handle_frame_request"](dict(token=self.token, method="POST", path="/module/owned/stop", body={}))
        self.assertEqual(500, framed["status"])
        self.assertEqual("control_plane_error", framed["body"]["error"])
        self.assertTrue(framed["body"]["uncertain"])

    def test_recovering_host_reports_health_and_rejects_mutation_before_dispatch(self):
        self.env['HOST_STATE'].update(ready=False,phase='recovering')
        calls = []
        self.env['module_stop'] = lambda *a: calls.append(a) or {'ok':True}
        self.assertFalse(self.request('/health')[1]['ready'])
        status, result = self.request('/module/owned/stop', {})
        self.assertEqual(503,status)
        self.assertEqual('supervisor_not_ready',result['error'])
        self.assertFalse(result['uncertain'])
        framed = self.env['handle_frame_request'](dict(token=self.token,method='POST',path='/module/owned/stop',body={}))
        self.assertEqual(503,framed['status'])
        self.assertFalse(calls)

    def test_failed_recovery_is_explicit_and_does_not_claim_ready(self):
        self.env['HOST_STATE'].update(ready=False,phase='failed',error_type='OSError')
        result = self.request('/health')[1]
        self.assertFalse(result['ready'])
        self.assertEqual('failed',result['phase'])
        self.assertEqual('OSError',result['error_type'])

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.temp.cleanup()

    def test_rotation_revokes_old_token_and_missing_file_fails_closed(self):
        self.auth_file.write_text('b' * 64)
        self.assertEqual(401, self.request('/health')[0])
        self.assertEqual(401, self.env['handle_frame_request']({'token': self.token})['status'])
        self.token = 'b' * 64
        self.assertEqual(200, self.request('/health')[0])
        self.auth_file.unlink()
        self.assertEqual(401, self.request('/health')[0])


if __name__ == "__main__":
    unittest.main()
