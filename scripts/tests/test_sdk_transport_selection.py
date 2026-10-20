import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk"))
import zero_tool


class SdkTransportSelectionTest(unittest.TestCase):
    def test_managed_socket_selection_never_creates_http_listener(self):
        with patch.dict(os.environ, {"ZERO_MOD_SOCKET": "isolated-managed"}), \
                patch.object(zero_tool, "serve_socket") as socket, \
                patch.object(zero_tool, "ThreadingHTTPServer") as http:
            zero_tool.serve()
            socket.assert_called_once_with("isolated-managed")
            http.assert_not_called()

    def test_default_http_keeps_explicit_host_and_port(self):
        with patch.dict(os.environ, {"ZERO_MOD_SOCKET": ""}), \
                patch.object(zero_tool, "prepare_contract"), \
                patch.object(zero_tool, "serve_socket") as socket, \
                patch.object(zero_tool, "ThreadingHTTPServer") as http:
            zero_tool.serve("127.0.0.1", 12345)
            http.assert_called_once_with(("127.0.0.1", 12345), zero_tool._Handler)
            http.return_value.serve_forever.assert_called_once()
            socket.assert_not_called()


if __name__ == "__main__":
    unittest.main()
