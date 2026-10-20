"""tasks.wechat_pull — 微信收消息（C4.2'' · 主进程 stdout 消息流解析，2026-09-05 实证）

WeClawBot-API 无读取 HTTP API（E25）——但主进程 stdout 实时落消息（实测）：
  [Bot: {bot_id} | Message from {sender}]: {text}
通道：worker → socket-proxy → GET /containers/{id}/logs（D102 白名单既有端点，零新增面）。

要点：
  --timestamps 行首 RFC3339 → since 精确过滤（TriggerHub 增量轮询）
  容器 recreate 日志清零——since 时间戳天然兜底（旧消息不重放，历史不追溯）
  消息内容 = 不可信输入（D107）：本任务只透传，定界包裹在消费侧（LLM Prompt）做
"""
import os
import re
import time
from datetime import datetime, timezone

import docker

from celery_app import app, update_meta

CONTAINER_NAME = os.getenv("WECLAWBOT_CONTAINER", "zero-weclawbot")
MSG_RE = re.compile(r"^\[Bot: (\S+) \| Message from (\S+)\]: (.*)$")
TS_FIX = re.compile(r"\.(\d{6})\d+")


def _docker() -> docker.DockerClient:
    return docker.DockerClient(
        base_url=os.getenv("DOCKER_HOST", "tcp://docker-socket-proxy:2375"),
        timeout=60,
    )


@app.task(name="tasks.wechat_pull", bind=True, max_retries=0)
def wechat_pull(self, since_ts: float = 0):
    task_id = self.request.id
    update_meta(task_id, status="running", started_at=int(time.time()), retry_count=0)
    try:
        client = _docker()
        cs = client.containers.list(filters={"name": CONTAINER_NAME})
        if not cs:
            raise RuntimeError(f"容器 {CONTAINER_NAME} 不在运行")
        kwargs = {"timestamps": True, "tail": 500}
        if since_ts and since_ts > 0:
            kwargs["since"] = int(since_ts)  # docker-py 拒收 0/负数
        logs = cs[0].logs(**kwargs).decode("utf-8", "replace")
        msgs = []
        for line in logs.splitlines():
            parts = line.split(" ", 1)
            if len(parts) < 2 or not parts[0][:4].isdigit():
                continue
            try:
                ts = datetime.fromisoformat(
                    TS_FIX.sub(r".\1", parts[0]).replace("Z", "+00:00")
                ).astimezone(timezone.utc).timestamp()
            except ValueError:
                continue
            m = MSG_RE.match(parts[1].strip())
            if m and m.group(3).strip():
                bot_id, sender, text = m.groups()
                msgs.append({"sender": sender, "bot_id": bot_id,
                             "text": text.strip(), "ts": round(ts, 3)})
        result = {"messages": msgs, "count": len(msgs), "server_ts": time.time()}
        update_meta(task_id, status="done", finished_at=int(time.time()), result=result)
        return result
    except Exception as e:
        update_meta(task_id, status="failed", error=str(e)[:300], finished_at=int(time.time()))
        raise
