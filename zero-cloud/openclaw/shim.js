#!/usr/bin/env node
/**
 * oc-shim — openclaw 容器内轻量集成面（E1.2 · 落地链路=用户裁定 2026-09-19）。
 *
 * 定案链路（设计 §五 E1.2 状态列）：
 *   send: POST /send  → openclaw message send（direct-delivery RPC，CLI 封装）
 *   read: GET  /read  → agents/main/agent/openclaw-agent.sqlite transcript_events
 *                       严格只读（readOnly 连接，零写入）：seq > cursor ORDER BY seq ASC
 *                       → role=user 过滤（排除出站）→ idempotencyKey 去重 → next_cursor
 *   kick: 内建轮询器 → 新入站消息 POST zero-gateway /im/events → SSE type=im 秒推手机
 *
 * schema contract（裁定强制）：seq/role/timestamp/idempotencyKey/content 任一缺失
 *   → 明确失败（SCHEMA_CONTRACT_FAILED）并停下——不猜测、不自动迁移。
 *
 * 纪律：SQLite 只读不写（OpenClaw 权威转录事实源）；Zero 消费 cursor 持久化在 Redis（网关侧）。
 */
const http = require('http');
const { execFile } = require('child_process');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');

const PORT = parseInt(process.env.SHIM_PORT || '19980', 10);
const TOKEN = process.env.OPENCLAW_GATEWAY_TOKEN || '';
const DB_PATH = '/data/zero/openclaw/.openclaw/agents/main/agent/openclaw-agent.sqlite';
const GATEWAY_EVENTS_URL = process.env.GATEWAY_EVENTS_URL || 'http://gateway:8000/im/events';
const GATEWAY_TOKEN = process.env.ZERO_CLOUD_TOKEN || '';
const POLL_MS = parseInt(process.env.SHIM_POLL_MS || '20000', 10);

// systemSource 统一命名（裁定 9）
const CHANNEL_MAP = {
  'openclaw-weixin': 'WECHAT_PRIVATE',
  'wecom-openclaw-plugin': 'WECOM_PRIVATE',
  'qqbot': 'QQ_PRIVATE',
  'napcat': 'QQ_PRIVATE',   // E1.2-QQ：NapCat/OneBot v11（用户裁定参考 Alife）
  'onebot': 'QQ_PRIVATE',   // @kirigaya/openclaw-onebot 插件通道 id
};

// ═══ schema contract（裁定强制前置）═══
let contract = { ok: false, missing: [], checkedAt: 0 };

function runSchemaContractCheck() {
  const missing = [];
  try {
    const db = new DatabaseSync(DB_PATH, { readOnly: true });
    try {
      const cols = db.prepare("PRAGMA table_info(transcript_events)").all().map(c => c.name);
      for (const c of ['seq', 'event_json']) if (!cols.includes(c)) missing.push(`transcript_events.${c}`);
      if (!missing.length) {
        const row = db.prepare(
          "SELECT event_json FROM transcript_events WHERE event_json LIKE '%\"type\":\"message\"%' ORDER BY seq DESC LIMIT 1"
        ).get();
        if (row) {
          const e = JSON.parse(row.event_json);
          const m = e.message || {};
          for (const k of ['role', 'timestamp', 'content']) if (m[k] === undefined) missing.push(`message.${k}`);
          if (m.role === 'user' && m.idempotencyKey === undefined) missing.push('message.idempotencyKey');
        }
      }
    } finally { db.close(); }
  } catch (e) {
    missing.push(`EXCEPTION:${e.message.slice(0, 80)}`);
  }
  contract = { ok: missing.length === 0, missing, checkedAt: Date.now() };
  console.log(`[shim] schema contract: ${contract.ok ? 'OK' : 'FAILED missing=' + missing.join(',')}`, { flush: true });
  return contract;
}

// ═══ 严格只读增量读（定案链路）═══
function extractText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map(b => (b && (b.text || b.content)) || '').join('');
  return '';
}

// E1.2-媒体：入站媒体引用提取（__openclaw.media[]——插件已下载落盘的实体路径）。
// 语音例外：插件原生 ASR 已转文本、无媒体数组（用户实测裁定照用）。
function extractMedia(msg) {
  const arr = msg && msg.__openclaw && Array.isArray(msg.__openclaw.media)
    ? msg.__openclaw.media : [];
  return arr.map(md => ({
    path: md.path || '',
    mime: md.contentType || '',
    kind: md.kind || 'file',   // image / document / ...
  })).filter(md => md.path);
}

