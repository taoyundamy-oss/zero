package com.zero.v2.app.data

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.mcp.ZeroCloudTrust
import com.zero.v2.services.security.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** WebFetch 模块配置 — Step 8.1（D57：文件主权护栏参数化） */
class WebFetchConfig : ModuleConfigBase() {
    @ConfigField(label = "单文件下载上限 (MB)", fieldType = ConfigFieldType.NUMBER,
        min = 1.0, max = 2048.0,
        description = "download_file 落盘工作目录的单文件大小上限（绝对硬上限 2GB——防磁盘占满）")
    var maxDownloadMB: Int = 500

    @ConfigField(label = "单文件上传上限 (MB)", fieldType = ConfigFieldType.NUMBER,
        min = 1.0, max = 2048.0,
        description = "upload_file 上传到云服的单文件大小上限（默认 100MB，服务器硬顶 2048MB）。" +
                "云端文件 24 小时后自动过期")
    var uploadMaxMB: Int = 100

    /**
     * Jina Reader Key 条目名（Secret/Config 分离；可选——匿名 20 RPM，免费 Key 200 RPM）。
     * 走模块详情页 API Keys 卡片。
     */
    var jinaApiKeyRef: String = "webfetch:jina"

    override fun toJson(): String =
        """{"maxDownloadMB":$maxDownloadMB,"uploadMaxMB":$uploadMaxMB,"jinaApiKeyRef":"$jinaApiKeyRef"}"""

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            // D51 钳位：恢复路径与 UI 同界（防直改 JSON 越界）
            maxDownloadMB = obj.optInt("maxDownloadMB", 500).coerceIn(1, 2048)
            uploadMaxMB = obj.optInt("uploadMaxMB", 100).coerceIn(1, 2048)
            jinaApiKeyRef = obj.optString("jinaApiKeyRef", "webfetch:jina")
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = WebFetchConfig()
}

/**
 * 网络资源获取模块 — Step 8.1（D57：浏览器操控改判为"下载 + 爬取"直接方案）。
 *
 * FC download_file：OkHttp 流式下载 → 固定落盘工作目录 downloads/ 子目录
 * （fileName 仅相对名 + 净化——LLM 无路径自由度，D57 文件主权三原则）。
 * FC fetch_webpage：双引擎降级——本地 jsoup（快/隐私/静态页）→ 正文过短（<200 字，疑似
 * JS 渲染页）→ Jina Reader（r.jina.ai 服务端渲染，Key 可选）→ 失败文案降级 web_search。
 *
 * 设计偏差记录：registry 不支持按调用参数分级权限 → 危险类型（apk/so/dex/jar）
 * 硬拦截（比 CONFIRM 更保守，ROADMAP 的"危险类型 CONFIRM"以拦截实现）。
 * 仅 HTTPS（targetSdk 35 明文限制）。
 */
