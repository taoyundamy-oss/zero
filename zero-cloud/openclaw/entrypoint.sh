#!/bin/bash
# openclaw 容器入口（E1.1 · 云服架构演进设计 §2.1）
# 职责：首启按锁定版本装配 IM 统一网关栈（marker 防重装）→ 前台运行 openclaw gateway。
# 版本锁定（G11 · 2026-09-19 npm registry 实测核定）：
#   openclaw@2026.9.5                  —— 运行时（engines: node >=24.16 <25）
#   @tencent-weixin/openclaw-weixin@2.4.9       —— 微信（腾讯微信团队官方）
#   @wecom/wecom-openclaw-plugin@2026.9.15      —— 企业微信（腾讯官方）
#   @openclaw/qqbot@2026.7.1                    —— QQ（openclaw 官方）
# 扫码会话卷：HOME=/data/zero/openclaw → 凭据/会话落 /data/zero/openclaw/.openclaw（持久卷）
# 受阻纪律（设计 §一）：任何装配不通 → 日志留痕（docker logs zero-openclaw）→ 停下反馈，不私换方案。
set -e

export HOME=/data/zero/openclaw
STATE="$HOME/.openclaw"
MARKER="$STATE/.stack-2026.9.5"
mkdir -p "$STATE"

echo "[entrypoint] stack marker: $(cat "$MARKER" 2>/dev/null || echo 'not installed')"

# 装配判据：marker 缺失 **或** 二进制不在场（容器层易失——force-recreate 后 marker 残留但
# 全局安装随旧容器丢弃，2026-09-19 实测）。marker 仅为版本审计优化，二进制在场才是事实。
if [ ! -f "$MARKER" ] || ! command -v openclaw >/dev/null 2>&1; then
    echo "[entrypoint] installing pinned stack (first boot, 2-6 min)..."
    echo "[entrypoint] node=$(node -v) npm=$(npm -v)"
    # --no-audit/--no-fund：减小 npm 安装内存与时长（600m cgroup OOM 教训，mem_limit 已放宽 1200m 双保险）
    if ! npm install -g --no-audit --no-fund "openclaw@2026.9.5"; then
        echo "[entrypoint] npmjs install failed — retry via npmmirror"
        npm install -g --no-audit --no-fund "openclaw@2026.9.5" --registry=https://registry.npmmirror.com
    fi
    echo "[entrypoint] runtime installed: $(openclaw --version 2>&1 || echo 'VERSION_CHECK_FAILED')"

    # 三插件安装（逐个留痕；单插件失败不阻断其余——探针期分通道核查，E28 原则）
    for pkg in "@tencent-weixin/openclaw-weixin@2.4.9" \
               "@wecom/wecom-openclaw-plugin@2026.9.15" \
               "@openclaw/qqbot@2026.7.1"; do
        echo "[entrypoint] plugin install: $pkg"
        openclaw plugins install "$pkg" \
            || echo "[entrypoint] PLUGIN_INSTALL_FAILED: $pkg（留痕继续——探针期逐通道核查）"
    done

    # 插件启用（config set；失败留痕不阻断——可能需初始化交互，探针期核实）
    openclaw config set plugins.entries.openclaw-weixin.enabled true \
        || echo "[entrypoint] ENABLE_FAILED: openclaw-weixin"
    openclaw config set plugins.entries.wecom-openclaw-plugin.enabled true \
        || echo "[entrypoint] ENABLE_FAILED: wecom-openclaw-plugin"
    openclaw config set plugins.entries.qqbot.enabled true \
        || echo "[entrypoint] ENABLE_FAILED: qqbot"

    mkdir -p "$STATE"
    touch "$MARKER"
    echo "[entrypoint] stack installed OK"
fi

# 网关模式（容器=本地网关）：gateway 拒启缺 gateway.mode（2026.9.5 实测）——
# config set 幂等，每次启动前确保（不受 marker 门控）
openclaw config set gateway.mode local \
    || echo "[entrypoint] GATEWAY_MODE_SET_FAILED（留痕继续——探针期核查）"

echo "[entrypoint] launching openclaw gateway (foreground)..."

# oc-shim（E1.2 集成面：send CLI 封装 + transcript_events 严格只读增量读 + SSE kick 推送）
# 崩溃自动拉起（2s 退避）；gateway 为 pid 1 主进程
( while true; do
    node /opt/openclaw/shim.js >> /tmp/oc-shim.log 2>&1
    echo "[entrypoint] shim exited, restarting in 2s" >> /tmp/oc-shim.log
    sleep 2
  done ) &
echo "[entrypoint] oc-shim started (port 19980)"

# mute-model（E1.2 · 用户裁定"挂载简易本地小模型类"）：本地哑模型——OpenAI 兼容恒空回复。
# 内置 agent 以此为模型出口（agents.defaults.model.primary=mute/mute + models.providers.
# mute.localService 按需拉起——openclaw 官方本地模型服务托管）→ 管线绿色跑完但零出站
# （消灭 401 抢答文本）；transcript_events 照常写入（im_read 数据源完整）。
# entrypoint 不另起守护循环——生命周期归 localService 官方托管，避免双管理抢端口。
exec openclaw gateway
