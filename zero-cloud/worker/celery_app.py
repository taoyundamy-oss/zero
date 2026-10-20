"""Zero Cloud Worker — Celery 应用与任务元数据助手（C2.1）

队列（Z8/P1-5 隔离）：fast（轻量）/ cpu（研究·沙箱）/ download（下载）/ sandbox（C2.2）/ media（S6.1 媒体专队，worker-media 并发=1）
任务元数据：zero:task:{task_id}（与 Gateway 共享协议，Z5 全量字段）
协议不变量：task_id == Celery request.id（Gateway 预生成并随 send_task 传入）
大结果落 /data/zero/results/{task_id}.json，Redis 只存 result_ref（Z5/R14）。
"""
import json
import os
import time

import redis
from celery import Celery

REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379/0")
DATA_DIR = os.getenv("DATA_DIR", "/data/zero")
TASK_TTL = 86400  # 24h

app = Celery("zero", broker=REDIS_URL, backend=REDIS_URL)
app.conf.update(
    task_ignore_result=True,             # 结果走 zero:task 元数据，不用 Celery backend
    task_acks_late=True,                 # Worker 崩溃任务可重派（RETRYABLE 语义）
    worker_prefetch_multiplier=1,        # 长任务公平调度
    broker_connection_retry_on_startup=True,
    task_routes={
        "tasks.ping": {"queue": "fast"},
        "tasks.research": {"queue": "cpu"},
        "tasks.python_exec": {"queue": "cpu"},
        "tasks.download": {"queue": "download"},
        "tasks.file.*": {"queue": "fast"},
        # S6.1（D119/P0-1）：媒体专队——worker-media 并发=1 承接（隔离通道稳定性）
        "tasks.media.*": {"queue": "media"},
    },
)

rdb = redis.from_url(REDIS_URL, decode_responses=True)


def update_meta(task_id: str, **fields) -> None:
    """读改写任务元数据（TTL 24h 滚动续期）。
    D114 事件源②：status 置 done/failed 时 publish zero:events（手机 SSE → kick）。
    E29：tasks.wechat_pull 不发事件——它是手机 tick 检查器的自产任务，发了即成
    自激励环（pull done → kick → tick → 又 pull → ...），实测 2-7s/轮风暴打爆
    TriggerCap。微信侧真事件由 wechat-tail 的 wechat 事件承担。"""
    raw = rdb.get(f"zero:task:{task_id}")
    meta = json.loads(raw) if raw else {}
    meta.update(fields)
    rdb.setex(f"zero:task:{task_id}", TASK_TTL, json.dumps(meta, ensure_ascii=False))
    if fields.get("status") in ("done", "failed") and meta.get("type") != "tasks.wechat_pull":
        try:
            rdb.publish("zero:events", json.dumps(
                {"type": "task", "id": task_id, "status": fields["status"]}))
        except Exception:
            pass  # 事件通道故障不阻塞任务元数据写入（tick 兜底覆盖）


def save_big_result(task_id: str, result: dict) -> str:
    """大结果落盘 /data/zero/results/，返回 result_ref（Redis 只存引用）"""
    path = os.path.join(DATA_DIR, "results", f"{task_id}.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False)
    return path


# 触发任务模块注册（tasks/__init__ 导入全部任务模块；须在 app 定义之后）
import tasks  # noqa: F401,E402
