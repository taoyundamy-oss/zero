# Zero V2 云服迁移与维护手册（v2 · 四容器栈）

> 版本：2026-09-20 · 适配 E1.3 后终态（gateway/openclaw/nginx/redis）
> 读者：LLM（Zero/CC 新会话）或人——**面向可直接执行**编写：每步给命令与判据，坑表给现象→根因→操作
> 权威源：本手册 + `zero-cloud/scripts/migrate.sh`（源码为最终权威）+ git 仓库 ZeroV2

---

## 一、系统全貌

### 1.1 架构与容器

```
手机 Zero App ──MCP/SSE──▶ nginx :8443（TLS 自签+指纹 pin）
                              │
                              ▼
                    gateway :8000（FastAPI · 7 工具）
                      │            │
                      ▼            ▼
                 redis :6379    openclaw :18789/19980/19981
                 （游标/配置/     （微信 iLink 通道 + oc-shim 集成面 + mute 哑模型）
                  文件登记）
```

| 容器 | 作用 | 关键端口（均仅内网） | mem_limit |
|---|---|---|---|
| zero-gateway | MCP 端点 / SSE /events / 文件 /files | 8000 | 300m |
| zero-openclaw | 微信通道（openclaw@2026.9.5 + weixin 插件@2.4.9）+ oc-shim(:19980) + mute-model(:19981) | 18789/19980/19981 | **1600m（勿降）** |
| zero-nginx | TLS 终端（8443 对外唯一口） | 8443 | — |
| zero-redis | 状态：im 游标/im 配置/文件登记 | 6379 | — |

### 1.2 资产与位置（迁移对象）

| 资产 | 位置 | 说明 |
|---|---|---|
| **全部代码与配置** | git 仓库 `ZeroV2/zero-cloud/`（服务器 `/opt/zero-cloud`） | compose/entrypoint/shim/mute-model/gateway 全在 git——**迁移=clone** |
| **微信登录会话+转录库** | `/data/zero/openclaw/.openclaw/` | state/openclaw.sqlite（转录）、openclaw-weixin/（会话）、npm/（插件装配）、openclaw.json（配置：mute 模型/gateway.mode） |
| 收件箱/上传文件 | `/data/zero/downloads|uploads|results` | |
| Redis 状态 | redis-data 卷 | im:cursor:*（消费游标）/ im:config / zero:file:*（文件登记，TTL 24h） |
| 秘密 | `/opt/zero-cloud/.env`（600） | ZERO_CLOUD_TOKEN / OPENCLAW_GATEWAY_TOKEN / NPM_REGISTRY |

### 1.3 服务器参数（现役）

- 阿里云 ECS：`203.0.113.10` · 实例 `i-bp16l4ohdfaopfsou4nw` · 2C4G Ubuntu 22.04 · 盘 40G
- 最低可运行：**1C2G/20G**；推荐：**2C2G/40G**（openclaw 稳态 ~900M + npm 重装窗口峰值 1.4G）
- 执行通道（CC）：Workbench CLI（`workbench.exe exec -i <实例ID> -c "命令"`，单命令 30s 超时——长任务 `nohup … &` + 轮询日志）

---

## 二、迁移手册（随时可迁）

### 2.1 迁移前提核对表

- [ ] 新机：Docker 已装（`curl -fsSL https://get.docker.com | sh`）
- [ ] 新机：安全组放行 **8443**（唯一对外口）
- [ ] 新机：`git clone` ZeroV2 仓库（或拷贝 zero-cloud/ 目录）到 `/opt/zero-cloud`
- [ ] 源机：磁盘余量 ≥ 2G（export 包 ~600M+，输出到 /tmp）
- [ ] 手机可操作（迁移收尾要改 MCP 条目）

### 2.2 三步迁移

**Step 1 · 源机导出**（微信通道中断约 1-3 分钟）：
```bash
cd /opt/zero-cloud
bash scripts/migrate.sh export /tmp/zero-migrate
# 判据：末行 "✅ 导出完成 …服务已恢复"；[4/5] 已重启 openclaw
bash scripts/migrate.sh verify /tmp/zero-migrate   # 判据：三项 OK
```
产物：`data.tar.gz`（/data/zero 全量含微信会话）+ `redis-dump.rdb` + `env.template` + `SHA256SUMS`。

**Step 2 · 包传输 + 新机就位**：
```bash
# 包经任意通道（OSS/ossutil/scp）到新机 /tmp/zero-migrate
# 新机 .env：从 env.template 填三个真实值，chmod 600
#   ZERO_CLOUD_TOKEN=<源机同值——手机端 Key 不用重填>
#   OPENCLAW_GATEWAY_TOKEN=<源机同值——openclaw 配置已随 data.tar 携带>
#   NPM_REGISTRY=https://registry.npmmirror.com   ← 国内机必填（见坑表 #2）
```

