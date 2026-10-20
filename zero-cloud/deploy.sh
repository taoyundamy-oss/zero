#!/bin/bash
# Zero Cloud 一键部署
# 前置：用户已注册 duckdns.org 并将域名指向服务器 IP
set -e

echo "═══ Zero Cloud 部署 ═══"

# 1. 检测 Docker
if ! docker --version >/dev/null 2>&1; then
    echo "❌ Docker 未安装。运行: curl -fsSL https://get.docker.com | sh"
    exit 1
fi
echo "✅ Docker $(docker --version)"

# 2. 检测 Port 80（Let's Encrypt 需要）
if ! curl -s http://localhost:80 >/dev/null 2>&1; then
    echo "⚠️  请确认安全组已开放 Port 80/443（Let's Encrypt 验证需要）"
fi

# 3. Prepare private credentials (idempotent; never print values)
python3 scripts/prepare_env.py

# 4. 创建数据目录
mkdir -p /data/zero/{downloads,results,tmp}
echo "✅ 数据目录就绪"

# 5. 启动 Docker Compose
PROFILE=${1:-core}
echo "▶ 启动 profile: $PROFILE"
docker compose --profile $PROFILE up -d --build

# 6. 等待服务就绪
echo "⏳ 等待服务启动..."
sleep 5

# 7. 验证
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8000/health)
if [ "$HEALTH" = "200" ]; then
    echo "✅ Gateway 健康检查通过"
else
    echo "⚠️  Gateway 健康检查: HTTP $HEALTH（检查 docker compose logs gateway）"
fi

echo ""
echo "═══ 部署完成 ═══"
echo "  MCP 端点: http://$(curl -s ifconfig.me):8000/mcp"
echo "  Token:    请在本机安全读取 .env；不会输出到部署日志"
echo ""
echo "下一步："
echo "  1. Zero 手机 → 设置 → MCP 服务器 → 添加"
echo "  2. URL: http://$(curl -s ifconfig.me):8000/mcp"
echo "  3. Key:  本机 .env 中的 ZERO_CLOUD_TOKEN（请勿公开）"
