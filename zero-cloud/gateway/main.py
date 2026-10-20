"""
Zero Cloud Gateway — MCP Server + Auth + Tool Routing（C1.1 模块化重构）

结构：
  main.py     入口 + MCP JSON-RPC 端点 + health
  auth.py     Bearer / ?token= 认证 + 每 IP 限流
  ssrf.py     URL 安全校验（D106 L1）
  runtime.py  共享客户端（httpx/redis）+ 配置 + ok/fail 统一响应
  tools/      工具自动发现（@register_tool）

⚠ Zero MCP 兼容模式（E3）：所有响应（含 notifications/*）必须返回可解析 JSON，
  禁止 204/空 body——这是 Zero 客户端约束，非 MCP 标准行为，勿"修正"。
"""
import json
import re
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse

import runtime
from auth import auth_middleware
from tools import TOOLS


@asynccontextmanager
async def lifespan(app: FastAPI):
    await runtime.open_clients()
    yield
    await runtime.close_clients()


app = FastAPI(title="Zero Cloud Gateway", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
app.middleware("http")(auth_middleware)


# ═══ Health ═══

@app.get("/health")
async def health():
    checks = {"gateway": "ok"}
    try:
        await runtime.redis_pool.ping()
        checks["redis"] = "ok"
    except Exception:
        checks["redis"] = "down"
    # E1.3：searxng 退役——健康检查同步移除（im/oc-shim 存活由 im_status 探测）
    status = 200 if all(v == "ok" for v in checks.values()) else 503
    return Response(content=json.dumps(checks), status_code=status, media_type="application/json")


# ═══ Files 下载端点（auth 中间件保护；UUID 白名单 + realpath 防穿越，C1.3）═══

_UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


@app.get("/files/{file_id}")
async def files_endpoint(file_id: str):
    def _err(msg: str, code: int):
        return Response(content=json.dumps({"error": msg}), status_code=code, media_type="application/json")

    if not _UUID_RE.match(file_id or ""):
        return _err("not found", 404)
    raw = await runtime.redis_pool.get(f"zero:file:{file_id}")
    if not raw:
        return _err("file not found or expired", 404)
    try:
        meta = json.loads(raw)
    except Exception:
        return _err("corrupt file meta", 500)
    if meta.get("expires_at", 0) < time.time():
        return _err("file expired", 410)

    base = Path(runtime.DATA_DIR).resolve()
    path = (base / meta.get("path", "")).resolve()
    if not path.is_relative_to(base) or not path.is_file():
        return _err("not found", 404)
    return FileResponse(path, media_type=meta.get("mime", "application/octet-stream"), filename=path.name)


# ═══ Files 上传端点（C3.4：端→云通道——upload_file 工具/HTTP 客户端直传；auth 中间件保护同 GET）═══

@app.post("/files")
async def upload_files_endpoint(request: Request):
    """二进制直传：POST /files?filename=x.docx&mime=...（body=文件字节）→ 登记 file_id"""
    def _err(msg: str, code: int):
        return Response(content=json.dumps({"error": msg}), status_code=code, media_type="application/json")

    raw_name = request.query_params.get("filename", "").strip()
    mime = request.query_params.get("mime", "").strip() or "application/octet-stream"
    # 净化：仅末段文件名 + 非法字符替换 + 限长（basename 语义，防穿越）
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", raw_name.replace("\\", "/").split("/")[-1]).strip()
    if not name or name.startswith("."):
        name = f"upload_{int(time.time())}"
    name = name[:150]

    cap = runtime.MAX_FILE_SIZE_MB * 1024 * 1024
    cl = request.headers.get("content-length", "")
    if cl.isdigit() and int(cl) > cap:
        return _err(f"文件超过 {runtime.MAX_FILE_SIZE_MB}MB 上限", 413)

    upload_dir = Path(runtime.DATA_DIR) / "uploads" / str(uuid.uuid4())
    upload_dir.mkdir(parents=True, exist_ok=True)
    dest = upload_dir / name
    total = 0
    try:
        with open(dest, "wb") as f:
            async for chunk in request.stream():
                total += len(chunk)
                if total > cap:
                    raise ValueError(f"文件超过 {runtime.MAX_FILE_SIZE_MB}MB 上限")
                f.write(chunk)
    except ValueError as e:
        try:
            dest.unlink()
        except OSError:
            pass
        return _err(str(e), 413)

    from tools.file import register_file
    reg = await register_file(str(dest), mime)
    url = f"{runtime.PUBLIC_BASE}/files/{reg['file_id']}" if runtime.PUBLIC_BASE else f"/files/{reg['file_id']}"
    return Response(content=json.dumps({
        "file_id": reg["file_id"], "filename": name, "mime": reg["mime"],
        "size": reg["size"], "sha256": reg["sha256"], "url": url,
        "expires_at": reg["expires_at"],
    }, ensure_ascii=False), media_type="application/json")


# ═══ Events SSE 推送端点（D114：云事件 → 手机 kick；auth 中间件保护同 /mcp）═══

@app.post("/im/events")
async def im_events_endpoint(request: Request):
    """oc-shim 入站推送（E1.2）：openclaw 轮询到新入站消息 → 本端点 → zero:events
    发布 type=im kick 标记（不含内容，Z32——内容权威在 openclaw transcript，手机经
    im_read 增量拉取）。auth 中间件已保护本路径（Bearer ZERO_CLOUD_TOKEN）。"""
    try:
        body = await request.json()
    except Exception:
        return Response(content=json.dumps({"ok": False, "error": "bad json"}),
                        status_code=400, media_type="application/json")
    if body.get("type") != "im":
        return Response(content=json.dumps({"ok": False, "error": "type must be im"}),
                        status_code=400, media_type="application/json")
    msgs = body.get("messages") or []
    channels = sorted({m.get("channel", "?") for m in msgs})
    try:
        await runtime.redis_pool.publish("zero:events", json.dumps(
            {"type": "im", "channels": channels, "count": len(msgs)}))
    except Exception as e:
        # 事件通道故障不阻塞（tick 兜底覆盖——im_read 轮询语义仍在）
        print(f"[im_events] publish failed: {e}", flush=True)
    return Response(content=json.dumps({"ok": True, "kicked": len(msgs)}),
                    media_type="application/json")


@app.get("/events")
async def events_endpoint(request: Request):
    """SSE 长连：转发 Redis pub/sub zero:events → 事件标记（不含内容，Z32）。
    心跳：无事件 30s 发 `: ping` comment（Z27——nginx proxy_read_timeout 120s 保活）。"""
    async def gen():
        pubsub = runtime.redis_pool.pubsub()
        await pubsub.subscribe("zero:events")
        try:
            while True:
                if await request.is_disconnected():
                    break
                msg = await pubsub.get_message(ignore_subscribe_messages=True, timeout=30.0)
                if msg and msg.get("type") == "message":
                    data = msg["data"]
                    if isinstance(data, bytes):
                        data = data.decode("utf-8", "replace")
                    yield f"data: {data}\n\n"
                else:
                    yield ": ping\n\n"
        finally:
            try:
                await pubsub.unsubscribe("zero:events")
                await pubsub.close()
            except Exception:
                pass

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


# ═══ MCP JSON-RPC 端点 ═══

def _jsonrpc_response(req_id, result: dict) -> Response:
    return Response(
        content=json.dumps({"jsonrpc": "2.0", "id": req_id, "result": result}),
        media_type="application/json",
    )


def _jsonrpc_error(req_id, code: int, message: str) -> Response:
    return Response(
        content=json.dumps({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}),
        media_type="application/json",
    )


def _tool_definition(name: str, t: dict) -> dict:
    return {
        "name": name,
        "description": t["description"],
        "inputSchema": t["parameters"],
    }


@app.post("/mcp")
async def mcp_endpoint(request: Request):
    """MCP Streamable HTTP — JSON-RPC 2.0（Zero MCP 兼容模式，见模块注释）"""
    try:
        body = await request.json()
    except Exception:
        return _jsonrpc_error(None, -32700, "Parse error")

    # JSON-RPC batch 不支持——返回可解析错误（E3），不得 500
    if isinstance(body, list):
        return _jsonrpc_error(None, -32600, "Invalid Request: batch not supported")

    method = body.get("method")
    req_id = body.get("id")

    # ── initialize ──
    if method == "initialize":
        result = {
            "protocolVersion": "2025-06-18",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "zero-cloud", "version": "1.1.0"},
        }
        resp = _jsonrpc_response(req_id, result)
        resp.headers["Mcp-Session-Id"] = str(uuid.uuid4())
        return resp

    # ── notifications/initialized（返回可解析 JSON——McpTransport 需要，E3）──
    if method == "notifications/initialized":
        return Response(
            content=json.dumps({"jsonrpc": "2.0", "result": {}}),
            media_type="application/json",
        )

    # ── tools/list ──
    if method == "tools/list":
        tools = [_tool_definition(name, t) for name, t in TOOLS.items()]
        return _jsonrpc_response(req_id, {"tools": tools})

    # ── tools/call ──
    if method == "tools/call":
        params = body.get("params", {})
        tool_name = params.get("name")
        arguments = params.get("arguments", {})

        if tool_name not in TOOLS:
            return _jsonrpc_response(req_id, {
                "isError": True,
                "content": [{"type": "text", "text": f"Unknown tool: {tool_name}"}],
            })

        handler = TOOLS[tool_name]["handler"]
        try:
            result = await handler(**arguments) if _is_async(handler) else handler(**arguments)
            if isinstance(result, dict) and result.get("success"):
                return _jsonrpc_response(req_id, {
                    "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
                })
            else:
                return _jsonrpc_response(req_id, {
                    "isError": True,
                    "content": [{"type": "text", "text": json.dumps(result or {"error": "empty"}, ensure_ascii=False)}],
                })
        except Exception as e:
            return _jsonrpc_response(req_id, {
                "isError": True,
                "content": [{"type": "text", "text": f"{type(e).__name__}: {e}"}],
            })

    # ── ping ──
    if method == "ping":
        return _jsonrpc_response(req_id, {})

    return _jsonrpc_error(req_id, -32601, f"Method not found: {method}")


def _is_async(fn) -> bool:
    import inspect
    return inspect.iscoroutinefunction(fn)


# ═══ 启动入口 ═══

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