function readMessages(sinceSeq) {
  if (!contract.ok) {
    return { ok: false, code: 'SCHEMA_CONTRACT_FAILED', missing: contract.missing };
  }
  const db = new DatabaseSync(DB_PATH, { readOnly: true });
  try {
    const rows = db.prepare(`
      SELECT t.seq AS seq, t.event_json AS event_json, c.channel AS channel,
             c.account_id AS account_id, c.peer_id AS peer_id
      FROM transcript_events t
      LEFT JOIN session_conversations sc ON sc.session_id = t.session_id
      LEFT JOIN conversations c ON c.conversation_id = sc.conversation_id
      WHERE t.seq > ? ORDER BY t.seq ASC`).all(sinceSeq);
    const messages = [];
    const seen = new Set();
    let maxSeq = sinceSeq;
    for (const r of rows) {
      if (r.seq > maxSeq) maxSeq = r.seq;
      let e;
      try { e = JSON.parse(r.event_json); } catch { continue; }
      if (e.type !== 'message') continue;           // transcript ≠ 收件箱（裁定 3）
      const m = e.message || {};
      if (m.role !== 'user') continue;              // 排除 assistant/出站（裁定 4）
      if (m.idempotencyKey === undefined || m.content === undefined) {
        console.log('[shim] contract violation on row: seq=' + r.seq, { flush: true });
        continue;
      }
      if (seen.has(m.idempotencyKey)) continue;     // idempotencyKey 去重（裁定 2）
      seen.add(m.idempotencyKey);
      messages.push({
        seq: r.seq,
        channel: CHANNEL_MAP[r.channel] || r.channel || 'UNKNOWN',  // 裁定 5/9
        account_id: r.account_id || '',
        peer_id: r.peer_id || '',
        sender: r.peer_id || '',
        text: extractText(m.content),
        media: extractMedia(m),   // E1.2-媒体：[{path, mime, kind}]（无媒体=空数组）
        ts: m.timestamp,
        idempotency_key: m.idempotencyKey,
      });
    }
    return { ok: true, messages, next_cursor: maxSeq };
  } finally { db.close(); }
}

// ═══ CLI 封装 ═══
function runCli(args, timeoutMs = 60000) {
  return new Promise((resolve) => {
    execFile('openclaw', args, {
      timeout: timeoutMs,
      env: { ...process.env, OPENCLAW_STATE_DIR: '/data/zero/openclaw/.openclaw', OPENCLAW_CONFIG_PATH: '/data/zero/openclaw/.openclaw/openclaw.json' },
      maxBuffer: 4 * 1024 * 1024,
    }, (err, stdout, stderr) => {
      resolve({ err, stdout: (stdout || '') + (stderr || '') });
    });
  });
}

// ═══ SSE kick 轮询器（新入站 → 网关 /im/events → zero:events → 手机）═══
let kickCursor = null;  // 启动时置=当前 max seq（只 kick 新消息，不补历史）
function postKick(messages) {
  return new Promise((resolve) => {
    const body = JSON.stringify({ type: 'im', messages: messages.map(m => ({ channel: m.channel, seq: m.seq })) });
    const req = http.request(GATEWAY_EVENTS_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + GATEWAY_TOKEN },
      timeout: 8000,
    }, (res) => { res.resume(); resolve(res.statusCode === 200); });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.end(body);
  });
}

async function kickPoll() {
  try {
    const r = readMessages(kickCursor === null ? currentMaxSeq() : kickCursor);
    if (!r.ok) return;
    if (r.messages.length) {
      const okPushed = await postKick(r.messages);
      if (okPushed) console.log('[shim] kick pushed n=' + r.messages.length, { flush: true });
    }
    kickCursor = r.next_cursor;
  } catch (e) {
    console.log('[shim] kick poll error: ' + e.message.slice(0, 80), { flush: true });
  }
}
function currentMaxSeq() {
  try {
    const db = new DatabaseSync(DB_PATH, { readOnly: true });
    try {
      const r = db.prepare('SELECT COALESCE(MAX(seq), 0) AS m FROM transcript_events').get();
      return r ? r.m : 0;
    } finally { db.close(); }
  } catch { return 0; }
}

