# WeClawBot-API 容器（C4.1' · 缩围为通知通道）
# 镜像源：cp0204/weclawbot-api 不在 DaoCloud 白名单（E14 同款）→ 官方 release 二进制自包装
# 基镜：glibc 系（E26：v1.0 二进制为 glibc 动态链接，alpine/musl 报 "exec: no such file"）
#       python:3.12-slim 本地已缓存零拉取；ca-certificates 供微信 TLS
# 二进制：v1.0 linux_amd64（≈6.5MB）
# 登录：容器终端 `docker exec -it zero-weclawbot bot` 扫码（一次性人工操作）
# 持久化：/app/config/auth.json（宿主 ./weclawbot/config 挂载，gateway ro 同读）
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ADD https://github.com/Cp0204/WeClawBot-API/releases/download/v1.0/weclawbot-api_linux_amd64 /app/weclawbot-api
RUN chmod +x /app/weclawbot-api && mkdir -p /app/config \
    && printf '#!/bin/sh\nexec /app/weclawbot-api "$@"\n' > /usr/local/bin/bot \
    && chmod +x /usr/local/bin/bot

WORKDIR /app
EXPOSE 26322
CMD ["/app/weclawbot-api"]
