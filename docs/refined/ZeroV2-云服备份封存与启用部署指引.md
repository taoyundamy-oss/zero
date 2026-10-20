# Zero V2 云服备份封存与启用部署指引

> 日期：2026-09-19 · 备份来源：203.0.113.10（11 容器全量态 · E1.3 计算面退役**之前**）
> 封存位置：`D:\ZeroV2\backups\zero-cloud\2026-09-19\zero-cloud-backup-20260919.tar.gz`（114,549,704 B）
> SHA-256：`3f03442bc2520788c8bd113ddc20f3d0dede9c5d5957cf0c86a8508157248944`
> 代码层备份：`zero-cloud/` 全量在 git（50 文件，含 compose/deploy.sh/全部 Dockerfile/nginx 配置）——两份资产互相独立、配合使用。

---

## 一、封存内容与敏感性

| 文件（解包后） | 内容 | 敏感性 |
|---|---|---|
| `data.tar.gz` | `/data/zero` 全量（文件库/收件箱/上传/结果） | ⚠️ 内含 `.bili_credential.json`（**B站账号会话凭据**，Z19 纪律管辖） |
| `redis-dump.rdb` | 任务元数据/文件登记/幂等键/微信读游标 | 低 |
| `weclawbot-config.tar.gz` | WeClawBot 扫码会话（auth.json） | ⚠️ **微信登录态**（等同账号会话） |
| `env.template` | `.env` 键清单（值已脱敏 `<FILL_ME>`） | 无值 |
| `SHA256SUMS` / `MANIFEST.txt` | 完整性清单 | — |

**纪律**：备份包已 gitignore（`backups/`）；**不入 git、不外传、不贴对话**（与 bilibili 凭据 P1-4 同级待遇）。

## 二、启用前校验（每次启用必做）

```bash
cd D:/ZeroV2/backups/zero-cloud/2026-09-19
sha256sum zero-cloud-backup-20260919.tar.gz        # 必须等于 3f03442b…8944（README 同）
mkdir /tmp/zc && tar -xzf zero-cloud-backup-20260919.tar.gz -C /tmp/zc
cd /tmp/zc/zero-migrate-bak-20260919 && sha256sum -c SHA256SUMS   # 四项全 OK
```

## 三、启用部署 · 场景 A：全新服务器全量恢复（含数据）

**目标机建议**：1C2G（最低 1C1G + 2G swap——11 容器全量态贴线；E 轨 4 容器终态则宽裕）/ 盘 40G / Ubuntu 22.04。

**前置（一次性）**：
1. Docker + daemon.json 配 DaoCloud 镜像加速（E4——Docker Hub 被墙）；
2. 安全组放行 8443（唯一公网入口）；8000 不放行（Y9/D104 入口收敛）；
3. 建立部署通道（Workbench CLI 或 SSH）；上传通道用 **PowerShell**（E5：Git Bash 路径劫持）。

**步骤**：
```
① 上传代码：zero-cloud/ 全目录 → /opt/zero-cloud（git 为源；或 WB upload 打包上传）
② 上传备份包 → /opt/zero-cloud-backup-20260919.tar.gz → 解包得 zero-migrate-bak-20260919/
③ 填 .env：cp env.template → /opt/zero-cloud/.env，逐键填真实值（来源：手头凭据——
   MCP Token / DEEPSEEK_API_KEY / DASHSCOPE_API_KEY / ZERO_CLOUD_PUBLIC_BASE=新机公网 IP），
   chmod 600。⚠️ 证书两项先留空（步骤⑤重签后回填）
④ 导入：cd /opt/zero-cloud && bash scripts/migrate.sh import /opt/zero-migrate-bak-20260919
   （脚本内建：RDB 注入 redis 卷 + /data/zero 解包 + weclawbot 会话恢复 + quiesce 残留清除）
⑤ 证书：新机自签 10 年（SAN=IP:新公网IP，P-ICP 先例）→ 指纹回填 .env/nginx 挂载 →
   **Android 侧重新 pin**（S7.2：MCP 条目编辑改指纹，或 SecureKeyStore
   zero_cloud:cert_sha256_extra 备份通道；不重 pin 则全链 TLS 拒连）
⑥ Android 切换：MCP 服务器 URL 改 https://新IP:8443/mcp?token=…（S7.2 免代码）
⑦ 验收：bash scripts/test_mcp.sh（9 用例 ALL_PASS）+ docker ps 11 容器 +
   微信发消息验扫码会话存活（weclawbot 会话随包恢复，一般免重扫）
```

## 四、启用部署 · 场景 B：代码级干净重建（不带历史数据）

适用于：E 轨 4 容器终态部署（openclaw 取代 weclawbot 后**本包的 weclawbot 会话即失效**）、或只要功能不要历史。

```
① zero-cloud/ 代码上传（git 为源）+ .env 全量手填
② bash deploy.sh（build + up）
③ 自签证书 + Android pin（同场景 A ⑤⑥）
④ openclaw 三通道扫码（E1.1）——登录态在 openclaw 自己的卷
⑤ test_mcp.sh 验收
```

## 五、启用部署 · 场景 C：部分恢复

| 需求 | 操作 |
|---|---|
| 只救文件库 | 解 `data.tar.gz` 取 `/data/zero` 需要的文件（redis 登记丢失则 file_id 失效——文件本体仍在） |
| 只救微信扫码会话 | 解 `weclawbot-config.tar.gz` → `zero-cloud/weclawbot/config/` → 重启 weclawbot 容器 |
| 只救游标/任务态 | `redis-dump.rdb` 注入 redis 卷（import 脚本步骤①的单独执行） |

## 六、注意事项

1. **快照时点语义**：G8 单一时间切片（quiesce 拒新 + active=0 真静止 + BGSAVE 同刻）——包内数据一致，但**仅代表 2026-09-19 13:52 时点**；此后服务器新数据（微信游标推进/新文件）不在包内。重大变更后应重导封存。
2. **本包形态与 E 轨的关系**：本包是 11 容器全量态；E1.3 退役执行后**应重新导出一份 4 容器终态包**（migrate.sh 对新形态同样适用，weclawbot 段自动跳过）。
3. **降配迁移**：import 到 1C1G 前，建议先按云服架构演进方案 §三 在现机实测瘦身稳态。
4. **Workbench 通道坑**（E5/E6 已入册）：上传/下载必须 PowerShell；>30s 命令 `nohup … &` + 轮询日志。
5. **密钥不可复原性**：`.env` 真实值只在服务器与用户手头——封存前确认 DeepSeek/DashScope/MCP token 有据可查，否则随服务器退役即失。

---

*本指引与备份包同日生成；启用操作如遇与本指引不符的服务器实况，以实况为准并回写本档。*
