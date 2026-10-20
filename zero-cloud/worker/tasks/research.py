"""tasks.research — 深度研究管线（C2.3 · Z11 分层，防一坨 Python）

  Planner（LLM 子查询扩展 / 无 key 降级单查询）
→ Search（SearXNG 多子查询并行）
→ Selector（URL 去重 + 同域≤3 + D106 L2 出口校验过滤）
→ Fetcher（Crawl4AI 批量全文抓取，E10：urls 列表 + markdown dict）
→ Evidence/Synthesis（LLM 交叉验证报告 / 无 key 抽取式摘录降级）

大结果落 /data/zero/results/{task_id}.json（result_ref），Redis 只存摘要+引用（Z5）。
RETRYABLE（网络类）→ max_retries=1（§七）；软超时 240s。
"""
import json
import os
import re
import time
from urllib.parse import urlsplit

import httpx
from celery.exceptions import SoftTimeLimitExceeded

import llm
from celery_app import app, save_big_result, update_meta
from ssrf import validate_url

SEARXNG_URL = os.getenv("SEARXNG_URL", "http://searxng:8080")
CRAWL4AI_URL = os.getenv("CRAWL4AI_URL", "http://crawl4ai:11235")
CRAWL4AI_TOKEN = os.getenv("CRAWL4AI_TOKEN", "")

MAX_SOURCES = 15
FETCH_CHUNK = 5


# ── ① Planner ─────────────────────────────────────────────
def plan_subqueries(query: str) -> list:
    if not llm.llm_available():
        return [query]
    try:
        out = llm.chat(
            "你是搜索查询规划器。",
            f"把研究问题拆成 3 个互补的中文搜索查询（不同角度）。仅输出 JSON 字符串数组，无其他文字。\n问题：{query}",
            max_tokens=200,
        )
        m = re.search(r"\[.*?\]", out, re.S)
        subs = json.loads(m.group(0)) if m else []
        subs = [s.strip() for s in subs if isinstance(s, str) and s.strip()]
        return ([query] + subs)[:4]
    except Exception:
        return [query]


# ── ② Search ──────────────────────────────────────────────
def _searx(q: str, attempt: int = 0) -> list:
    try:
        r = httpx.get(
            f"{SEARXNG_URL}/search",
            params={"q": q, "format": "json", "safesearch": "0"},  # 不指定 engines——用配置白名单（E16）
            timeout=25,
        )
        return r.json().get("results", [])[:12]
    except Exception:
        return []  # 单子查询失败不拖垮整轮


def search_all(subqueries: list) -> list:
    # 串行 + 间隔——降低对上游引擎的瞬时压力（sogou 因连发触发 CAPTCHA，E16）
    results = []
    for i, q in enumerate(subqueries[:3]):
        if i:
            time.sleep(1.5)
        results.extend(_searx(q))
    return results


# ── ③ Selector ────────────────────────────────────────────
def select(results: list, max_n: int = MAX_SOURCES) -> list:
    seen, per_domain, out = set(), {}, []
    for item in results:
        url = (item.get("url") or "").strip()
        if not url:
            continue
        dom = urlsplit(url).netloc
        if url in seen or per_domain.get(dom, 0) >= 3:
            continue
        if not validate_url(url)[0]:  # D106 L2：出口前过滤
            continue
        seen.add(url)
        per_domain[dom] = per_domain.get(dom, 0) + 1
        out.append({
            "url": url,
            "title": (item.get("title") or "")[:120],
            "snippet": (item.get("content") or "")[:200],
        })
        if len(out) >= max_n:
            break
    return out


# ── ④ Fetcher ─────────────────────────────────────────────
def fetch_pages(urls: list) -> dict:
    headers = {"Authorization": f"Bearer {CRAWL4AI_TOKEN}"} if CRAWL4AI_TOKEN else None
    pages = {}
    for i in range(0, len(urls), FETCH_CHUNK):
        chunk = urls[i:i + FETCH_CHUNK]
        try:
            r = httpx.post(f"{CRAWL4AI_URL}/crawl", json={"urls": chunk}, headers=headers, timeout=90)
            data = r.json()
        except Exception:
            continue
        for item in data.get("results") or []:
            u, md = item.get("url"), item.get("markdown")
            if isinstance(md, dict):  # E10
                md = md.get("raw_markdown") or md.get("fit_markdown") or md.get("markdown_with_citations") or ""
            if u and md:
                pages[u] = md[:8000]
    return pages


