"""tasks.lib.files — 文件体系共享助手（S5.1 基建收敛：自 tasks/file.py 归位）。

单一来源：
  - load_file_meta / resolve_under_data_dir / register_file / file_result（Z6 元数据协议）
  - sha256_of
  - EXT_MIME 统一映射（原 tasks/download.py EXT_MIME 与 tasks/media.py MIME_OUT 两表合并——
    键值一致纯并集，guess_mime() 统一出口）
消费方：tasks.file / tasks.media / tasks.download / tasks.bili_login。
E15：worker 任务模块依赖一律顶层导入（本模块亦然——celery_app.rdb 在其底部 `import tasks`
之前定义，链路安全，与原 tasks/file.py 同款）。
"""
import hashlib
import json
import os
import time
import uuid as uuidlib
from pathlib import Path

from celery_app import rdb

DATA_DIR = os.getenv("DATA_DIR", "/data/zero")
MAX_FILE_SIZE_MB = int(os.getenv("MAX_FILE_SIZE_MB", "2048"))
FILE_TTL_HOURS = int(os.getenv("FILE_TTL_HOURS", "24"))
PUBLIC_BASE = os.getenv("ZERO_CLOUD_PUBLIC_BASE", "")

# 统一扩展名 → MIME（download EXT_MIME ∪ media MIME_OUT；重叠键值逐项核对一致）
EXT_MIME = {
    ".mp4": "video/mp4", ".m4a": "audio/mp4", ".mp3": "audio/mpeg",
    ".webm": "video/webm", ".mkv": "video/x-matroska", ".flac": "audio/flac",
    ".opus": "audio/opus", ".ogg": "audio/ogg", ".wav": "audio/wav",
    ".mov": "video/quicktime", ".avi": "video/x-msvideo", ".gif": "image/gif",
}


def guess_mime(path) -> str:
    """按扩展名取 MIME（未知 = octet-stream）"""
    return EXT_MIME.get(Path(path).suffix.lower(), "application/octet-stream")


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_file_meta(file_id: str) -> dict:
    raw = rdb.get(f"zero:file:{file_id}")
    if not raw:
        raise FileNotFoundError(f"输入文件不存在或已过期: {file_id}")
    meta = json.loads(raw)
    if meta.get("expires_at", 0) < time.time():
        raise FileNotFoundError(f"输入文件已过期: {file_id}")
    return meta


def resolve_under_data_dir(rel: str) -> Path:
    """归一化 + 防穿越（与 gateway /files/{id} 端点同则）"""
    base = Path(DATA_DIR).resolve()
    p = (base / rel).resolve()
    if not p.is_relative_to(base):
        raise ValueError("文件路径非法")
    return p


def register_file(abs_path: Path, mime: str) -> dict:
    """登记产物（同步版 register_file，Z6 元数据与 gateway 同构）"""
    file_id = str(uuidlib.uuid4())
    ttl = FILE_TTL_HOURS * 3600
    meta = {
        "path": str(abs_path.relative_to(Path(DATA_DIR).resolve())),
        "mime": mime,
        "size": abs_path.stat().st_size,
        "sha256": sha256_of(abs_path),
        "owner_id": "zero-main",
        "created_at": int(time.time()),
        "expires_at": int(time.time()) + ttl,
    }
    rdb.setex(f"zero:file:{file_id}", ttl, json.dumps(meta, ensure_ascii=False))
    return {"file_id": file_id, **meta}


def file_result(reg: dict) -> dict:
    """产物 → result 字段（file_id/元数据/可选 url）"""
    d = {k: reg[k] for k in ("file_id", "mime", "size", "sha256", "expires_at")}
    if PUBLIC_BASE:
        d["url"] = f"{PUBLIC_BASE}/files/{reg['file_id']}"
    return d
