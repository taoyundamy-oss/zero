# zero_tool.py —— Zero 轻量 SDK shim（零依赖，仅 stdlib，MCP 兼容子集 Profile v1）
# 契约源：ZeroV2-模块化重构方案.md §4.3/§4.4（Profile v1 封板）——《模块构建指引》§三 API 表同源。
# 部署位（M1.4 bootstrap）：$PREFIX/zero/zero_sdk/（Termux 侧）+ /root/zero/zero_sdk/（Debian 侧，模块 import 用）。
import ast, inspect, json, os, typing, hashlib, math, threading, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_TOOLS = {}
SDK_VERSION = "1.1.0"
_FILES = {}  # name -> abs path（serve_file 注册，供 GET /file/<name> 流式回传，A5）
_CONTRACT = json.loads(os.environ.get("ZERO_RELEASE_MANIFEST", "{}"))
_CALL_LOCK = threading.Lock()
_CALL_COUNT = 0


def validate_schema(schema, depth=0):
    if not isinstance(schema, dict) or depth > 16:
        raise ValueError("unsupported_contract_schema")
    supported = {"type", "properties", "required", "additionalProperties", "items", "enum",
                 "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
                 "title", "description", "default"}
    if set(schema) - supported:
        raise ValueError("unsupported_contract_keyword")
    if "type" in schema and schema["type"] not in ("object", "array", "string", "integer", "number", "boolean", "null"):
        raise ValueError("invalid_contract_type")
    properties = schema.get("properties", {})
    required = schema.get("required", [])
    if not isinstance(properties, dict) or not isinstance(required, list) or any(not isinstance(k, str) for k in required):
        raise ValueError("invalid_contract_properties")
    if "additionalProperties" in schema and type(schema["additionalProperties"]) is not bool:
        raise ValueError("unsupported_additional_properties")
    if "enum" in schema and (not isinstance(schema["enum"], list) or not schema["enum"]):
        raise ValueError("invalid_contract_enum")
    for key in ("minLength", "maxLength", "minItems", "maxItems"):
        if key in schema and (type(schema[key]) is not int or schema[key] < 0):
            raise ValueError("invalid_contract_bound")
    for key in ("minimum", "maximum"):
        if key in schema and (type(schema[key]) not in (int, float) or not math.isfinite(schema[key])):
            raise ValueError("invalid_contract_bound")
    for child in properties.values():
        validate_schema(child, depth + 1)
    if "items" in schema:
        validate_schema(schema["items"], depth + 1)


def check_value(value, schema):
    if not schema:
        return
    kind = schema.get("type")
    types = {"object": isinstance(value, dict), "array": isinstance(value, list), "string": isinstance(value, str),
             "integer": type(value) is int, "number": type(value) in (int, float),
             "boolean": type(value) is bool, "null": value is None}
    if kind is not None and not types[kind]:
        raise ValueError("contract_type_mismatch")
    if type(value) in (int, float):
        if not math.isfinite(value) or value < schema.get("minimum", -math.inf) or value > schema.get("maximum", math.inf):
            raise ValueError("contract_number_bound")
    if "enum" in schema and not any(type(value) is type(item) and value == item for item in schema["enum"]):
        raise ValueError("contract_enum_mismatch")
    if isinstance(value, dict):
        props = schema.get("properties", {})
        if not set(schema.get("required", [])).issubset(value):
            raise ValueError("contract_required_missing")
        if schema.get("additionalProperties") is False and set(value) - set(props):
            raise ValueError("contract_additional_property")
        for name in value.keys() & props.keys():
            check_value(value[name], props[name])
    if isinstance(value, (str, list)):
        minimum, maximum = ("minLength", "maxLength") if isinstance(value, str) else ("minItems", "maxItems")
        if not schema.get(minimum, 0) <= len(value) <= schema.get(maximum, math.inf):
            raise ValueError("contract_length_bound")
    if isinstance(value, list) and "items" in schema:
        for item in value:
            check_value(item, schema["items"])


