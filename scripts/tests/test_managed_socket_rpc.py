import sys
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk"))
from zero_release import managed_socket_rpc


class ManagedSocketRelayTest(unittest.TestCase):
    def setUp(self):
        self.entry = {"state": "ACTIVE", "endpoint": "unix://managed"}
        backend = SimpleNamespace(lock=threading.RLock(), entries={"fixture": self.entry})
        self.store = SimpleNamespace(lock=threading.RLock(), backend=backend, data={"emergency": False})
        self.body = {"endpoint": "unix://managed", "timeout_ms": 15000,
                     "request": {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}}

    def test_managed_endpoint_and_timeout_reach_rpc(self):
        with patch("zero_release.rpc", return_value={"jsonrpc": "2.0", "id": 1, "result": {}}) as rpc:
            self.assertEqual(managed_socket_rpc(self.store, "fixture", self.body)["id"], 1)
            rpc.assert_called_once_with("unix://managed", self.body["request"], 15.0)
        with patch("zero_release.rpc", return_value={"id": 1, "result": {}}) as rpc:
            managed_socket_rpc(self.store, "fixture", dict(self.body, timeout_ms=3600000))
            rpc.assert_called_once_with("unix://managed", self.body["request"], 3600.0)

    def test_unknown_stale_stopped_and_emergency_never_dispatch(self):
        cases = [("unknown", self.body), ("fixture", dict(self.body, endpoint="unix://other"))]
        with patch("zero_release.rpc") as rpc:
            for mid, body in cases:
                with self.assertRaises(ValueError):
                    managed_socket_rpc(self.store, mid, body)
            self.entry["state"] = "STOPPED"
            with self.assertRaises(ValueError):
                managed_socket_rpc(self.store, "fixture", self.body)
            self.entry["state"] = "ACTIVE"
            self.store.data["emergency"] = True
            with self.assertRaises(ValueError):
                managed_socket_rpc(self.store, "fixture", self.body)
            rpc.assert_not_called()

    def test_generation_replacement_during_rpc_rejects_late_result(self):
        def replace(*args):
            self.store.backend.entries["fixture"] = dict(self.entry)
            return {"id": 1, "result": {}}
        with patch("zero_release.rpc", side_effect=replace):
            with self.assertRaisesRegex(ValueError, "module_generation_changed"):
                managed_socket_rpc(self.store, "fixture", self.body)

    def test_invalid_protocol_method_ids_deadlines_and_size_never_dispatch(self):
        invalid = [None, {}, dict(self.body, timeout_ms=True), dict(self.body, timeout_ms=0),
                   dict(self.body, timeout_ms=3600001), dict(self.body, request=[])]
        for key, value in (("method", "exec"), ("id", True), ("id", -1), ("jsonrpc", "1.0"),
                           ("params", {"text": "x" * 1048576})):
            invalid.append(dict(self.body, request=dict(self.body["request"], **{key: value})))
        with patch("zero_release.rpc") as rpc:
            for body in invalid:
                with self.assertRaises(ValueError):
                    managed_socket_rpc(self.store, "fixture", body)
            rpc.assert_not_called()


if __name__ == "__main__":
    unittest.main()