# ── ⑤ Evidence / Synthesis ────────────────────────────────
def synthesize(query: str, sources: list, pages: dict) -> dict:
    corpus = "\n\n".join(
        f"[{i + 1}] {s['title']}\nURL: {s['url']}\n{(pages.get(s['url']) or s.get('snippet', ''))[:3000]}"
        for i, s in enumerate(sources[:10])
    )
    if llm.llm_available():
        try:
            report = llm.chat(
                "你是严谨的研究助理。仅基于给定资料回答，观点标注来源序号[n]；资料不足处明确说明，不编造。",
                f"研究问题：{query}\n\n资料：\n{corpus}\n\n"
                "输出 Markdown：\n## 结论摘要\n## 要点（带[n]引用）\n## 分歧与缺口",
                max_tokens=1500,
            )
            return {"mode": "llm", "report": report}
        except Exception:
            pass
    lines = [
        f"### {s['title']}\n来源：{s['url']}\n摘录：{(pages.get(s['url']) or s['snippet'])[:300]}…"
        for s in sources[:10]
    ]
    return {"mode": "extractive", "report": "（LLM 不可用——逐源摘录模式）\n\n" + "\n\n".join(lines)}


# ── 任务入口 ───────────────────────────────────────────────
@app.task(name="tasks.research", bind=True, max_retries=1, soft_time_limit=240, time_limit=280)
def research(self, query: str, max_sources: int = MAX_SOURCES):
    task_id = self.request.id
    started = int(time.time())
    try:
        update_meta(task_id, status="running", stage="planner", started_at=started,
                    retry_count=self.request.retries)
        subs = plan_subqueries(query)

        update_meta(task_id, stage="search")
        raw = search_all(subs)
        if not raw:
            # 诚实失败优于空转成功（E16）：搜索引擎无结果时明确报错
            update_meta(task_id, status="failed", stage="search",
                        error="搜索引擎无返回（上游引擎限流或网络异常），请稍后重试",
                        finished_at=int(time.time()))
            return None

        update_meta(task_id, stage="select")
        sources = select(raw, min(max(int(max_sources), 3), 20))
        if not sources:
            update_meta(task_id, status="failed", stage="select",
                        error="候选 URL 全部被过滤（SSRF 校验或去重），请调整查询词重试",
                        finished_at=int(time.time()))
            return None

        update_meta(task_id, stage=f"fetch({len(sources)}源)")
        pages = fetch_pages([s["url"] for s in sources])

        update_meta(task_id, stage="synthesis")
        synth = synthesize(query, sources, pages)

        full = {
            "query": query,
            "subqueries": subs,
            "sources": [{**s, "fetched_chars": len(pages.get(s["url"], ""))} for s in sources],
            "stats": {
                "subqueries": len(subs), "candidates": len(raw),
                "selected": len(sources), "fetched": len(pages),
                "duration_s": int(time.time()) - started,
            },
            **synth,
        }
        ref = save_big_result(task_id, full)
        result = {
            "result_ref": ref,
            "mode": synth["mode"],
            "summary": {
                "sources": len(sources), "fetched": len(pages),
                "report_head": synth["report"][:600],
            },
            "duration_s": int(time.time()) - started,
        }
        update_meta(task_id, status="done", stage="done", finished_at=int(time.time()), result=result)
        return result
    except SoftTimeLimitExceeded:
        update_meta(task_id, status="failed", error="研究超时（240s 软限制）",
                    finished_at=int(time.time()))
        raise
    except Exception as e:
        if self.request.retries < 1:
            update_meta(task_id, status="retrying", retry_count=self.request.retries + 1,
                        last_error=str(e)[:200])
            raise self.retry(exc=e, countdown=10)
        update_meta(task_id, status="failed", error=str(e)[:300], finished_at=int(time.time()))
        raise