def contract_digest():
    return hashlib.sha256(json.dumps(_CONTRACT, sort_keys=True, ensure_ascii=False,
                                    separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def prepare_contract():
    for name in ("inputSchema", "outputSchema"):
        validate_schema(_CONTRACT.get(name, {}))
    limits = _CONTRACT.get("resourceLimits", {})
    if set(limits) - {"maxCalls", "callTimeoutS"}:
        raise ValueError("unsupported_resource_limit")
    if "maxCalls" in limits and (type(limits["maxCalls"]) is not int or limits["maxCalls"] < 1):
        raise ValueError("invalid_call_limit")
    if "callTimeoutS" in limits and (type(limits["callTimeoutS"]) not in (int, float) or
                                     not math.isfinite(limits["callTimeoutS"]) or not 0 < limits["callTimeoutS"] <= 600):
        raise ValueError("invalid_call_timeout")
    name = _CONTRACT.get("entrypoint")
    entry = vars(sys.modules["__main__"]).get(name)
    if entry is None and name in ("serve", "serve_socket"):
        entry = globals()[name]  # Both `from zero_tool import serve` and namespace imports are valid SDK use.
    if name and not callable(entry):
        raise ValueError("entrypoint_not_callable")


def tool(fn):
    """装饰器：从类型注解+docstring 自动生成 JSON schema，注册工具。

    M2.1b 实测修复：原设计稿误写 typing.Parameter（不存在，Debian 3.13 实测
    AttributeError）——注解缺省判定是 inspect.Parameter.empty。
    """
    sig = inspect.signature(fn)
    props, req = {}, []
    for name, p in sig.parameters.items():
        t = p.annotation if p.annotation is not inspect.Parameter.empty else str
        props[name] = _py_to_jsonschema(t)
        if p.default is inspect.Parameter.empty:
            req.append(name)
    _TOOLS[fn.__name__] = {
        "name": fn.__name__,
        "description": (fn.__doc__ or "").strip(),
        "inputSchema": {"type": "object", "properties": props, "required": req},
        "fn": fn,
    }
    return fn


def _py_to_jsonschema(t):  # str/int/float/bool/list/dict → JSON Schema
    m = {str: "string", int: "integer", float: "number", bool: "boolean"}
    return {"type": m.get(t, "string")}


def _dispatch(req):
    """核心 JSON-RPC 分发（initialize/tools/list/tools/call）—— HTTP 与 socket 传输共用。"""
    method, rid = req.get("method"), req.get("id")
    if method == "initialize":
        res = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
               "serverInfo": {"name": "zero-mod", "version": "1", "sdkVersion": SDK_VERSION,
                              "contractDigest": contract_digest()}}
    elif method == "tools/list":
        res = {"tools": [{k: v for k, v in t.items() if k != "fn"} for t in _TOOLS.values()]}
    elif method == "tools/call":
        t = _TOOLS.get(req["params"]["name"])
        if t is None:
            return {"jsonrpc": "2.0", "id": rid,
                    "error": {"code": -32602, "message": "unknown tool: %s" % req["params"]["name"]}}
        try:
            arguments = req["params"].get("arguments", {})
            check_value(arguments, _CONTRACT.get("inputSchema", {}))
            global _CALL_COUNT
            limits = _CONTRACT.get("resourceLimits", {})
            with _CALL_LOCK:
                if _CALL_COUNT >= limits.get("maxCalls", math.inf):
                    raise ValueError("call_limit_reached")
                _CALL_COUNT += 1
            finished = threading.Event()
            timeout = limits.get("callTimeoutS")
            timer = threading.Timer(timeout, lambda: os._exit(124) if not finished.is_set() else None) if timeout else None
            if timer:
                timer.start()
            try:
                out = t["fn"](**arguments)
                check_value(out, _CONTRACT.get("outputSchema", {}))
            finally:
                finished.set()
                if timer:
                    timer.cancel()
            res = {"content": [{"type": "text", "text": json.dumps(out, ensure_ascii=False, default=str)}]}
        except Exception as e:
            res = {"isError": True, "content": [{"type": "text", "text": f"{type(e).__name__}: {e}"}]}
    else:
        return {"jsonrpc": "2.0", "id": rid,
                "error": {"code": -32601, "message": "method not found: %s" % method}}
    return {"jsonrpc": "2.0", "id": rid, "result": res}


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # MCP 兼容（application/json 单响应；Mcp-Session-Id 可忽略——McpTransport null-safe，A3）
        req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self._respond(_dispatch(req))

    def do_GET(self):  # 文件回传通道（A5）：下载型模块把大二进制交还 app 入 SAF，支持 Range
        name = self.path.replace("/file/", "").split("?")[0]
        path = _FILES.get(name)
        if not path or not os.path.exists(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            s = rng[6:].split("-")[0]
            start = int(s) if s else 0
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                buf = f.read(min(65536, remaining))
                if not buf:
                    break
                self.wfile.write(buf)
                remaining -= len(buf)

    def _respond(self, obj):
        b = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, fmt, *args):  # 静默默认访问日志（stdout 保持 ZERO_MOD_* 协议行纯净）
        pass


def serve_file(abs_path):
    """注册文件供 GET /file/<name> 流式回传（Range），返回文件名。下载型模块用（A5）。"""
    name = os.path.basename(abs_path)
    _FILES[name] = abs_path
    return name


def get_config() -> dict:
    """读配置（D68）——supervisor 经 env ZERO_MOD_CONFIG（JSON）注入；空/坏 JSON 返回 {}（不炸启动）。"""
    raw = os.environ.get("ZERO_MOD_CONFIG", "")
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except Exception:
        return {}


def serve(host="127.0.0.1", port=0):
    # A managed start explicitly selects its transport via the runtime environment.
    # Legacy modules using serve() must follow that selection on HTTP fallback too.
    abstract_name = os.environ.get("ZERO_MOD_SOCKET")
    if abstract_name:
        return serve_socket(abstract_name)
    prepare_contract()
    # HTTP 传输（主，D59）——端口自分配经 stdout 回报供 app 连接
    srv = ThreadingHTTPServer((host, port), _Handler)
    print(f"ZERO_MOD_LISTENING {srv.server_address[1]}", flush=True)
    srv.serve_forever()


def serve_socket(abstract_name):
    """unix 抽象命名空间 socket 传输（A4 兜底，Android 16 loopback 受限时）。
    MCP 语义不变，传输=4 字节大端长度前缀 + JSON-RPC over AF_UNIX（与 McpSocketTransport 对称）。
    抽象名（\\0 前缀）的跨 UID 连接仍受系统权限约束；Android 经 supervisor 受管代理访问。
    每连接一线程（M2.3 加固：原串行 accept 在长 tools/call 占住当前连接时饿死并发调用方
    ——与 zero_supervisor.socket_server 同构）；_dispatch 无共享可变态，线程安全。"""
    prepare_contract()
    import socket, struct, threading
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind("\0" + abstract_name)  # 抽象名（\0 前缀，无文件路径，跨 app 共享）
    srv.listen(8)
    print(f"ZERO_MOD_SOCKET {abstract_name}", flush=True)

    def _recvn(c, n):
        d = b""
        while len(d) < n:
            b = c.recv(n - len(d))
            if not b:
                return None
            d += b
        return d

    def _conn(c):
        try:
            while True:
                hdr = _recvn(c, 4)
                if not hdr:
                    break
                req = json.loads(_recvn(c, struct.unpack(">I", hdr)[0]))
                resp = json.dumps(_dispatch(req)).encode()
                c.sendall(struct.pack(">I", len(resp)) + resp)
        except Exception:
            pass
        finally:
            c.close()

    while True:
        conn, _ = srv.accept()
        threading.Thread(target=_conn, args=(conn,), daemon=True).start()
