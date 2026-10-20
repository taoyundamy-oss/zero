#!/usr/bin/env node
/**
 * mute-model — 本地哑模型（E1.2 · 用户裁定"挂载简易本地小模型类，指定仅传输消息"）。
 *
 * 角色：OpenClaw 内置 agent 的模型出口（agents.defaults.model.primary = mute/mute）。
 * 入站微信 → openclaw agent 跑本模型 → 恒空回复 → openclaw 不投递任何内容到微信
 * → 消灭 401 抢答文本；transcript_events 照常写入（role=user 入站记录完整），
 *   Zero 的 im_read 数据源不受影响。OpenClaw = IM transport，Zero = 唯一 Agent。
 *
 * 协议：OpenAI 兼容（POST /v1/chat/completions 支持 stream 与非 stream；
 * GET /v1/models 供 catalog/健康探测）。
 */
const http = require('http');

const PORT = parseInt(process.env.MUTE_PORT || '19981', 10);
const MODEL_ID = 'mute';
// OpenClaw 官方静默标记（SILENT_REPLY_TOKEN）：reply content 恰为 NO_REPLY 时
// parseReplyDirectives().isSilent=true → hasExplicitSilentAssistantReply=true →
// 兜底错误文案不投递（实测源码 builtin-openclaw: return null 分支）。空字符串不行
// （空回复触发 "Agent couldn't generate a response" 兜底投递——2026-09-20 实测）。
const SILENT = 'NO_REPLY';

function emptyChoice() {
  return {
    index: 0,
    message: { role: 'assistant', content: SILENT },
    finish_reason: 'stop',
  };
}

function sse(res, obj) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.write(`data: ${JSON.stringify(obj)}\n\n`);
  res.write('data: [DONE]\n\n');
  res.end();
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url.startsWith('/v1/models')) {
    return json(res, {
      object: 'list',
      data: [{ id: MODEL_ID, object: 'model', owned_by: 'zero-mute' }],
    });
  }
  if (req.method === 'POST' && req.url.startsWith('/v1/chat/completions')) {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 2 * 1024 * 1024) req.destroy(); });
    req.on('end', () => {
      let stream = false;
      let model = MODEL_ID;
      try {
        const p = JSON.parse(body || '{}');
        stream = p.stream === true;
        if (p.model) model = p.model;
      } catch { /* 按非流式空回复兜底 */ }
      const id = `chatcmpl-mute-${Date.now()}`;
      const created = Math.floor(Date.now() / 1000);
      if (stream) {
        return sse(res, {
          id, object: 'chat.completion.chunk', created, model,
          choices: [{ index: 0, delta: { role: 'assistant', content: SILENT }, finish_reason: 'stop' }],
        });
      }
      return json(res, {
        id, object: 'chat.completion', created, model,
        choices: [emptyChoice()],
        usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
      });
    });
    return;
  }
  json(res, 404, { error: { message: 'not found' } });
});

function json(res, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(body);
}

server.listen(PORT, () => {
  console.log(`[mute-model] listening :${PORT} (model=${MODEL_ID}, always-empty)`, { flush: true });
});
