"""cloud_file_url — 文件 URL/元数据查询（C1.3 实现，替换 stub）。

Z6 元数据：{url, mime, size, sha256, owner_id, expires_at}；
owner_id 常量 "zero-main"（预留多设备）。
文件本体在 /data/zero（Redis 只存元数据）；下载经 GET /files/{file_id}（Bearer/?token 保护，见 main.py）。

cloud_file_convert — C3.1 实现（提交 Worker tasks.file.convert → Gotenberg，fast 队列）。
cloud_file_process — C3.2 实现（提交 Worker tasks.file.process；全部 AUTO——只读/产新文件不破坏原件）。

register_file()：文件登记助手（C3 的 download/写盘工具调用；测试种子也用）。
"""
import hashlib
import json
import os
import re
import time
import uuid as uuidlib

import runtime
from runtime import ok, fail
from tools import register_tool

OWNER = "zero-main"  # Z6：单用户常量，预留多设备

_UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


async def register_file(abs_path: str, mime: str, ttl_hours: int = 0) -> dict:
    """登记文件 → 写 Redis 元数据（zero:file:{uuid}，TTL=expires_at 同步）。
    @param abs_path: 必须位于 runtime.DATA_DIR 之下
    @return {file_id, path, mime, size, sha256, owner_id, created_at, expires_at}
    """
    file_id = str(uuidlib.uuid4())
    ttl = ttl_hours or runtime.FILE_TTL_HOURS
    meta = {
        "path": os.path.relpath(abs_path, runtime.DATA_DIR),
        "mime": mime,
        "size": os.path.getsize(abs_path),
        "sha256": _sha256(abs_path),
        "owner_id": OWNER,
        "created_at": int(time.time()),
        "expires_at": int(time.time()) + ttl * 3600,
    }
    await runtime.redis_pool.setex(f"zero:file:{file_id}", ttl * 3600, json.dumps(meta))
    return {"file_id": file_id, **meta}


@register_tool(
    "cloud_file_url",
    "获取文件下载 URL 与元数据（mime/size/sha256/有效期）。",
    {
        "type": "object",
        "properties": {
            "file_id": {"type": "string", "description": "文件 ID（UUID）"},
        },
        "required": ["file_id"],
    },
)
async def cloud_file_url(file_id: str):
    if not _UUID_RE.match(file_id or ""):
        return fail("file_id 非法（须为 UUID）", "INVALID_PARAM")
    try:
        raw = await runtime.redis_pool.get(f"zero:file:{file_id}")
    except Exception as e:
        return fail(f"文件存储不可达: {str(e)[:120]}", "INTERNAL")
    if not raw:
        return fail(f"文件不存在或已过期（TTL {runtime.FILE_TTL_HOURS}h）: {file_id}", "NOT_FOUND")
    try:
        meta = json.loads(raw)
    except Exception:
        return fail("文件记录损坏", "INTERNAL")
    if meta.get("expires_at", 0) < time.time():
        return fail("文件已过期", "NOT_FOUND")
    path = f"/files/{file_id}"
    url = f"{runtime.PUBLIC_BASE}{path}" if runtime.PUBLIC_BASE else path
    return ok({
        "url": url,
        "mime": meta.get("mime"),
        "size": meta.get("size"),
        "sha256": meta.get("sha256"),
        "owner_id": meta.get("owner_id"),
        "expires_at": meta.get("expires_at"),
    })


# 与 worker/tasks/file.py ACTION_EXT 保持一致（先拒不匹配项，免空转任务）
_PROCESS_ACTIONS = {
    "pdf_extract_text": {".pdf"},
    "pdf_split": {".pdf"},
    "docx_read": {".docx"},
    "xlsx_read": {".xlsx"},
    "image_resize": {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"},
    "pdf_ocr": {".pdf"},
    "image_ocr": {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"},
}


