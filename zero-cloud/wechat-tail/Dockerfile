# wechat-tail — WeClawBot 日志流跟读 sidecar（D114 事件源①）
# 双网络容器（default + sandbox-net；E12：socket-proxy 静态 IP 直连）
FROM python:3.12-slim

WORKDIR /app
RUN pip install --no-cache-dir docker==7.1.0 redis==5.0.0 \
        -i https://mirrors.aliyun.com/pypi/simple/

COPY tail.py .
CMD ["python", "tail.py"]