**Step 3 · 新机导入**：
```bash
cd /opt/zero-cloud
bash scripts/migrate.sh import /tmp/zero-migrate
# 判据："✅ 导入完成"；随后等 openclaw 首启装配（npm 重装 2-6 分钟属预期）：
docker logs -f zero-openclaw 2>&1 | grep -m1 "ready"    # 出现 [gateway] ready 即成
```

### 2.3 迁移后验证矩阵（全绿才算完成）

```bash
bash scripts/test_mcp.sh            # 判据：ALL_PASS（12 检查项）
docker ps                           # 判据：4 容器 Up，gateway (healthy)
# 微信通道（openclaw 容器内）：
docker exec zero-openclaw sh -c "OPENCLAW_STATE_DIR=/data/zero/openclaw/.openclaw \
  OPENCLAW_CONFIG_PATH=/data/zero/openclaw/.openclaw/openclaw.json openclaw channels status" \
  | grep openclaw-weixin             # 判据：enabled, configured, running
```
手机侧：微信发一条消息 → Zero 回复 = 全链终验。

### 2.4 手机侧收尾（换 IP 必做）

1. Zero App → MCP 设置 → zero-cloud 条目「编辑」：URL 改 `https://<新IP>:8443/mcp`
2. 证书指纹改新机自签证书 SHA256（`openssl x509 -in nginx/certs/server.crt -noout -fingerprint -sha256` 生成后对比 App 内 ZeroCloudTrust）
3. **Key 免重填**（apiKeyRef 稳定）；SSE/上传 URL 随 HOST 动态 getter 自动跟随

### 2.5 迁移已知坑表（先读再动）

| # | 现象 | 根因 | 操作 |
|---|---|---|---|
| 1 | openclaw 容器 Up 但 channels status 报 Gateway 不可达/工具 401 | 首启 npm 重装未完成（2-6 分钟） | 等 `docker logs zero-openclaw \| grep ready`；**勿中途 restart**（会重装） |
| 2 | npm 装配卡 4 分钟+ 无进展 | 走了 npmjs 官方源（国内 ECS 到 npmjs ~5s/请求） | `.env` 置 `NPM_REGISTRY=https://registry.npmmirror.com` 后 `docker compose up -d --force-recreate openclaw` |
| 3 | openclaw 重启后报 migration pending / plugin not loaded | 跨容器状态迁移残留（qqbot 事件同款） | 一般自愈（时间租约）；不愈走停机态手术（见维护 §3.4） |
| 4 | 微信要求重新扫码 | 换出口 IP（iLink IP 亲和） | 手机 QQ/微信扫 channels login 新码；**会话卷迁移到位则大概率免扫**（同 IP 演练实证免扫） |
| 5 | import 后 gateway 起不来 | .env 用了模板占位值 | 填真实值 chmod 600 再 `up -d`（脚本 exit 2 已拦） |
| 6 | 手机会话双响/游标重读 | redis RDB 未注入（appendonlydir 残留） | import 脚本已自动清；手跑时确认 `rm -rf <卷>/appendonlydir` |
| 7 | tar 打包后 sqlite 损坏（im_read 报 malformed） | openclaw 未停即打包 | v2 脚本已内置停机窗口；勿绕过脚本手动 tar |
| 8 | 服务器侧拉 DB 到本地解析 malformed | `adb/shell` 管道 CRLF 转换（二进制破坏） | 一律 `exec-out … cat`（二进制安全通道） |

---

## 三、维护手册

### 3.1 日常巡检（建议每日/告警触发时）

```bash
docker ps --format '{{.Names}} {{.Status}}'          # 4 容器 Up；gateway (healthy)
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'   # openclaw <1.2G 属常态
df -h / | tail -1                                     # >85% 需清理（见 3.5）
docker exec zero-openclaw tail -5 /tmp/oc-shim.log    # schema contract: OK + kick 正常
bash /opt/zero-cloud/scripts/test_mcp.sh | tail -2    # ALL_PASS
```
微信通道态：channels status（§2.3 命令）——`running, in:<近期>` 即健康。

### 3.2 常见故障 → 处置

| 现象 | 根因 | 处置 |
|---|---|---|
| gateway unhealthy | 残留检查项（已清 searxng）或 redis 不通 | `docker logs zero-gateway --tail 20`；redis 探活 `docker exec zero-redis redis-cli ping` |
| 微信消息无响应 | ①通道掉线 ②shim 死 ③SSE 断 | ①channels status 看 stopped→重扫码 ②`docker restart zero-openclaw` ③手机侧重连（自动退避） |
| 手机收不到 kick 但微信能查 | nginx SSE 缓冲/断连 | 手机端自动重连兜底；服务器 `docker logs zero-nginx --tail 10` |
| openclaw 内存顶死（媒体发送静默失败） | 1600m 上限逼近 | `docker stats` 确认 → restart openclaw（会话在卷，免扫码） |
| im_status 报 AUTH_FAILED | OPENCLAW_GATEWAY_TOKEN 失配（.env vs openclaw.json） | 以 openclaw.json 内有效值为准对齐 .env → 重建 gateway 容器 |
| 磁盘涨 | 收件箱/转录库/迁移包残留 | `du -sh /data/zero/*` 定位；迁移包删；旧 file TTL 24h 自动滚 |

