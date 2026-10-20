"""ImProvider — IM 统一网关抽象层（E1.1/E1.2 · D97 升级 · 云服架构演进设计 §2.1/§2.2）。

定案链路（用户裁定 2026-09-19，E1.2 恢复实施）：
  im_send → OpenClaw direct-delivery send（CLI 封装，实测可用）
  im_read → openclaw-agent.sqlite transcript_events 严格只读
            （seq>cursor ORDER BY seq ASC → role=user 过滤 → idempotencyKey 去重
            → next_cursor；SQLite 仅作 OpenClaw 权威转录事实源，Zero 不写）
  消费 cursor 持久化 Redis（im:cursor:{channel}）——SQLite 零写入。

传输面：oc-shim（openclaw 容器内集成面，:19980，Bearer OPENCLAW_GATEWAY_TOKEN）。
  shim schema contract：seq/role/timestamp/idempotencyKey/content 任一缺失 →
  SCHEMA_CONTRACT_FAILED 明确失败（不猜测/不迁移）。

systemSource 统一命名：WECHAT_PRIVATE / WECOM_PRIVATE / QQ_PRIVATE。
"""
import os
from typing import Optional

import runtime


# ═══ 抽象接口（通道感知——D97 升级点）═══

class ImProvider:
    """IM 底座抽象：三通道统一操作面。

    channel 取值对齐 S2R systemSource。方法返回 dict（ok 字段约定同 runtime.ok/fail）；
    实现必须 E28 留痕（失败不静默）。
    """

    async def status(self) -> dict:
        raise NotImplementedError

    async def login_start(self, channel: str) -> dict:
        raise NotImplementedError

    async def login_qr(self, channel: str) -> dict:
        raise NotImplementedError

    async def read(self, since: Optional[float] = None, channel: Optional[str] = None) -> dict:
        raise NotImplementedError

    async def send(self, channel: str, peer: str, text: str,
                   file_id: Optional[str] = None) -> dict:
        raise NotImplementedError


class ProviderNotReady(RuntimeError):
    """底座能力未就绪——工具层转 fail(code=NOT_READY)"""


# ═══ openclaw 底座实现（经 oc-shim）═══

class OpenclawImProvider(ImProvider):
    """openclaw 底座——经 oc-shim（容器内集成面）访问 send/read/status/login。"""

    def __init__(self, base_url: Optional[str] = None, token: Optional[str] = None):
        self._base = (base_url or os.getenv("OPENCLAW_SHIM_URL", "http://openclaw:19980")).rstrip("/")
        self._token = token or os.getenv("OPENCLAW_GATEWAY_TOKEN", "")

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self._token}"}

    async def _shim_get(self, path: str, timeout: float = 10.0) -> dict:
        r = await runtime.http.get(f"{self._base}{path}", headers=self._headers(), timeout=timeout)
        if r.status_code == 401:
            return {"ok": False, "error": "shim token 失配", "code": "AUTH_FAILED"}
        return r.json()

    async def _shim_post(self, path: str, payload: dict, timeout: float = 90.0) -> dict:
        r = await runtime.http.post(f"{self._base}{path}", headers=self._headers(),
                                    json=payload, timeout=timeout)
        if r.status_code == 401:
            return {"ok": False, "error": "shim token 失配", "code": "AUTH_FAILED"}
        return r.json()

    async def status(self) -> dict:
        """shim /health + /status（通道级登录态文本）——E28 失败显形。"""
        if not self._token:
            return {"ok": False, "error": "OPENCLAW_GATEWAY_TOKEN 未配置", "code": "NOT_CONFIGURED"}
        try:
            health = await self._shim_get("/health", timeout=6.0)
            if not health.get("ok"):
                return health
            if not health.get("contract_ok"):
                return {"ok": False, "code": "SCHEMA_CONTRACT_FAILED",
                        "missing": health.get("contract_missing", []),
                        "error": "transcript schema contract 未通过——按裁定停下"}
            st = await self._shim_get("/status", timeout=30.0)
            return {"ok": True, "channels_text": st.get("text", ""),
                    "note": "openclaw shim 可达，schema contract OK"}
        except Exception as e:
            print(f"[im_provider] OPENCLAW_UNREACHABLE: {type(e).__name__} {str(e)[:80]}", flush=True)
            return {"ok": False, "error": f"openclaw 不可达: {e}", "code": "UPSTREAM"}

    async def login_start(self, channel: str) -> dict:
        return await self._shim_post("/login", {"channel": channel}, timeout=30.0)

    async def login_qr(self, channel: str) -> dict:
        return await self._shim_get(f"/login_qr?channel={channel}", timeout=15.0)

    async def read(self, since: Optional[float] = None, channel: Optional[str] = None) -> dict:
        """严格只读增量：shim 查 transcript_events（seq>since ASC，role=user，幂等去重）。

        since=None → 用 0（全量由调用方/网关 cursor 语义决定）；channel 过滤在网关侧
        （shim 返回全通道，统一由 im_read 工具裁剪）。
        """
        cursor = int(since or 0)
        r = await self._shim_get(f"/read?cursor={cursor}", timeout=30.0)
        if not r.get("ok"):
            return r  # SCHEMA_CONTRACT_FAILED 等显式失败透传
        msgs = r.get("messages", [])
        if channel:
            msgs = [m for m in msgs if m.get("channel") == channel]
        return {"ok": True, "messages": msgs, "next_cursor": r.get("next_cursor", cursor)}

    async def send(self, channel: str, peer: str, text: str,
                   file_id: Optional[str] = None) -> dict:
        payload = {"target": peer, "text": text}
        if file_id:
            payload["media"] = file_id  # shim 透传 --media（卷内绝对路径）
        r = await self._shim_post("/send", payload)
        if r.get("ok"):
            r["channel"] = channel
        return r


# ═══ 注册表（换底座只改此行——D97 同律）═══

_provider: ImProvider = OpenclawImProvider()


def get_provider() -> ImProvider:
    return _provider
