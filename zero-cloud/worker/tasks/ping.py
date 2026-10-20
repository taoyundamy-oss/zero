"""tasks.ping — Worker 连通性/演示任务（C2.1 验证用）。

任务骨架（后续 research/python_exec/download 沿用）：
  running 元数据 → 业务 → done（小结果 inline result / 大结果 result_ref）
  异常 → retrying（RETRYABLE）→ 重试耗尽 → failed
"""
import time

from celery_app import app, update_meta


@app.task(name="tasks.ping", bind=True, max_retries=3, retry_backoff=True)
def ping(self, sleep_s: float = 3.0, message: str = "pong"):
    task_id = self.request.id  # 协议不变量：Gateway 预生成的 task_id
    try:
        update_meta(task_id, status="running", started_at=int(time.time()),
                    retry_count=self.request.retries)
        if sleep_s > 0:
            time.sleep(sleep_s)
        update_meta(task_id, status="done", finished_at=int(time.time()),
                    result={"message": message, "slept": sleep_s})
        return message
    except Exception as e:
        if self.request.retries < self.max_retries:
            update_meta(task_id, status="retrying", retry_count=self.request.retries + 1,
                        last_error=str(e)[:200])
            raise self.retry(exc=e)
        update_meta(task_id, status="failed", error=str(e)[:300],
                    finished_at=int(time.time()))
        raise