### 3.3 代码部署流（gateway/脚本改动）

```bash
# 1. 本地改码 → git commit（仓库权威）
# 2. 上传（Workbench：upload 命令；ssh：scp）
# 3. 重建：
cd /opt/zero-cloud && nohup docker compose --profile core up -d --build gateway > /tmp/gw.log 2>&1 &
# 4. 验证：test_mcp.sh ALL_PASS + 冒烟一条 im_read
```
openclaw 侧（entrypoint/shim/mute-model）改动：上传后 `docker restart zero-openclaw`（shim/mute 由 entrypoint 守护拉起）；**restart 不触发 npm 重装**（容器层还在），`--force-recreate` 才会。

### 3.4 openclaw 维护（高危区——版本锁纪律）

- **版本锁死**：openclaw@2026.9.5 + weixin@2.4.9（entrypoint.sh 头注）。**禁任何自动升级**——插件状态机随版本变（qqbot 事件实证）。
- **状态手术范式**（config/插件迁移类操作，需网关停机态）：
  ```bash
  docker stop zero-openclaw
  docker commit zero-openclaw zero-openclaw-snap:1        # 快照保底
  docker run --rm -v /data/zero/openclaw:/data/zero/openclaw \
    -e OPENCLAW_STATE_DIR=/data/zero/openclaw/.openclaw \
    zero-openclaw-snap:1 bash -c "openclaw <维护命令>"
  docker start zero-openclaw
  ```
- mute 哑模型/微信配置全在 `openclaw.json`（data.tar 随迁）——改配置优先 `openclaw config set`（热应用免重启）。

### 3.5 备份策略

- **定期**：`migrate.sh export`（600M/次，~1 分钟，微信中断 <3 分钟）——建议每周/大改前
- 校验：`migrate.sh verify`（checksum 三件）；异地留存（下载到本地/oss）
- 恢复=完整迁移流程（§二）；**部分恢复**（仅微信会话）：解 data.tar 取 `/data/zero/openclaw/.openclaw/` 覆盖 + restart openclaw

### 3.6 危险级操作清单（执行前必须用户裁定）

| 操作 | 风险 |
|---|---|
| openclaw 版本锁变更 | 插件状态迁移不可预知 |
| migrate.sh import（生产迁移） | 覆盖目标机全量状态 |
| 证书/IP 变更 | 手机重 pin、微信重扫码 |
| Token 轮换 | 手机端/网关/手机 QQ 模块三处联动 |
| mem_limit 调整（openclaw <1600m） | npm 重装窗口 OOM |

### 3.7 Zero 维护分级（如交由 Zero）

| 级 | 任务 | 通道 |
|---|---|---|
| 日常（可交） | §3.1 巡检 / §3.2 前四行处置 / §3.5 export 备份 / im_config 参数管理 | ssh 受限用户（建议建 deploy 用户：sudo 白名单仅 docker compose；私钥入手机 SecureKeyStore） |
| 危险（留人工） | §3.6 全表 | 用户拍板 + CONFIRM 纪律 |

（deploy 用户/分级授权落地=独立开发单元，本手册仅记规程。）

---

## 四、速查表

| 项 | 值 |
|---|---|
| MCP 端点 | `https://<IP>:8443/mcp`（Bearer ZERO_CLOUD_TOKEN） |
| SSE | `GET /events`（30s 心跳，type=im/task） |
| 文件 | `POST /files?filename=`（传）/ `GET /files/{uuid}`（取，24h TTL） |
| 工具面 | im_status / im_login / im_read / im_send / im_config_get / im_config_set / cloud_file_url（7） |
| openclaw CLI 环境 | `OPENCLAW_STATE_DIR=/data/zero/openclaw/.openclaw OPENCLAW_CONFIG_PATH=/data/zero/openclaw/.openclaw/openclaw.json`（所有 docker exec 必带） |
| compose 起栈 | `docker compose --profile core --profile im up -d` |
| 回归 | `bash scripts/test_mcp.sh` → ALL_PASS |

*沿革：v1 手册（11 容器栈/Workbench 通道细节）见 git 历史与《ZeroV2-云服迁移手册.md》；坑表 #1/#2/#4/#8 均为本周期实测。*