@Singleton
class WebFetchModule @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val workDir: WorkDirRepository,

    private val secureKeyStore: CredentialStore,
    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    companion object {
        private const val TRACE_ID = "web_fetch"
        private const val DOWNLOAD_SUBDIR = "downloads"
        /** 危险类型硬拦截（设计偏差：per-call CONFIRM 不支持，拦截 > 无提示放行） */
        private val DANGEROUS_EXT = setOf("apk", "so", "dex", "jar")
        /** 网页正文读取上限（防超大页面撑爆内存/上下文） */
        private const val MAX_HTML_BYTES = 5L * 1024 * 1024
        /** 返回给 LLM 的正文/链接/图片截断 */
        private const val BODY_CAP = 8000
        private const val LINK_CAP = 30
        private const val IMG_CAP = 20
        /** 低于此长度疑似 JS 渲染页 → 切 Jina */
        private const val JS_PAGE_SUSPECT_LEN = 200
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        /** MIME → 扩展名（fileName 无扩展名时补全） */
        private val MIME_EXT = mapOf(
            "application/pdf" to "pdf", "image/jpeg" to "jpg", "image/png" to "png",
            "image/webp" to "webp", "image/gif" to "gif", "application/zip" to "zip",
            "audio/mpeg" to "mp3", "video/mp4" to "mp4", "text/html" to "html",
            "text/plain" to "txt", "application/json" to "json",
            "application/msword" to "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx")
    }

    override val metadata = ModuleMetadata(
        id = "web_fetch",
        displayName = "网络资源获取",
        description = "文件下载（落盘工作目录 downloads/）+ 网页抓取双引擎（jsoup + Jina Reader）",
        category = ModuleCategory.INSTRUMENT
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = WebFetchConfig()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)  // 大文件流式下载
            .build()
    }

    // C3.3：zero-cloud 文件下载（/files/{id} 为 IP 直连自签证书）——该 host 走指纹 pin 信任
    // （ZeroCloudTrust，McpTransport 同源）；其他 https 走系统 CA 不变。
    // C3.4：/files 全路径经 auth 中间件（Bearer/?token）——此客户端自动携带 MCP token
    // （SecureKeyStore "mcp_server:zero-cloud"，C0.3 存储位；download_file/upload_file 共用）。
    private val pinnedHttp: OkHttpClient by lazy {
        ZeroCloudTrust.install(
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val token = secureKeyStore.getOrFallbackString("mcp_server:zero-cloud", null)
                    val req = if (!token.isNullOrBlank())
                        chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                    else chain.request()
                    chain.proceed(req)
                }
        ).build()
    }

    private fun clientFor(url: String): OkHttpClient =
        if (ZeroCloudTrust.isPinned(url)) pinnedHttp else http

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE

    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition(
                name = "download_file",
                description = "下载网络文件到用户工作目录的 downloads/ 子目录（文件名自动净化，仅限 https）。" +
                        "适用：下载搜索结果中的 PDF/文档/图片/压缩包等资源。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf("type" to "string", "description" to "文件的完整 https 链接"),
                        "file_name" to mapOf("type" to "string", "description" to "保存文件名（可选，仅文件名不含路径；缺省取 URL 末段）")
                    ),
                    "required" to listOf("url")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val url = args["url"] as? String ?: ""
            val fileName = args["file_name"] as? String
            downloadFile(url, fileName)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "upload_file",
                description = "上传工作目录文件到云服，返回云端 file_id（24 小时有效）。" +
                        "用途：把本地文档交给云端工具处理——如 cloud_file_convert 转 PDF、" +
                        "cloud_file_process 提取/拆分。上传后把 file_id 告知云端工具即可。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file_name" to mapOf("type" to "string", "description" to "工作目录中的文件名（含扩展名，如 report.docx）")
                    ),
                    "required" to listOf("file_name")
                )
            ), metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val fileName = args["file_name"] as? String ?: ""
            uploadFile(fileName)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "fetch_webpage",
                description = "抓取网页内容（标题/正文/链接清单/图片清单，供后续下载或分析）。" +
                        "静态页本地解析，JS 动态页自动切换 Jina Reader 服务端渲染；失败时建议改用 web_search。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf("type" to "string", "description" to "网页完整 https 链接")
                    ),
                    "required" to listOf("url")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val url = args["url"] as? String ?: ""
            fetchWebpage(url)
        }
    }

    // ═══ download_file ═══

    private suspend fun downloadFile(url: String, rawName: String?): FunctionResult =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) {
                return@withContext FunctionResult.failure("仅支持 https 链接（http 明文被系统安全策略禁止）: ${url.take(60)}")
            }
            var fileName = sanitizeName(rawName ?: url.substringAfterLast('/'))
            try {
                val response = clientFor(url).newCall(
                    Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
                ).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext FunctionResult.failure("下载失败: HTTP ${resp.code}")
                    }
                    val mime = (resp.header("Content-Type") ?: "").substringBefore(';').trim()
                    // 无扩展名 → 按 MIME 补全
                    if (!fileName.contains('.')) {
                        MIME_EXT[mime]?.let { fileName = "$fileName.$it" }
                    }
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    // C3.4b：zero-cloud（自有云，用户自传文件）豁免危险类型拦截；
                    // 任意 web URL 维持硬拦截（D57——防来历不明可执行文件）
                    if (ext in DANGEROUS_EXT && !ZeroCloudTrust.isPinned(url)) {
                        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                            "download_blocked: 危险类型 .$ext url=${url.take(60)}")
                        return@withContext FunctionResult.failure(
                            "已拦截危险文件类型（.$ext）——出于安全不自动下载可执行文件，请引导用户手动处理")
                    }
                    val maxBytes = (config as WebFetchConfig).maxDownloadMB.coerceIn(1, 4096) * 1_048_576L
                    resp.header("Content-Length")?.toLongOrNull()?.let { cl ->
                        if (cl > maxBytes) {
                            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                                "download_blocked: 超限 cl=$cl max=$maxBytes")
                            return@withContext FunctionResult.failure(
                                "文件大小（${cl / 1_048_576}MB）超过上限（${maxBytes / 1_048_576}MB），可在模块配置调高")
                        }
                    }
                    val body = resp.body ?: return@withContext FunctionResult.failure("下载失败: 空响应体")
                    val t0 = System.currentTimeMillis()
                    val result = workDir.writeBinaryStream(DOWNLOAD_SUBDIR, fileName, mime, maxBytes, body.byteStream())
                    if (result.error != null) {
                        return@withContext FunctionResult.failure(result.error)
                    }
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                        "download_done: ${result.finalName} bytes=${result.bytesWritten} " +
                                "ms=${System.currentTimeMillis() - t0}")
                    FunctionResult.success(
                        "✅ 已下载到工作目录/$DOWNLOAD_SUBDIR/${result.finalName}（${result.bytesWritten / 1024}KB）")
                }
            } catch (e: Exception) {
                traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "download_failed: ${e.message?.take(60)}")
                FunctionResult.failure("下载失败: ${e.message?.take(60)}")
            }
        }

    /** 文件名净化（D57：LLM 无路径自由度）——仅相对名，剥离穿越/非法字符 */
    private fun sanitizeName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("[/\\\\:*?\"<>|%#]"), "_")
        if (name.isBlank() || name == "." || name == ".." || name.startsWith(".")) {
            name = "download_${System.currentTimeMillis()}"
        }
        return name.take(120)
    }

    // ═══ upload_file（C3.4：端→云通道）═══

    /** zero-cloud 上传端点（与 MCP 同入口：IP 直连自签 + Bearer token，pinnedHttp 承载） */
    private val cloudUploadUrl: String
        get() = "https://${ZeroCloudTrust.HOST}:8443/files"

    private suspend fun uploadFile(fileName: String): FunctionResult = withContext(Dispatchers.IO) {
        if (fileName.isBlank()) {
            return@withContext FunctionResult.failure("file_name 不能为空")
        }
        val safe = sanitizeName(fileName)
        val cfg = config as WebFetchConfig
        val maxBytes = cfg.uploadMaxMB.coerceIn(1, 2048) * 1_048_576L
        // C3.4b：流式读取（大文件免全量进内存——readBinary 全量 byte[] 在大档位必 OOM）
        val (stream, len) = workDir.openReadStream(safe)
            ?: return@withContext FunctionResult.failure(
                "读取 $safe 失败：文件不存在（工作目录根或 downloads/ 下）")
        if (len >= 0 && len > maxBytes) {
            stream.close()
            return@withContext FunctionResult.failure(
                "文件 ${len / 1_048_576}MB 超过上传上限 ${maxBytes / 1_048_576}MB（可在模块配置调高，服务器硬顶 2048MB）")
        }
        val mime = safe.substringAfterLast('.', "").lowercase().let { ext ->
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        } ?: "application/octet-stream"
        // 流式 RequestBody：边读边传 + 在途配额累计（未知长度走 chunked）
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = len
            override fun writeTo(sink: okio.BufferedSink) {
                stream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) throw java.io.IOException("超过上传上限 ${maxBytes / 1_048_576}MB")
                        sink.write(buf, 0, n)
                    }
                }
            }
        }
        try {
            val url = "$cloudUploadUrl?filename=" +
                java.net.URLEncoder.encode(safe, "UTF-8") + "&mime=" +
                java.net.URLEncoder.encode(mime, "UTF-8")
            pinnedHttp.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                        "upload_failed: HTTP ${r.code} ${text.take(60)}")
                    return@withContext FunctionResult.failure("上传失败: HTTP ${r.code}")
                }
                val fileId = JSONObject(text).optString("file_id", "")
                if (fileId.isBlank()) {
                    return@withContext FunctionResult.failure("上传响应异常: ${text.take(80)}")
                }
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "upload_done: $safe file_id=${fileId.take(8)}")
                FunctionResult.success("✅ 已上传到云端：file_id=$fileId（24 小时有效）" +
                        "——可直接用于 cloud_file_convert / cloud_file_process 等云端工具")
            }
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "upload_failed: ${e.message?.take(60)}")
            FunctionResult.failure("上传失败: ${e.message?.take(60)}（检查 zero-cloud 服务器与 Key 配置）")
        }
    }

    // ═══ fetch_webpage（双引擎）═══

    private suspend fun fetchWebpage(url: String): FunctionResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            return@withContext FunctionResult.failure("仅支持 https 链接: ${url.take(60)}")
        }
        try {
            // 引擎 ①：本地 jsoup
            var engine = "local"
            var title = ""
            var bodyText = ""
            var links = emptyList<String>()
            var images = emptyList<String>()
            val html = httpFetchCapped(url)
            if (html != null) {
                val doc = Jsoup.parse(html, url)
                title = doc.title()?.trim() ?: ""
                bodyText = doc.body()?.text()?.trim() ?: ""
                links = doc.select("a[href]").mapNotNull { it.absUrl("href").ifBlank { null } }
                    .filter { it.startsWith("http") }.distinct().take(LINK_CAP)
                images = doc.select("img[src]").mapNotNull { it.absUrl("src").ifBlank { null } }
                    .filter { it.startsWith("http") }.distinct().take(IMG_CAP)
            }
            // 引擎 ②：正文过短（疑似 JS 渲染页）或本地失败 → Jina Reader（服务端渲染）
            if (bodyText.length < JS_PAGE_SUSPECT_LEN) {
                val jina = fetchJina(url)
                if (jina != null && jina.length > bodyText.length) {
                    engine = "jina"
                    bodyText = jina.trim()
                    if (title.isBlank()) {
                        title = bodyText.lineSequence().firstOrNull()?.take(80) ?: ""
                    }
                }
            }
            if (bodyText.isBlank()) {
                return@withContext FunctionResult.failure(
                    "网页抓取失败（可能是反爬限制或网络问题），建议改用 web_search 获取信息")
            }
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "fetch_done: engine=$engine len=${bodyText.length} links=${links.size} url=${url.take(60)}")
            val sb = StringBuilder()
            sb.appendLine("📄 ${title.ifBlank { "(无标题)" }}（engine=$engine，${bodyText.length} 字）")
            sb.appendLine()
            sb.appendLine(bodyText.take(BODY_CAP))
            if (links.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("── 页面链接（${links.size}）──")
                links.forEachIndexed { i, l -> sb.appendLine("[${i + 1}] $l") }
            }
            if (images.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("── 图片资源（${images.size}，可用 download_file 下载）──")
                images.forEach { sb.appendLine(it) }
            }
            FunctionResult.success(sb.toString())
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "fetch_failed: ${e.message?.take(60)}")
            FunctionResult.failure("网页抓取失败: ${e.message?.take(60)}")
        }
    }

    /** 本地取 HTML（≤5MB 截断；失败返回 null） */
    private fun httpFetchCapped(url: String): String? = try {
        clientFor(url).newCall(Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val bytes = body.byteStream().use { ins ->
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_HTML_BYTES) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        }
    } catch (_: Exception) { null }

    /** Jina Reader：r.jina.ai/<url> 服务端渲染 → Markdown（Key 可选，Bearer） */
    private fun fetchJina(url: String): String? = try {
        val key = secureKeyStore.getOrFallbackString((config as WebFetchConfig).jinaApiKeyRef, null)
        val req = Request.Builder()
            .url("https://r.jina.ai/$url")
            .header("User-Agent", DESKTOP_UA)
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) { null }

    private class ByteArrayOutputStream : java.io.ByteArrayOutputStream()
}