// ═══ HTTP 服务 ═══
function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(body);
}
function authed(req) {
  return TOKEN && req.headers.authorization === 'Bearer ' + TOKEN;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (!authed(req)) return json(res, 401, { ok: false, error: 'unauthorized' });

  if (url.pathname === '/health') {
    return json(res, 200, { ok: true, contract_ok: contract.ok, contract_missing: contract.missing, uptime_s: Math.round(process.uptime()) });
  }
  if (url.pathname === '/schema_check') {
    return json(res, 200, runSchemaContractCheck());
  }
  if (url.pathname === '/read' && req.method === 'GET') {
    const since = parseInt(url.searchParams.get('cursor') || '0', 10) || 0;
    if (!contract.ok) return json(res, 200, { ok: false, code: 'SCHEMA_CONTRACT_FAILED', missing: contract.missing });
    return json(res, 200, readMessages(since));
  }
  if (url.pathname === '/send' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 512 * 1024) req.destroy(); });
    req.on('end', async () => {
      let p; try { p = JSON.parse(body); } catch { return json(res, 400, { ok: false, error: 'bad json' }); }
      if (!p.target || !p.text) return json(res, 400, { ok: false, error: 'target/text required' });
      const args = ['message', 'send', '--target', String(p.target), '--message', String(p.text)];
      if (p.media) args.push('--media', String(p.media));
      const r = await runCli(args, 90000);
      const ok = !r.err && /✅ Sent|Message ID:/i.test(r.stdout);
      json(res, 200, { ok, message_id: (r.stdout.match(/Message ID:\s*(\S+)/) || [])[1] || null, out: r.stdout.slice(-300) });
    });
    return;
  }
  if (url.pathname === '/status' && req.method === 'GET') {
    const r = await runCli(['channels', 'status'], 30000);
    return json(res, 200, { ok: !r.err, text: r.stdout.slice(-1200) });
  }
  if (url.pathname === '/login' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 4096) req.destroy(); });
    req.on('end', async () => {
      let p; try { p = JSON.parse(body); } catch { return json(res, 400, { ok: false, error: 'bad json' }); }
      const cliChannel = Object.keys(CHANNEL_MAP).find(k => CHANNEL_MAP[k] === p.channel) || p.channel;
      const logFile = '/tmp/login-' + cliChannel + '.log';
      execFile('sh', ['-c', `rm -f ${logFile}; nohup openclaw channels login --channel ${cliChannel} > ${logFile} 2>&1 &`], { env: { ...process.env, OPENCLAW_STATE_DIR: '/data/zero/openclaw/.openclaw', OPENCLAW_CONFIG_PATH: '/data/zero/openclaw/.openclaw/openclaw.json' } }, () => {});
      await new Promise(r => setTimeout(r, 8000));
      const qr = await runCli(['sh', '-c', `grep -o 'https://liteapp.weixin.qq.com/q/[^[:space:]]*' ${logFile} 2>/dev/null | tail -1`], 8000);
      json(res, 200, { ok: true, qr_url: (qr.stdout || '').trim() || null, note: '扫码完成前每 60s 重取 /login_qr' });
    });
    return;
  }
  if (url.pathname === '/login_qr' && req.method === 'GET') {
    const cliChannel = url.searchParams.get('channel') || 'openclaw-weixin';
    const cliName = Object.keys(CHANNEL_MAP).find(k => CHANNEL_MAP[k] === cliChannel) || cliChannel;
    const r = await runCli(['sh', '-c', `grep -o 'https://liteapp.weixin.qq.com/q/[^[:space:]]*' /tmp/login-${cliName}.log 2>/dev/null | tail -1`], 8000);
    return json(res, 200, { ok: true, qr_url: (r.stdout || '').trim() || null });
  }
  json(res, 404, { ok: false, error: 'not found' });
});

server.listen(PORT, () => {
  console.log(`[shim] listening :${PORT}`, { flush: true });
  runSchemaContractCheck();
  setInterval(kickPoll, POLL_MS);
  setTimeout(kickPoll, 3000);
});
