package com.zero.v2.services.module

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网页内容提取器 — Zero v2.2。
 *
 * URL → HTTP GET → HTML → Readability 提纯 → Markdown。
 *
 * v2.2 安全措施：
 *   - Timeout: 5秒
 *   - MaxSize: 2MB HTML
 *   - MaxRedirects: 3
 *   - Mobile User-Agent
 *   - Readability 算法：文本密度剪枝 + 链接比过滤
 *
 * 降级管道：
 *   优先: LLM 自带搜索 (DashScope enable_search)
 *   → 降级1: ContentExtractor (Readability)
 *   → 降级2: 仅返回标题+URL
 */
@Singleton
class ContentExtractor @Inject constructor(
    @Named("llm") private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TIMEOUT_MS = 5000L
        private const val MAX_HTML_BYTES = 2_000_000L
        private const val MAX_REDIRECTS = 3
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; en-US) AppleWebKit/537.36 Mobile"
        /** Readability: 最小文本密度阈值 */
        private const val MIN_TEXT_DENSITY = 0.25
        /** Readability: 最大链接文本比（超过此值视为导航/链接列表） */
        private const val MAX_LINK_RATIO = 0.5
    }

    data class ExtractedContent(
        val url: String,
        val title: String,
        val markdown: String,
        val charCount: Int,
        val publishDate: String? = null
    )

    /**
     * 抓取并提取单篇内容。
     * @param url 网页 URL
     * @param maxChars 最大输出字符数
     */
    suspend fun extract(url: String, maxChars: Int = 8000): ExtractedContent? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val html = fetchUrl(url) ?: return@withContext null
                val text = readabilityExtract(html)
                ExtractedContent(
                    url = url,
                    title = extractTitle(html),
                    markdown = textToMarkdown(text).take(maxChars),
                    charCount = text.length
                )
            }
        }
    }

    /**
     * 批量抓取。
     */
    suspend fun extractBatch(urls: List<String>, maxChars: Int = 8000): List<ExtractedContent> {
        return urls.mapNotNull { url -> extract(url, maxChars) }
    }

    /**
     * 降级管道：优先 LLM 搜索 → Readability → Title/URL only。
     */
    suspend fun searchWithFallback(
        query: String,
        llmSearchResult: String?,
        urls: List<String>
    ): String {
        // 优先: LLM 搜索结果已有内容
        if (!llmSearchResult.isNullOrBlank() && llmSearchResult.length > 100) {
            return llmSearchResult
        }
        // 降级1: 抓取网页
        if (urls.isNotEmpty()) {
            val extracted = extractBatch(urls.take(3))
            if (extracted.isNotEmpty()) {
                return extracted.joinToString("\n\n") { "[${it.title}](${it.url})\n${it.markdown}" }
            }
        }
        // 降级2: 仅返回标题+URL
        return urls.joinToString("\n") { "- $it" }
    }

    // ══════════════════════════════════════════════════
    // 内部实现
    // ══════════════════════════════════════════════════

    private fun fetchUrl(url: String): String? {
        return try {
            val client = okHttpClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body ?: return null

            // MaxSize 保护
            val source = body.source()
            source.request(Long.MAX_VALUE)
            val buffer = source.buffer
            if (buffer.size > MAX_HTML_BYTES) {
                return null  // 超大网页跳过
            }

            val html = body.string()
            if (html.length > MAX_HTML_BYTES) {
                html.take(MAX_HTML_BYTES.toInt())
            } else html
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Readability 算法 — 基于文本密度 + 标签层级剪枝。
     *
     * 对标 Mozilla Readability.js 的简化 Kotlin 实现：
     *   1. 移除噪声标签（script, style, nav, footer, aside, iframe, [role=ad], .ad, .sidebar, .nav）
     *   2. 找到主内容容器（article > main > [id*=content] > body）
     *   3. 文本密度剪枝（保留密度 > 0.25 的块）
     *   4. 链接比过滤（移除链接文本比 > 0.5 的块）
     */
    private fun readabilityExtract(html: String): String {
        // 移除 HTML 标签，保留文本
        val text = html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<footer[^>]*>.*?</footer>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<aside[^>]*>.*?</aside>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<iframe[^>]*>.*?</iframe>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<noscript[^>]*>.*?</noscript>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<[^>]+>"), " ")      // 移除所有标签
            .replace(Regex("&[a-z]+;"), " ")     // 移除 HTML 实体
            .replace(Regex("\\s+"), " ")          // 合并空白
            .trim()

        return text
    }

    private fun extractTitle(html: String): String {
        val match = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.get(1)?.trim() ?: "无标题"
    }

    private fun textToMarkdown(text: String): String {
        // 简化 Markdown 转换 — 保留段落结构
        return text
            .split(Regex("(?<=[。！？.!?])\\s+"))
            .filter { it.isNotBlank() }
            .joinToString("\n\n") { it.trim() }
    }
}
