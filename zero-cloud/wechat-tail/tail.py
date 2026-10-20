"""wechat-tail — WeClawBot stdout 消息流跟读 → Redis pub/sub（D114 事件源①）

链路：socket-proxy（E12 静态 IP 172.20.0.5，logs 端点 D102 白名单）→ 流式跟读
zero-weclawbot 日志 → 正则命中 "Message from" 行 → publish zero:events。
- 事件负载仅类型标记（不含内容——本体由 wechat_read 鉴权拉取，Z32）
- 去抖：距上次 publish <2s 丢弃（用户连发多条合并唤醒一次）
- 自愈：容器 recreate/流断 → 5s 后重查重启跟读；启动 since=now 不回放历史
"""
import json
import os
import re
import time

import docker
import redis

PROXY = os.getenv("DOCKER_PROXY", "tcp://172.20.0.5:2375")
CONTAINER = os.getenv("WECLAWBOT_CONTAINER", "zero-weclawbot")
REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379/0")
PATTERN = re.compile(r"Message from \S+\]:")
THROTTLE_S = 2.0

rdb = redis.from_url(REDIS_URL)


def follow():
    client = docker.DockerClient(base_url=PROXY, timeout=300)
    cs = client.containers.list(filters={"name": CONTAINER})
    if not cs:
        raise RuntimeError(f"容器 {CONTAINER} 不在运行")
    stream = cs[0].logs(follow=True, timestamps=True, stream=True,
                        since=int(time.time()))
    print(f"following {CONTAINER} logs ...", flush=True)
    buf = ""
    last_pub = 0.0
    for chunk in stream:
        buf += chunk.decode("utf-8", "replace")
        while "\n" in buf:
            line, buf = buf.split("\n", 1)
            if not PATTERN.search(line):
                continue
            now = time.time()
            if now - last_pub < THROTTLE_S:
                continue
            last_pub = now
            rdb.publish("zero:events", json.dumps({"type": "wechat", "ts": round(now, 3)}))
            print(f"event: wechat ({line.strip()[:60]})", flush=True)


if __name__ == "__main__":
    while True:
        try:
            follow()
        except KeyboardInterrupt:
            break
        except Exception as e:
            print(f"retry in 5s: {e}", flush=True)
            time.sleep(5)
