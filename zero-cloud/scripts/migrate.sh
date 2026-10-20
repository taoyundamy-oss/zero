#!/bin/bash
# migrate.sh — zero-cloud 迁移 v2（E1.3 后新栈：gateway/openclaw/nginx/redis 四容器）
#
# 状态全集：Redis（RDB）+ /data/zero 卷（含 openclaw 态：微信扫码会话/转录库/插件装配/
#   收件箱文件）+ .env 模板——compose 服务本身无状态（openclaw 容器层 npm 装配由
#   entrypoint 首启自动重装，不入迁移包）。
#
# 用法：
#   bash scripts/migrate.sh export [输出目录]   # 源服务器：停openclaw→BGSAVE→打包→恢复
#   bash scripts/migrate.sh verify <包目录>     # 任意机器：checksum 校验
#   bash scripts/migrate.sh import <包目录>     # 新服务器：校验→RDB 注入→解包→起栈
#
# export 语义（G8 一致性切片 · v2 修订 2026-09-20）：
#   ① 停 openclaw 容器——转录库 sqlite 打包一致性（微信消息不会 mid-write 入 tar）
#   ② Redis BGSAVE 完成后 RDB 与 tar 同刻打包
#   ③ 失败/中断 trap 兜底重启 openclaw（服务恢复优先）
#   （v1 的 quiesce/celery 等静止步已随 worker 退役删除——新栈无异步任务面）
# import 语义：fresh redis 卷注入 dump.rdb（清 appendonlydir 保 RDB 优先）→ tar 解包
#   → compose --profile core --profile im 起栈（openclaw 首启 npm 重装 2-6 分钟属预期）
set -euo pipefail

COMPOSE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="${DATA_DIR:-/data/zero}"
REDIS="zero-redis"
OPENCLAW="zero-openclaw"
TS="$(date +%Y%m%d-%H%M%S)"

die() { echo "ERROR: $*" >&2; exit 1; }

ensure_openclaw_up() {
  # 兜底：导出中断也把 openclaw 拉回（服务可用性优先于迁移成败）
  if ! docker ps --format '{{.Names}}' | grep -q "^${OPENCLAW}$"; then
    docker start "$OPENCLAW" >/dev/null 2>&1 || true
  fi
}

wait_bgsave_done() {
  for _ in $(seq 1 120); do
    inprog=$(docker exec "$REDIS" redis-cli INFO persistence 2>/dev/null \
      | grep '^rdb_bgsave_in_progress' | tr -d '\r' | cut -d: -f2)
    [ "${inprog:-1}" = "0" ] && return 0
    sleep 1
  done
  die "BGSAVE 120s 未完成"
}

cmd_export() {
  local out="${1:-./zero-migrate-$TS}"
  [ -d "$out" ] && die "输出目录已存在: $out"
  mkdir -p "$out"
  trap 'ensure_openclaw_up' EXIT
  echo "[1/5] 停 openclaw（转录 sqlite 一致性窗口）"
  docker stop "$OPENCLAW" >/dev/null
  echo "[2/5] Redis BGSAVE"
  docker exec "$REDIS" redis-cli BGSAVE >/dev/null
  wait_bgsave_done
  echo "[3/5] 打包 $DATA_DIR（含 openclaw 态：微信会话/转录/收件箱）"
  tar -czf "$out/data.tar.gz" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"
  docker cp "$REDIS:/data/dump.rdb" "$out/redis-dump.rdb"
  echo "[4/5] 恢复 openclaw"
  docker start "$OPENCLAW" >/dev/null
  trap - EXIT
  echo "[5/5] manifest + checksum"
  # .env 模板（值脱敏——真实值留源机 600 权限，目标机手工填充）
  sed -E 's/=.*/=<FILL_ME>/' "$COMPOSE_DIR/.env" > "$out/env.template" 2>/dev/null || true
  ( cd "$out" && sha256sum data.tar.gz redis-dump.rdb env.template > SHA256SUMS 2>/dev/null \
      || sha256sum data.tar.gz redis-dump.rdb > SHA256SUMS )
  cat > "$out/MANIFEST.txt" <<EOF
zero-cloud 迁移包 $TS（v2 · 四容器栈）
源主机: $(hostname) / $(hostname -I 2>/dev/null | awk '{print $1}')
内容:
  data.tar.gz    $DATA_DIR 全量（openclaw 微信会话+转录库 / 收件箱 / im 游标在 Redis）
  redis-dump.rdb Redis RDB（im:cursor / im:config / zero:file:* 文件登记）
  env.template   .env 键清单（值脱敏——目标机填充真实值，chmod 600）
一致性: openclaw 停机窗口 + BGSAVE 同刻快照（微信通道中断约 30-60s）
导入: bash scripts/migrate.sh import <本目录>
注意: 换出口 IP 后微信可能要求重扫码（iLink IP 亲和性）——导入后 im_status 验证
EOF
  echo "✅ 导出完成: $out（$(du -sh "$out" | cut -f1)）——服务已恢复"
}

