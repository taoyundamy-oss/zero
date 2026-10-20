"""
共享运行时（C1.1 新增，模块化拆分的公共层）：
  - 环境配置（含 G6 资源配额）
  - httpx / redis 客户端（main.lifespan 管理，工具模块经 `runtime.http` 访问）
  - ok / fail 统一响应（设计 §6.2）

⚠ 工具模块必须 `import runtime` 后用 `runtime.http`（模块级 from-import 会绑住 None）。
"""
import os

import httpx
import redis.asyncio as aioredis

# ── 端点配置 ──
TOKEN = os.getenv("ZERO_CLOUD_TOKEN", "")
REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379/0")
DATA_DIR = "/data/zero"
# 对外基地址（cloud_file_url 拼 URL 用；P-ICP 偏差后 = IP:8443）
PUBLIC_BASE = os.getenv("ZERO_CLOUD_PUBLIC_BASE", "")

# ── 资源配额（G6，工具执行前检查）──
MAX_FILE_SIZE_MB = int(os.getenv("MAX_FILE_SIZE_MB", "2048"))
MAX_TOTAL_STORAGE_GB = int(os.getenv("MAX_TOTAL_STORAGE_GB", "20"))
MAX_CONCURRENT_TASKS = int(os.getenv("MAX_CONCURRENT_TASKS", "3"))
FILE_TTL_HOURS = int(os.getenv("FILE_TTL_HOURS", "24"))

# ── 客户端（lifespan 填充）──
redis_pool: aioredis.Redis | None = None
http: httpx.AsyncClient | None = None


async def open_clients():
    global redis_pool, http
    redis_pool = aioredis.from_url(REDIS_URL, decode_responses=True)
    http = httpx.AsyncClient(timeout=30.0)


async def close_clients():
    global redis_pool, http
    if http:
        await http.aclose()
    if redis_pool:
        await redis_pool.close()


# ── 统一响应（§6.2）──
def ok(result, meta=None) -> dict:
    return {"success": True, "result": result, "meta": meta or {}}


def fail(error, code: str = "GENERAL") -> dict:
    return {"success": False, "error": error, "code": code}
