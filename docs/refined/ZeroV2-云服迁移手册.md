<!--
  本文件为 Claude 持久记忆《zero-cloud-migration》的仓库备份（2026-09-11）。
  活副本：C:\Users\USER\.claude\projects\d--ZeroV2\memory\zero-cloud-migration.md（服务器信息变更时同步更新两处）。
  脚本权威实现：zero-cloud/scripts/migrate.sh；迁移演练记录：收束落地实施计划 §九 S7.1 行。
-->
---
name: zero-cloud-migration
description: zero-cloud 云服迁移执行手册（服务器信息/Workbench 部署通道/migrate.sh 用法/实测坑/验证清单）
metadata:
  type: reference
---

# zero-cloud 云服迁移速查（S7.1 已建成并真实演练 2026-09-10）

**服务器**：阿里云 `203.0.113.10` · 实例 `i-bp16l4ohdfaopfsou4nw` · 2C4G Ubuntu22.04 · 部署目录 `/opt/zero-cloud` · 数据 `/data/zero`（~111MB）· MCP 入口 `https://IP:8443`（IP+自签+Android 指纹固定）。

**部署通道（Workbench CLI，勿用 SSH）**：
- 远程执行：`WB="C:\Users\USER\.workbench\workbench-windows-amd64\workbench.exe"` → `"$WB" exec -i i-bp16l4ohdfaopfsou4nw -c "命令"`；**单命令 30s 超时**——长命令 `nohup ... > /tmp/x.log 2>&1 &` + 轮询日志
- 上传**必须 PowerShell**：`powershell.exe -Command "& '$WB' upload 'D:\ZeroV2\zero-cloud\<f>' '/opt/zero-cloud/<dir>/' --instance-id i-... --force"`；**新目录先远程 `mkdir -p`**（上传通道不建目录，报 PathNoWritePermission）；路径含 `:` 勿用 bash 循环按冒号切分
- 复杂命令用 base64：本地写脚本 → `base64 -w0` → 远程 `echo <b64> | base64 -d > /tmp/x.sh && bash /tmp/x.sh`（绕引号地狱）

**迁移三步**（脚本 `zero-cloud/scripts/migrate.sh`，源码=权威）：
1. 源机：`bash scripts/migrate.sh export /tmp/bundle`（自动 quiesce 拒新→active=0→BGSAVE→打包 data/weclawbot会话/脱敏env模板/SHA256SUMS；服务中断仅秒级）
2. 包拷新机 + 代码目录就位 + **手工填 .env**（chmod 600，模板在包内）+ 安全组放行 8443
3. 新机：`bash scripts/migrate.sh import /tmp/bundle`（校验→RDB 注入 redis-data 卷（清 appendonlydir 保 RDB 优先）→数据解包→起栈→**清快照残留 quiesce**）
4. 验证：`bash scripts/test_mcp.sh` 回归 9 用例 ALL_PASS + tools/list=15 + `docker ps` 10 容器

**Android 收尾（零代码）**：MCP 设置→zero-cloud 条目「编辑」→改新 URL+新证书指纹（S7.2-rev，**Key 免重填**——apiKeyRef 稳定）；TrustConfig 双指纹过渡=旧指纹保留接受集，SSE/上传 URL 经 HOST 动态 getter 自动跟随。

**实测坑（演练抓出，已修入脚本/需知）**：
- celery active 检测必须 `inspect active --json` 求和——文本模式 `-> worker: OK` 头行恒计 2 永不归零
- 快照必含 `zero:quiesce` 标志（导出时序必然）→ 不清则导入新栈永久拒任务（import 已自动 DEL）
- `docker kill`=手动停止 **restart 策略不生效**（需 `docker start`）；真实 OOM 崩溃才自愈
- rebuild：`cd /opt/zero-cloud && nohup docker compose --profile full up -d --build <服务> > /tmp/b.log 2>&1 &`
- 微信扫码会话随包迁移（weclawbot/config），但换出口 IP 可能需重扫码

**Token/指纹**：`.env` 的 `ZERO_CLOUD_TOKEN`（docs 亦有但已计划轮换）；现役证书 SHA256 见 `ZeroCloudTrust.DEFAULT_CERT_SHA256`。相关：[[local-llm-node]]
