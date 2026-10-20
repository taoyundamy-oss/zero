"""DeepSeek LLM 客户端（Worker 侧，httpx 同步）。

无 DEEPSEEK_API_KEY 时调用方应走降级路径（llm_available() 先判）。
Key 仅存服务器 .env（600 权限），不入代码库/文档。
"""
import os

import httpx

DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"


def llm_available() -> bool:
    return bool(DEEPSEEK_API_KEY)


def chat(system: str, user: str, max_tokens: int = 1000, temperature: float = 0.3) -> str:
    r = httpx.post(
        DEEPSEEK_URL,
        headers={"Authorization": f"Bearer {DEEPSEEK_API_KEY}"},
        json={
            "model": "deepseek-chat",
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "max_tokens": max_tokens,
            "temperature": temperature,
        },
        timeout=60,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]
