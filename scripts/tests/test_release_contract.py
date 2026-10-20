import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "services/src/main/assets/zero_sdk"))
import zero_tool
from zero_release import ProcessRuntime, rpc


class ContractSchemaTest(unittest.TestCase):
    def test_input_and_nested_bounds_and_output_checks(self):
        schema = {"type": "object", "required": ["items"], "additionalProperties": False,
                  "properties": {"items": {"type": "array", "maxItems": 2,
                                           "items": {"type": "integer", "minimum": 1}}}}
        zero_tool.validate_schema(schema)
        zero_tool.check_value({"items": [1, 2]}, schema)
        for value in ({}, {"items": [True]}, {"items": [0]}, {"items": [1, 2, 3]}, {"items": [], "extra": 1}):
            with self.assertRaises(ValueError):
                zero_tool.check_value(value, schema)
        for schema in ({"$ref": "file:///secret"}, {"type": "bad"}, {"minLength": -1}, {"required": "x"}):
            with self.assertRaises(ValueError):
                zero_tool.validate_schema(schema)


@unittest.skipIf(os.name == "nt", "Native managed SDK tests run in Debian")
class NativeContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.runtime = ProcessRuntime(self.root / "journal", python=sys.executable,
                                      sdk_path=str(Path(zero_tool.__file__).parent))

    def tearDown(self):
        self.runtime.stop("contract")
        self.temp.cleanup()

    def launch(self, declaration, expression="value", namespace=False):
        source = self.root / "candidate/module.py"
        source.parent.mkdir(exist_ok=True)
        imports = "import time\nimport zero_tool\n@zero_tool.tool\n" if namespace else "import time\nfrom zero_tool import tool,serve\n@tool\n"
        ending = "zero_tool.serve()\n" if namespace else "serve()\n"
        source.write_text(imports + "def echo(value):\n    return " + expression + "\n" + ending)
        manifest = {"sdkRange": ">=1.1,<2", "entrypoint": "serve", "probe_timeout_s": 5, **declaration}
        release = {"path": str(source), "python": sys.executable, "transport": manifest.get("transport", "http"), "manifest": manifest}
        self.runtime.freeze_sdk(release, self.runtime.check_sdk(release))
        return self.runtime.start("contract", release)["endpoint"]

    def call(self, endpoint, value):
        return rpc(endpoint, {"jsonrpc": "2.0", "id": "call", "method": "tools/call",
                              "params": {"name": "echo", "arguments": {"value": value}}})["result"]

    def test_input_rejection_and_call_budget_enforced_on_real_requests(self):
        endpoint = self.launch({"inputSchema": {"type": "object", "properties": {"value": {"type": "integer"}}},
                                "outputSchema": {"type": "integer"}, "resourceLimits": {"maxCalls": 2}})
        self.assertTrue(self.call(endpoint, "wrong")["isError"])
        for value in (1, 2):
            self.assertEqual(value, json.loads(self.call(endpoint, value)["content"][0]["text"]))
        self.assertIn("call_limit_reached", self.call(endpoint, 3)["content"][0]["text"])

    def test_namespace_import_and_transport_mismatch_are_checked(self):
        endpoint = self.launch({}, namespace=True)
        self.assertEqual(3, json.loads(self.call(endpoint, 3)["content"][0]["text"]))
        with self.assertRaisesRegex(ValueError, "runtime_transport_mismatch"):
            self.launch({"transport": "socket"})
        source = self.root / "candidate/module.py"
        source.write_text("import os,zero_tool\n@zero_tool.tool\ndef echo(value):\n    return value\n"
                          "zero_tool.serve_socket(os.environ['ZERO_MOD_SOCKET'])\n")
        manifest = {"sdkRange": ">=1.1,<2", "entrypoint": "serve_socket", "transport": "socket", "probe_timeout_s": 5}
        release = {"path": str(source), "python": sys.executable, "transport": "socket", "manifest": manifest}
        self.runtime.freeze_sdk(release, self.runtime.check_sdk(release))
        endpoint = self.runtime.start("contract", release)["endpoint"]
        self.assertTrue(endpoint.startswith("unix://"))
        self.assertEqual(4, json.loads(self.call(endpoint, 4)["content"][0]["text"]))

    def test_invalid_output_cannot_be_reported_as_success(self):
        endpoint = self.launch({"outputSchema": {"type": "integer"}})
        self.assertTrue(self.call(endpoint, "wrong")["isError"])

    def test_missing_entry_and_unsupported_schema_fail_health(self):
        for declaration in ({"entrypoint": "missing"}, {"inputSchema": {"oneOf": []}},
                            {"resourceLimits": {"unknownLimit": 1}}):
            with self.assertRaises(RuntimeError):
                self.launch(declaration)

    def test_timeout_terminates_only_the_isolated_process(self):
        endpoint = self.launch({"resourceLimits": {"callTimeoutS": 0.2}}, "time.sleep(2)")
        started = time.monotonic()
        with self.assertRaises(Exception):
            self.call(endpoint, 1)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertEqual(124, self.runtime.entries["contract"]["proc"].wait(timeout=2))
