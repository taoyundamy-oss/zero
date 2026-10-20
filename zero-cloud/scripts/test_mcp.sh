#!/bin/bash
# test_mcp.sh — MCP 回归套件 v2 · 10 用例（E1.3 终态：7 工具=im_*6+cloud_file_url）
# 每次 Gateway 改动/迁移后必跑；全过输出 ALL_PASS。
# 用法：bash scripts/test_mcp.sh [base_url]
#   缺省经 nginx 本机回环：https://localhost:8443/mcp?token=<.env 中的 TOKEN>
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TOKEN="$(grep -E '^ZERO_CLOUD_TOKEN=' "$HERE/.env" | cut -d= -f2)"
BASE="${1:-https://localhost:8443/mcp?token=$TOKEN}"
PASS=0; FAIL=0; FAILED=""

post() { curl -sk --max-time 25 -X POST "$BASE" -H 'Content-Type: application/json' "$@"; }

check() { # 用例名 + 作用于响应 JSON(d) 的 python 断言
  local name="$1" expr="$2"
  if echo "$R" | python3 -c "import sys,json;d=json.load(sys.stdin);sys.exit(0 if ($expr) else 1)" 2>/dev/null; then
    echo "PASS  $name"; PASS=$((PASS+1))
  else
    echo "FAIL  $name  → $(echo "$R" | head -c 160)"; FAIL=$((FAIL+1)); FAILED="$FAILED $name"
  fi
}

# 1. initialize
R=$(post -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"reg","version":"1"}}}')
check "initialize" "'serverInfo' in d.get('result',{})"

# 2. notifications/initialized（E3：可解析 JSON，非 204/空 body）
R=$(post -d '{"jsonrpc":"2.0","method":"notifications/initialized"}')
check "notifications/initialized(E3)" "'jsonrpc' in d"

# 3. tools/list：终态 7 工具（im_*6 + cloud_file_url），退役工具确证缺席
R=$(post -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
check "tools/list=7" "len(d['result']['tools'])==7"
R_NAMES="$R"
check "im-tools-present" "set(t['name'] for t in d['result']['tools']) >= {'im_status','im_login','im_read','im_send','im_config_get','im_config_set','cloud_file_url'}"
check "retired-absent" "'cloud_search' not in set(t['name'] for t in d['result']['tools'])"

# 4. tools/call im_status（openclaw shim 链路——success 即通）
R=$(post -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"im_status","arguments":{}}}')
check "tools/call(im_status)" "d['result']['content'][0]['text'].find('\"success\": true')>=0"

# 5. tools/call im_read（游标语义——next_cursor 字段在）
R=$(post -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"im_read","arguments":{}}}')
check "tools/call(im_read)" "'next_cursor' in d['result']['content'][0]['text']"

# 6. invalid params（im_send 缺 required → isError）
R=$(post -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"im_send","arguments":{}}}')
check "invalid-params" "d['result'].get('isError') is True"

# 7. unknown tool（isError，非 500/崩溃）
R=$(post -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}')
check "unknown-tool" "d['result'].get('isError') is True"

# 8. malformed JSON → -32700
R=$(post -d '{broken')
check "malformed-json" "d['error']['code']==-32700"

# 9. batch → -32600 可解析错误（不支持批量，不 500）
R=$(post -d '[{"jsonrpc":"2.0","id":7,"method":"ping"}]')
check "batch-rejected-json" "d.get('error',{}).get('code')==-32600"

# 10. 未知 notification → 可解析 JSON
R=$(post -d '{"jsonrpc":"2.0","method":"notifications/cancelled","params":{}}')
check "notification-json" "'jsonrpc' in d"

echo "──────────"
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL_PASS"; else echo "FAILED:$FAILED"; exit 1; fi