cmd_verify() {
  local dir="${1:?用法: migrate.sh verify <包目录>}"
  [ -d "$dir" ] || die "包目录不存在: $dir"
  cd "$dir"
  [ -f SHA256SUMS ] || die "缺 SHA256SUMS"
  sha256sum -c SHA256SUMS
  for f in data.tar.gz redis-dump.rdb; do
    [ -s "$f" ] || die "缺关键文件: $f"
  done
  echo "✅ 校验通过（$(ls | tr '\n' ' ')）"
}

cmd_import() {
  local dir="${1:?用法: migrate.sh import <包目录>}"
  cmd_verify "$dir"
  cd "$COMPOSE_DIR"
  echo "[1/4] compose up redis（建卷后停，注入 RDB）"
  docker compose --profile core up -d redis
  sleep 3
  docker compose stop redis
  vol=$(docker volume inspect "${COMPOSE_PROJECT_NAME:-zero-cloud}_redis-data" 2>/dev/null \
    | grep Mountpoint | awk '{print $2}' | tr -d '",' || true)
  [ -n "$vol" ] || vol=$(docker inspect "$REDIS" 2>/dev/null | grep -A3 redis-data \
    | grep Mountpoint | head -1 | awk '{print $2}' | tr -d ',"')
  [ -n "$vol" ] || die "无法定位 redis-data 卷路径"
  echo "  卷路径: $vol"
  cp "$dir/redis-dump.rdb" "$vol/dump.rdb"
  rm -rf "$vol/appendonlydir"   # 无 AOF 时 RDB 优先载入（载入后 AOF 由 redis 重建）
  echo "[2/4] 解包 $DATA_DIR"
  tar -xzf "$dir/data.tar.gz" -C "$(dirname "$DATA_DIR")"
  echo "[3/4] 检查 .env（值需手工填充）"
  if [ -f "$dir/env.template" ] && [ ! -f "$COMPOSE_DIR/.env" ]; then
    cp "$dir/env.template" "$COMPOSE_DIR/.env"
    echo "  ⚠️ .env 已从模板创建——必须填充真实值（chmod 600）后再继续"
    exit 2
  fi
  echo "[4/4] compose 起栈（core+im——openclaw 首启 npm 重装 2-6 分钟属预期）"
  docker compose --profile core --profile im up -d
  # v1 包兼容：老快照可能带 zero:quiesce 残留（导出时序）——起栈后清除
  sleep 2
  docker exec "$REDIS" redis-cli DEL zero:quiesce >/dev/null 2>&1 || true
  echo "✅ 导入完成——等 openclaw gateway ready（docker logs zero-openclaw | grep ready）"
  echo "   验证: bash scripts/test_mcp.sh（10 用例）+ im_status 微信通道"
  echo "   Android 侧：MCP 条目改新 URL+新证书指纹（编辑条目，Key 免重填）"
}

case "${1:-}" in
  export) shift; cmd_export "$@" ;;
  verify) shift; cmd_verify "$@" ;;
  import) shift; cmd_import "$@" ;;
  *) echo "用法: migrate.sh {export|verify|import} [目录]"; exit 1 ;;
esac
