"""Gateway→Worker 任务提交/取消（C2.1 · G1 协议 + R10 幂等）。

流程：
  ① 预生成 task_id → 写 zero:task:{id} = pending 元数据（Z5 全量）
  ② celery send_task(name, args, queue, task_id=…)——协议不变量：task_id == request.id
  ③ 幂等：zero:idem:{key} → task_id（TTL 24h；显式 idempotency_key 优先，
     缺省 sha256(name+args)[:32]；重复提交返回原任务 + deduplicated 标记）

取消：control.revoke(terminate=True) + 元数据置 cancelled（沙箱容器停止在 C2.2 追加）。
"""
import hashlib
import json
import time
import uuid as uuidlib

from celery import Celery

import runtime

TASK_TTL = 86400  # 24h
_celery: Celery | None = None


def _app() -> Celery:
    global _celery
    if _celery is None:
        _celery = Celery("zero-gw", broker=runtime.REDIS_URL, backend=runtime.REDIS_URL)
        _celery.conf.task_ignore_result = True
    return _celery


async def submit_task(name: str, args: list, queue: str, idempotency_key: str = "") -> dict:
    # S7.1（D120/G8/F10）：quiesce 拒新任务——迁移导出窗口内新提交诚实拒绝
    # （在途任务不受影响：由 migrate.sh 等 active jobs=0 真静止保证）
    try:
        if await runtime.redis_pool.get("zero:quiesce"):
            return {"task_id": "", "status": "quiesce",
                    "error": "服务器迁移维护中（一致性快照窗口），暂不接受新任务——请稍后重试"}
    except Exception:
        pass  # 探测失败不阻塞正常提交路径（E28：迁移窗口由脚本侧兜底核对）
    key = idempotency_key or hashlib.sha256(
        f"{name}|{json.dumps(args, sort_keys=True, ensure_ascii=False)}".encode()
    ).hexdigest()[:32]

    idem_key = f"zero:idem:{key}"
    existing = await runtime.redis_pool.get(idem_key)
    if existing:
        raw = await runtime.redis_pool.get(f"zero:task:{existing}")
        status = json.loads(raw).get("status", "unknown") if raw else "unknown"
        # 失败任务允许重新提交（覆盖幂等映射）；cancelled 同理
        if status not in ("failed", "cancelled"):
            return {"task_id": existing, "status": status, "deduplicated": True}

    task_id = uuidlib.uuid4().hex
    await runtime.redis_pool.setex(f"zero:task:{task_id}", TASK_TTL, json.dumps({
        "status": "pending", "type": name, "owner": "zero-main",
        "created_at": int(time.time()), "retry_count": 0,
    }))
    await runtime.redis_pool.set(idem_key, task_id, ex=TASK_TTL)
    _app().send_task(name, args=args, queue=queue, task_id=task_id)
    return {"task_id": task_id, "status": "pending"}


async def cancel_task(task_id: str) -> dict:
    raw = await runtime.redis_pool.get(f"zero:task:{task_id}")
    if not raw:
        return {"ok": False, "error": "任务不存在或已过期", "code": "NOT_FOUND"}
    try:
        meta = json.loads(raw)
    except Exception:
        return {"ok": False, "error": "任务记录损坏", "code": "INTERNAL"}

    if meta.get("status") in ("done", "failed", "cancelled"):
        return {"ok": True, "status": meta["status"], "note": "任务已结束，无需取消"}

    _app().control.revoke(task_id, terminate=True)
    meta["status"] = "cancelled"
    meta["finished_at"] = int(time.time())
    await runtime.redis_pool.setex(f"zero:task:{task_id}", TASK_TTL, json.dumps(meta, ensure_ascii=False))
    return {"ok": True, "status": "cancelled"}
