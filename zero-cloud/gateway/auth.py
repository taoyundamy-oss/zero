"""
认证 + 限流中间件（C1.1，从 main.py 拆出）。

- 认证双模式：Authorization: Bearer <TOKEN>（标准）/ URL ?token= 参数（Zero 客户端兼容，E2）
- 限流：每 IP 滑动窗口计数（简单内存实现；nginx 10r/s 为第一层，此处为第二层）
- /health /healthz /docs* 公开（健康探测）
- 未认证 → 401 JSON-RPC 错误体（E3：所有响应可解析 JSON）
"""
import json
import os
import time
from collections import defaultdict, deque

from fastapi import Request, Response

from runtime import TOKEN

RATE_LIMIT_PER_MIN = int(os.getenv("RATE_LIMIT_PER_MIN", "120"))
PUBLIC_PATHS = ("/health", "/healthz")

_hits: dict = defaultdict(deque)


def _rate_limited(ip: str) -> bool:
    """每 IP 60 秒滑动窗口"""
    now = time.monotonic()
    q = _hits[ip]
    while q and q[0] < now - 60:
        q.popleft()
    if len(q) >= RATE_LIMIT_PER_MIN:
        return True
    q.append(now)
    return False


def _rpc_error(code: int, message: str, status: int) -> Response:
    return Response(
        content=json.dumps({"jsonrpc": "2.0", "error": {"code": code, "message": message}}),
        status_code=status,
        media_type="application/json",
    )


async def auth_middleware(request: Request, call_next):
    path = request.url.path
    if path in PUBLIC_PATHS or path.startswith("/docs"):
        return await call_next(request)

    ip = request.client.host if request.client else "?"
    if _rate_limited(ip):
        return _rpc_error(-32002, "Rate limited", 429)

    auth = request.headers.get("Authorization", "")
    if TOKEN and auth == f"Bearer {TOKEN}":
        return await call_next(request)

    token_param = request.query_params.get("token", "")
    if TOKEN and token_param == TOKEN:
        return await call_next(request)

    return _rpc_error(-32001, "Unauthorized", 401)
