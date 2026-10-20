package com.zero.v2.app.data

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件管理模块 — 开发前规范化 N3（WorkFiles + OfficeFiles 合并，D57 文件主权）；S9.1 全操作。
 *
 * 工作目录文本类（原 WorkFiles，Step 6.7）：
 * FC save_to_file / read_work_file(offset 分页) / list_work_files（S9.1 目录树）/ delete_work_file
 * Office 类（原 OfficeFiles，Step 8.6，v6.26e/v6.27）：
 * FC read_office_file(offset 分页) / create_document / edit_document
 * S9.1（D123 完全暴露 + SAF 天然护栏）：
 * FC move_file / create_folder / delete_folder(force)；delete_work_file 及新增三项默认
 * **AUTO**（用户主权让渡"正常所有文件夹操作权限"）；D57 硬护栏不变（SAF 边界/危险扩展名
 * 拦截/路径净化——越界物理不可能）。
 *
 * 用户主权（D17）：文件大小上限 + 单次读取页长均可配（模块详情页）。
 * 安全硬上限：文件 ≤50MB / 页长 ≤50000 字（防 OOM / LLM 上下文爆，不可配置）。
 */
class FilesConfig : ModuleConfigBase() {
    @ConfigField(label = "文件大小上限 (MB)", fieldType = ConfigFieldType.NUMBER,
        min = 1.0, max = 50.0,
        description = "读取办公文件的单文件大小上限（绝对硬上限 50MB）")
    var maxFileSizeMB: Int = 5

    @ConfigField(label = "单次读取页长（字）", fieldType = ConfigFieldType.NUMBER,
        min = 1000.0, max = 50000.0,
        description = "read_work_file / read_office_file 共用的每页字符数——长文档自动分页，LLM 翻页读完全文")
    var pageLength: Int = 8000

    override fun toJson(): String =
        """{"maxFileSizeMB":$maxFileSizeMB,"pageLength":$pageLength}"""

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            // coerceIn 兜底：防直改存储 JSON 绕过 UI 界限（§7.3）
            maxFileSizeMB = obj.optInt("maxFileSizeMB", 5).coerceIn(1, 50)
            pageLength = obj.optInt("pageLength", 8000).coerceIn(1000, 50_000)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = FilesConfig()
}

@Singleton
class FilesModule @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val workDir: WorkDirRepository,
    private val attachmentStore: AttachmentStore,
    private val mediaAnalysis: com.zero.v2.services.llm.MediaAnalysisService,

    private val traceManager: AgentTraceManager,
    /** KB1.4/KB1.5（KA10/KD8）：文件变更通知——写 FC 尾部 emit，消费方（知识库）经总线订阅 */
    private val systemBus: com.zero.v2.core.bus.SystemBus
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    companion object {
        private const val TRACE_ID = "files"
        /** 安全硬上限（不可配置——内存保护 / LLM 上下文保护） */
        private const val ABSOLUTE_MAX_FILE_MB = 50
        private const val ABSOLUTE_MAX_PAGE = 50_000
    }

    override val metadata = ModuleMetadata(
        id = "files",
        displayName = "文件管理",
        description = "工作目录全操作：读写（txt/md/pdf）+ Office（docx/xlsx/pptx）+ 移动/重命名 + 文件夹管理（目录树）",
        category = ModuleCategory.INSTRUMENT
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = FilesConfig()

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE
        // 修复（实测 08-31 PDF 追加闪退）：PdfBox-Android 必须先 init（全库此前零调用，
        // 未初始化时字体/资源加载走异常路径）。幂等，失败降级 HELVETICA 不阻塞模块。
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(appContext)
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id, "pdfbox_initialized")
        } catch (t: Throwable) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "pdfbox_init_failed: ${t.javaClass.simpleName} ${t.message?.take(40)}")
        }

    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    private fun mediaParameters(reference: String, prompt: String?): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to buildMap {
            put(reference, mapOf("type" to "string"))
            put("mime", mapOf("type" to "string", "description" to "文件真实MIME，例如image/png、audio/wav、video/mp4"))
            if (prompt != null) put(prompt, mapOf("type" to "string"))
        },
        "required" to listOf(reference, "mime"),
    )

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition(name = "media_analyze",
                description = "原生模型分析图片、视频采样画面或转写音频。与动态工具互为可选路径；传入完整附件引用和MIME，不传base64。",
                parameters = mediaParameters("attachment_id", "prompt")), metadata.id, permission = ActionPermission.AUTO,
        ) { args ->
            val mime = args["mime"] as? String ?: "image/jpeg"
            val attachment = com.zero.v2.core.common.Attachment(args["attachment_id"] as? String ?: "", mime,
                com.zero.v2.core.common.MediaAttachmentPolicy.type(mime))
            FunctionResult.success(mediaAnalysis.analyze(attachment, args["prompt"] as? String ?: "描述媒体内容"))
        }
        functionRegistry.register(
            FunctionDefinition(name = "attachment_publish",
                description = "将工作站生成的图片、音频或视频文件（最大5MB）呈现在对话中，用户可打开查看或播放。",
                parameters = mediaParameters("path", null)), metadata.id, permission = ActionPermission.AUTO,
        ) { args -> attachmentStore.publish(args["path"] as? String ?: "", args["mime"] as? String ?: "") }
        functionRegistry.register(
            FunctionDefinition(
                name = "attachment_export",
                description = "将对话图片、音频或视频附件导出到 Debian 工作站，返回 path。随后可发现并调用动态媒体工具读取该路径；不要传递base64。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf("attachment_id" to mapOf("type" to "string", "description" to "对话中的完整附件引用")),
                    "required" to listOf("attachment_id"),
                ),
            ), metadata.id, permission = ActionPermission.AUTO,
        ) { args -> attachmentStore.export(args["attachment_id"] as? String ?: "") }

        functionRegistry.register(
            FunctionDefinition(
                name = "read_office_file",
                description = "分页读取工作目录中的 Office 文件（docx/xlsx/pptx/pdf）。" +
                        "首次调用不带 offset 从头读；返回含 has_more 标记，为 true 时再次调用并传 offset 继续读下一页，" +
                        "直到 has_more=false 即读完全文。适用于长文档全读。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file_name" to mapOf("type" to "string",
                            "description" to "工作目录内的文件名（如 report.docx）"),
                        "offset" to mapOf("type" to "number",
                            "description" to "读取偏移量（字符）。首次不传=从0开始；翻页时传上次返回的 next_offset")
                    ),
                    "required" to listOf("file_name")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val fileName = args["file_name"] as? String ?: ""
            val offset = intArg(args, "offset", 0)
            readOfficeFile(fileName, offset)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "create_document",
                description = "生成或追加办公文件到工作目录。docx=Word / xlsx=Excel / pdf=PDF。" +
                        "append=true 且文件已存在时追加内容（docx/xlsx 在末尾追加，pdf 追加新页）；" +
                        "append=false 或文件不存在时从零创建。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file_name" to mapOf("type" to "string",
                            "description" to "文件名（如 会议纪要.docx / 数据表.xlsx / 报告.pdf）"),
                        "content" to mapOf("type" to "string",
                            "description" to "内容——docx: 每行一段落（首行=标题）；xlsx: 首行=表头，后续行=逗号分隔数据；pdf: 纯文本"),
                        "format" to mapOf("type" to "string",
                            "description" to "docx / xlsx / pdf（默认 docx）"),
                        "append" to mapOf("type" to "boolean",
                            "description" to "true=文件已存在时追加内容（默认 false 创建新文件）")
                    ),
                    "required" to listOf("file_name", "content")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val fileName = args["file_name"] as? String ?: ""
            val content = args["content"] as? String ?: ""
            val format = (args["format"] as? String ?: "docx").lowercase()
            val append = boolArg(args, "append", false)
            createDocument(fileName, content, format, append)
        }

        // v6.27: edit_document——精准修改（查找替换 / 单元格编辑）
        functionRegistry.register(
            FunctionDefinition(
                name = "edit_document",
                description = "精准修改已有办公文件（不重建整个文件）。支持两种操作：" +
                        "① replace：查找替换（docx/xlsx/pptx 全文搜索替换）；" +
                        "② set_cell：修改 Excel 指定单元格的值。PDF 不支持精准修改。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file_name" to mapOf("type" to "string",
                            "description" to "工作目录内的文件名"),
                        "operation" to mapOf("type" to "string",
                            "description" to "replace（查找替换）或 set_cell（改单元格）"),
                        "find_text" to mapOf("type" to "string",
                            "description" to "[replace] 要查找的文本"),
                        "replace_text" to mapOf("type" to "string",
                            "description" to "[replace] 替换为的文本"),
                        "cell_ref" to mapOf("type" to "string",
                            "description" to "[set_cell] 单元格引用（如 B3、A1）"),
                        "value" to mapOf("type" to "string",
                            "description" to "[set_cell] 新值")
                    ),
                    "required" to listOf("file_name", "operation")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args -> editDocument(args) }

        // ═══ 工作目录文本类（原 WorkFilesModule，Step 6.7 / N1.2 分页）═══
        functionRegistry.register(
            FunctionDefinition(
                name = "save_to_file",
                description = "在用户工作目录创建或追加任意类型文件（生词本 vocabulary.md / 笔记 / 总结 / 日记 / " +
                        "代码片段 / 清单等；format=pdf 时生成排版 PDF）。file_name 支持子目录相对路径" +
                        "（如 归档/2026/笔记.md——父目录自动逐级创建）；回执返回真实落点路径。未设置工作目录返回引导文案。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file_name" to mapOf("type" to "string", "description" to "文件名或子目录相对路径（如 vocabulary.md / 归档/读书笔记.txt / 总结.pdf）"),
                        "content" to mapOf("type" to "string", "description" to "写入内容（追加时建议以换行结尾）"),
                        "format" to mapOf("type" to "string", "description" to "txt（默认）/ md / pdf"),
                        "append" to mapOf("type" to "boolean", "description" to "true=追加到已有内容末尾（默认，文件不存在则新建）；false=覆盖重写（原内容被替换为仅本次内容）；pdf 恒为新文件")
                    ),
                    "required" to listOf("file_name", "content")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val fileName = args["file_name"] as? String ?: ""
            val content = args["content"] as? String ?: ""
            val format = (args["format"] as? String ?: "txt").lowercase()
            val append = boolArg(args, "append", true)  // 2026-09-18：三态解析（字符串"false"曾静默落默认 true）
            saveToFile(fileName, content, format, append)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "read_work_file",
                description = "分页读取用户工作目录的文本文件（txt/md 等，如查生词本 vocabulary.md、读取长文档）。" +
                        "返回本页内容与全文总长；若提示还有后文，带 offset 参数再调用读下一页，直至读到末尾。" +
                        "未设置目录或文件不存在返回提示。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file_name" to mapOf("type" to "string", "description" to "文件名"),
                        "offset" to mapOf("type" to "integer", "description" to "起始字符偏移（缺省 0 读第一页；翻页用上次返回的下一页偏移）")
                    ),
                    "required" to listOf("file_name")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val fileName = args["file_name"] as? String ?: ""
            val offset = intArg(args, "offset", 0)
            readWorkFile(fileName, offset)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "list_work_files",
                description = "以目录树列出用户工作目录的全部内容（📁 文件夹 / 📄 文件·大小·时间，" +
                        "嵌套路径按缩进展示，深度上限 5 层）。用于了解文件空间结构后再移动/整理。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { listWorkFiles() }

        functionRegistry.register(
            FunctionDefinition(
                name = "delete_work_file",
                description = "删除用户工作目录中的指定文件（不可恢复，直接执行）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file_name" to mapOf("type" to "string", "description" to "要删除的文件名")
                    ),
                    "required" to listOf("file_name")
                )
            ),
            // D123 用户主权让渡：工作目录操作默认 AUTO（原 CONFIRM）
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val fileName = args["file_name"] as? String ?: ""
            deleteWorkFile(fileName)
        }

        // ═══ S9.1（D123）：文件夹全操作 ×3——AUTO 让渡 + SAF 天然护栏 ═══

        functionRegistry.register(
            FunctionDefinition(
                name = "move_file",
                description = "移动或重命名工作目录内的文件/文件夹。new_name_or_path 不含“/”=原地重命名；" +
                        "含“/”=移动到目标子目录（如“归档/报告.docx”）。路径为工作目录内相对路径，" +
                        "目标已存在时拒绝（不覆盖）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file_name" to mapOf("type" to "string",
                            "description" to "现有文件/文件夹的相对路径（如 报告.docx 或 downloads/a.pdf）"),
                        "new_name_or_path" to mapOf("type" to "string",
                            "description" to "新名或目标相对路径（重命名：新名字.md；移动：目标文件夹/名字.md）")
                    ),
                    "required" to listOf("file_name", "new_name_or_path")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val from = args["file_name"] as? String ?: ""
            val to = args["new_name_or_path"] as? String ?: ""
            withContext(Dispatchers.IO) {
                val error = workDir.moveEntry(from, to)
                if (error == null) {
                    notifyFilesChanged(from, to)  // KB1.5：新旧两路径（移出/移入两侧都可能影响索引）
                    FunctionResult.success("✅ 已移动/重命名：$from → $to")
                } else FunctionResult.failure(error)
            }
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "create_folder",
                description = "在工作目录新建文件夹（支持嵌套路径如“项目/2026/报告”，逐级创建）。已存在则直接成功。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "folder_name" to mapOf("type" to "string", "description" to "文件夹相对路径")
                    ),
                    "required" to listOf("folder_name")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val folder = args["folder_name"] as? String ?: ""
            withContext(Dispatchers.IO) {
                val error = workDir.createFolder(folder)
                if (error == null) FunctionResult.success("✅ 文件夹已就绪：$folder")
                else FunctionResult.failure(error)
            }
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "delete_folder",
                description = "删除工作目录中的文件夹（递归删除全部内容，不可恢复）。" +
                        "文件夹非空时必须传 force=true；返回已删除文件清单与数量/总大小（审计）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "folder_name" to mapOf("type" to "string", "description" to "要删除的文件夹相对路径"),
                        "force" to mapOf("type" to "boolean",
                            "description" to "非空文件夹递归删除确认（默认 false：非空时拒绝并返回内容统计）")
                    ),
                    "required" to listOf("folder_name")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val folder = args["folder_name"] as? String ?: ""
            val force = boolArg(args, "force", false)
            withContext(Dispatchers.IO) {
                val r = workDir.deleteTree(folder, force)
                when {
                    r.error != null && !r.refused -> FunctionResult.failure(r.error)
                    r.refused -> FunctionResult.failure(
                        "⚠️ ${r.error}\n内容统计：${r.fileCount} 文件 / ${r.dirCount} 子目录 / ${r.totalBytes / 1024}KB" +
                                "\n确认删除请带 force=true 重新调用")
                    r.failed.isNotEmpty() -> FunctionResult.failure(
                        "部分删除失败 ${r.failed.size} 项：${r.failed.take(5).joinToString("、")}（其余已删）")
                    else -> {
                        notifyFilesChanged(folder)  // KB1.5：整树删除（子树含 .md 时知识库对账收敛）
                        FunctionResult.success(
                            "🗑 已删除 $folder：${r.fileCount} 文件 / ${r.dirCount} 子目录 / ${r.totalBytes / 1024}KB" +
                                    "\n清单：${r.fileManifest.take(20).joinToString("、").ifBlank { "（空目录）" }}")
                    }
                }
            }
        }
    }

    // ═══ 参数三态解析（2026-09-18 真机反馈修复 F5 类根因）═══
    // parseToolArguments 原样保留 JSON 类型：LLM 把布尔/数字发成字符串（"append":"false"）时，
    // 原直投 as? Boolean 落空 → 静默取默认值 → 覆盖指令变追加（F5 实测）。三态全收，杜绝静默反向。

    private fun boolArg(args: Map<String, Any?>, key: String, default: Boolean): Boolean =
        when (val v = args[key]) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true", "1" -> true; "false", "0" -> false; else -> default
            }
            is Number -> v.toInt() != 0
            else -> default
        }

    private fun intArg(args: Map<String, Any?>, key: String, default: Int): Int =
        when (val v = args[key]) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull() ?: default
            else -> default
        }

    // ═══ read_office_file（v6.26e: 分页 + LLM 自主翻页）═══

    private suspend fun readOfficeFile(fileName: String, offset: Int): FunctionResult =
        withContext(Dispatchers.IO) {
            if (fileName.isBlank()) return@withContext FunctionResult.failure("file_name 不能为空")
            val ext = FileFormatPolicy.extension(fileName)
            if (!FileFormatPolicy.canReadOffice(ext)) {
                return@withContext FunctionResult.failure(
                    "不支持的格式 .$ext——仅支持 docx/xlsx/pptx/pdf")
            }
            val cfg = config as FilesConfig
            val maxBytes = cfg.maxFileSizeMB.coerceIn(1, ABSOLUTE_MAX_FILE_MB) * 1L * 1024 * 1024
            val bytes = workDir.readBinary(fileName, maxBytes)
                ?: return@withContext FunctionResult.failure(
                    if (workDir.hasDirectory()) "文件不存在或超过 ${maxBytes / 1048576}MB 上限: $fileName"
                    else "尚未设置工作目录。请引导用户到 资料 → 知识与工作文件 选择目录")
            val t0 = System.currentTimeMillis()
            val text = try {
                when (ext) {
                    "docx" -> extractDocx(bytes)
                    "xlsx" -> extractXlsx(bytes)
                    "pptx" -> extractPptx(bytes)
                    "pdf" -> extractPdf(bytes)
                    else -> null
                }
            } catch (e: Exception) {
                traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "read_failed: $fileName ${e.javaClass.simpleName} ${e.message?.take(40)}")
                return@withContext FunctionResult.failure(
                    "❌ 读取失败: ${e.javaClass.simpleName} ${e.message?.take(50)}")
            }
            if (text.isNullOrBlank()) {
                return@withContext FunctionResult.failure("文件为空或无文本内容: $fileName")
            }
            // 分页（N1.3：返回结构与 read_work_file 同构——LLM 一套翻页逻辑通用）
            val pageLen = cfg.pageLength.coerceIn(1000, ABSOLUTE_MAX_PAGE)
            val total = text.length
            val safeOffset = offset.coerceIn(0, total)
            val pageEnd = minOf(safeOffset + pageLen, total)
            val page = text.substring(safeOffset, pageEnd)
            val hasMore = pageEnd < total

            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "read_paged: $fileName offset=$safeOffset pageLen=${page.length} " +
                        "total=$total has_more=$hasMore ms=${System.currentTimeMillis() - t0}")

            val sb = StringBuilder()
            sb.appendLine("📄 $fileName（全文共 $total 字，本页第 ${safeOffset + 1}-$pageEnd 字）：")
            sb.appendLine(page)
            sb.append(if (hasMore)
                "── 还有后文：调用 read_office_file(file_name=\"$fileName\", offset=$pageEnd) 读下一页 ──"
            else "（已读到末尾）")
            FunctionResult.success(sb.toString())
        }

    // ═══ 工作目录文本类实现（原 WorkFilesModule，N1.2 分页版）═══

    private suspend fun saveToFile(fileName: String, content: String, format: String, append: Boolean): FunctionResult {
        if (fileName.isBlank()) return FunctionResult.failure("file_name 不能为空")
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
            "save_requested: $fileName format=$format append=$append bytes=${content.toByteArray(Charsets.UTF_8).size}")
        // S9.1-fix2（用户裁定）：写族支持子目录相对路径（父目录自动创建）；
        // 回执=真实落点（SAF 撞名"(1)"/MIME 改名后的实名）——不再回显请求路径
        val outcome = if (format == "pdf") {
            workDir.writePdf(fileName, fileName.removeSuffix(".pdf"), content)
        } else {
            workDir.writeText(fileName, content, append)
        }
        return if (outcome.error == null) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "file_saved: ${outcome.finalRelPath} ($format)")
            notifyFilesChanged(outcome.finalRelPath)  // KB1.5：emit 真实落点（SR14）
            FunctionResult.success("已${if (format == "pdf") "生成" else if (append) "追加" else "保存"}到工作目录：${outcome.finalRelPath}")
        } else {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "save_failed: $fileName ${outcome.error.take(50)}")
            FunctionResult.failure(outcome.error)
        }
    }

    private suspend fun readWorkFile(fileName: String, offset: Int): FunctionResult {
        if (!workDir.hasDirectory()) return FunctionResult.failure(
            "工作目录未设置。请引导用户在 资料 → 知识与工作文件 选择目录。")
        val pageLen = (config as FilesConfig).pageLength
        val paged = workDir.readText(fileName, offset, pageLen)
            ?: return FunctionResult.success("📄 $fileName 不存在或为空")
        val next = paged.offset + paged.content.length
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
            "read_paged: $fileName offset=${paged.offset} pageLen=${paged.content.length} " +
                    "total=${paged.totalLen} has_more=${paged.hasMore}")
        val sb = StringBuilder()
        sb.appendLine("📄 $fileName（全文共 ${paged.totalLen} 字，本页第 ${paged.offset + 1}-$next 字）：")
        sb.appendLine(paged.content)
        sb.append(if (paged.hasMore)
            "── 还有后文：调用 read_work_file(file_name=\"$fileName\", offset=$next) 读下一页 ──"
        else "（已读到末尾）")
        return FunctionResult.success(sb.toString())
    }

    private suspend fun listWorkFiles(): FunctionResult {
        // S9.1（D123）：目录树渲染——含文件夹标注，嵌套按缩进（SR7 ≤5 层）
        val nodes = workDir.listTree()
        if (nodes.isEmpty()) return FunctionResult.success(
            if (workDir.hasDirectory()) "📭 工作目录为空" else "工作目录未设置。请引导用户在 资料 → 知识与工作文件 选择目录。")
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
            "file_listed: ${nodes.size} 节点（树）")
        return FunctionResult.success(nodes.joinToString("\n") { n ->
            val indent = "  ".repeat(n.path.count { it == '/' })
            if (n.isDir) "$indent📁 ${n.name}/"
            else "$indent📄 ${n.name} ${formatSize(n.sizeBytes)} · ${formatTime(n.lastModified)}"
        })
    }

    private suspend fun deleteWorkFile(fileName: String): FunctionResult {
        val error = workDir.deleteFile(fileName)
        return if (error == null) {
            notifyFilesChanged(fileName)  // KB1.5
            FunctionResult.success("已删除：$fileName")
        } else FunctionResult.failure(error)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1fMB".format(bytes / 1_048_576f)
        bytes >= 1024 -> "%.1fKB".format(bytes / 1024f)
        else -> "${bytes}B"
    }

    /** KB1.5（KA10/KD8 · AA2 四 FC 面）：写后文件变更通知——**事件=提示，树=真相**；
     *  不判路径/扩展名（过滤归消费方 KnowledgeModule）；save_to_file 传 WriteOutcome
     *  .finalRelPath 真实落点（SR14 重名改名场景）；tryEmit 永不阻塞，事件层故障
     *  （runCatching）不影响文件操作本身 */
    private fun notifyFilesChanged(vararg paths: String) {
        runCatching { systemBus.onFilesChanged.tryEmit(paths.toList()) }
    }

    private fun formatTime(ms: Long): String =
        if (ms <= 0) "-" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    // ═══ 文本抽取（v6.26c: POI 运行时崩溃 → 全部切换 ZIP+XML 手写解析）═══

    private fun extractDocx(bytes: ByteArray): String? {
        // docx = ZIP → word/document.xml → <w:t> 段落文本，</w:p> = 换行
        val xml = readZipEntry(bytes, "word/document.xml") ?: return null
        val sb = StringBuilder()
        // 按 </w:p> 分段落
        for (para in xml.split("</w:p>")) {
            val texts = extractTagText(para, "w:t")
            if (texts.isNotEmpty()) sb.appendLine(texts.joinToString(""))
        }
        return sb.toString().trim().ifBlank { null }
    }

    private fun extractXlsx(bytes: ByteArray): String? {
        // xlsx = ZIP → xl/worksheets/sheet*.xml → <v>/<t> 单元格值，</row> = 换行
        val sb = StringBuilder()
        // 找所有 sheet 文件
        val zip = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
        var entry: java.util.zip.ZipEntry?
        val sheetXmls = mutableListOf<Pair<String, String>>()
        while (zip.nextEntry.also { entry = it } != null) {
            val name = entry!!.name
            if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
                sheetXmls.add(name to zip.readBytes().toString(Charsets.UTF_8))
            }
        }
        zip.close()
        // sharedStrings（如有）
        val sharedStrings = readZipEntry(bytes, "xl/sharedStrings.xml")
            ?.let { extractTagText(it, "t") } ?: emptyList()

        sheetXmls.sortedBy { it.first }.forEach { (name, xml) ->
            val sheetName = name.substringAfterLast('/').removeSuffix(".xml")
            sb.appendLine("── $sheetName ──")
            for (rowXml in xml.split("</row>")) {
                val cells = mutableListOf<String>()
                // 提取 <v>（数值）和 <t>（共享/内联字符串）
                for (cellXml in rowXml.split("</c>")) {
                    val v = extractTagText(cellXml, "v").firstOrNull()
                    val t = extractTagText(cellXml, "t").firstOrNull()
                    val text = t ?: v ?: continue
                    // 共享字符串索引（t="s" 时 v 是索引）
                    val isShared = cellXml.contains("t=\"s\"")
                    val value = if (isShared && v != null) {
                        sharedStrings.getOrNull(v.toIntOrNull() ?: -1) ?: text
                    } else text
                    if (value.isNotBlank()) cells.add(value)
                }
                if (cells.isNotEmpty()) sb.appendLine(cells.joinToString(" | "))
            }
        }
        return sb.toString().trim().ifBlank { null }
    }

    private fun extractPptx(bytes: ByteArray): String? {
        // pptx = ZIP → ppt/slides/slide*.xml → <a:t> 文本
        val sb = StringBuilder()
        val zip = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
        var entry: java.util.zip.ZipEntry?
        val slides = mutableListOf<Pair<Int, String>>()
        while (zip.nextEntry.also { entry = it } != null) {
            val name = entry!!.name
            if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                val num = name.substringAfterLast('/').removePrefix("slide").removeSuffix(".xml")
                    .toIntOrNull() ?: 0
                slides.add(num to zip.readBytes().toString(Charsets.UTF_8))
            }
        }
        zip.close()
        slides.sortedBy { it.first }.forEach { (num, xml) ->
            sb.appendLine("── Slide $num ──")
            for (t in extractTagText(xml, "a:t")) {
                if (t.isNotBlank()) sb.appendLine(t)
            }
        }
        return sb.toString().trim().ifBlank { null }
    }

    /** 从 ZIP 字节中读取指定条目内容 */
    private fun readZipEntry(bytes: ByteArray, entryName: String): String? {
        return try {
            val zip = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
            var entry: java.util.zip.ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                if (entry!!.name == entryName) {
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    zip.close()
                    return content
                }
            }
            zip.close()
            null
        } catch (_: Exception) { null }
    }

    /** 正则提取 XML 标签内文本（<tagName ...>text</tagName>），处理 XML 实体 */
    private fun extractTagText(xml: String, tagName: String): List<String> {
        val pattern = Regex("<$tagName[^>]*>(.*?)</$tagName>", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(xml).map { m ->
            unescapeXml(m.groupValues[1])
        }.toList()
    }

    private fun unescapeXml(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")

    private fun extractPdf(bytes: ByteArray): String? {
        // PdfBox-Android：实际包名 com.tom_roush.pdfbox（AAR 结构验证）
        return try {
            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes)
            doc.use {
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                val text = stripper.getText(it)
                text?.trim()?.ifBlank { null }
            }
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "pdf_read_failed: ${e.message?.take(40)}")
            null
        }
    }

    // ═══ create_document（v6.27: 追加 + 创建统一入口）═══

    private suspend fun createDocument(fileName: String, content: String, format: String, append: Boolean): FunctionResult =
        withContext(Dispatchers.IO) {
            if (fileName.isBlank() || content.isBlank()) {
                return@withContext FunctionResult.failure("file_name 和 content 不能为空")
            }
            val ext = FileFormatPolicy.resolveDocumentExtension(fileName, format)
            if (!FileFormatPolicy.canWriteDocument(ext)) {
                return@withContext FunctionResult.failure("仅支持 docx/xlsx/pdf（当前 .$ext）")
            }
            val t0 = System.currentTimeMillis()
            val cfg = config as FilesConfig
            val maxBytes = cfg.maxFileSizeMB.coerceIn(1, ABSOLUTE_MAX_FILE_MB) * 1L * 1024 * 1024

            // 判断是否走追加路径
            val existingBytes = if (append) workDir.readBinary(fileName, maxBytes) else null
            val isAppend = existingBytes != null

            val mime = when (ext) {
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "pdf" -> "application/pdf"
                else -> return@withContext FunctionResult.failure("不支持的格式")
            }
            val bytes = try {
                when {
                    isAppend && ext == "docx" -> appendDocx(existingBytes!!, content)
                    isAppend && ext == "xlsx" -> appendXlsx(existingBytes!!, content)
                    isAppend && ext == "pdf" -> appendPdfSafe(existingBytes!!, content)
                    ext == "docx" -> createDocxZip(content)
                    ext == "xlsx" -> createXlsxZip(content)
                    ext == "pdf" -> createPdfNew(content)
                    else -> return@withContext FunctionResult.failure("不支持的格式")
                }
            } catch (e: Throwable) {
                // 修复：Throwable 兜底（原仅 Exception——OOM/LinkageError 漏网即进程闪退）
                traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                    "create_failed: $fileName ${e.javaClass.simpleName} ${e.message?.take(40)}")
                return@withContext FunctionResult.failure(
                    "❌ ${if (isAppend) "追加" else "生成"}失败: ${e.javaClass.simpleName} ${e.message?.take(50)}")
            }
            val outcome = workDir.writeBinary(fileName, mime, bytes)
            if (outcome.error != null) return@withContext FunctionResult.failure(outcome.error)
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "create_ok: ${outcome.finalRelPath} bytes=${bytes.size} ms=${System.currentTimeMillis() - t0} " +
                        "engine=${if (ext == "pdf") "pdfbox" else "zip+xml"} ${if (isAppend) "append" else "create"}")
            FunctionResult.success(
                "✅ 已${if (isAppend) "追加到" else "生成"} ${outcome.finalRelPath}（${bytes.size / 1024}KB）")
        }

    // ═══ edit_document（v6.27: 精准修改——replace / set_cell）═══

    private suspend fun editDocument(args: Map<String, Any?>): FunctionResult =
        withContext(Dispatchers.IO) {
            val fileName = args["file_name"] as? String ?: ""
            val operation = args["operation"] as? String ?: ""
            if (fileName.isBlank() || operation.isBlank()) {
                return@withContext FunctionResult.failure("file_name 和 operation 不能为空")
            }
            val cfg = config as FilesConfig
            val maxBytes = cfg.maxFileSizeMB.coerceIn(1, ABSOLUTE_MAX_FILE_MB) * 1L * 1024 * 1024
            val existingBytes = workDir.readBinary(fileName, maxBytes)
                ?: return@withContext FunctionResult.failure("文件不存在: $fileName")
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val t0 = System.currentTimeMillis()

            when (operation) {
                "replace" -> {
                    val find = args["find_text"] as? String ?: ""
                    val repl = args["replace_text"] as? String ?: ""
                    if (find.isBlank()) return@withContext FunctionResult.failure("find_text 不能为空")
                    if (ext == "pdf") return@withContext FunctionResult.failure("PDF 不支持精准修改（仅支持追加新页）")

                    val entryName = when (ext) {
                        "docx" -> "word/document.xml"
                        "pptx" -> null  // pptx 需遍历多个 slide
                        "xlsx" -> "xl/worksheets/sheet1.xml"
                        else -> null
                    }
                    if (entryName == null && ext != "pptx") {
                        return@withContext FunctionResult.failure("不支持的格式 .$ext")
                    }

                    val newBytes: ByteArray? = if (ext == "pptx") {
                        // pptx：遍历所有 slide XML 替换
                        replaceInAllSlides(existingBytes, find, repl)
                    } else {
                        val xml = readZipEntry(existingBytes, entryName!!) ?: return@withContext FunctionResult.failure("文件结构异常")
                        val modified = xml.replace(escapeXml(find), escapeXml(repl))
                        if (modified == xml) null
                        else rebuildZipWithEntry(existingBytes, entryName, modified)
                    }
                    if (newBytes == null) {
                        return@withContext FunctionResult.failure("未找到「${find.take(20)}」——未做修改")
                    }
                    val mime = "application/vnd.openxmlformats-officedocument." +
                            when (ext) { "docx" -> "wordprocessingml.document"; "xlsx" -> "spreadsheetml.sheet"; else -> "presentationml.presentation" }
                    val err = workDir.writeBinary(fileName, mime, newBytes)
                    if (err.error != null) return@withContext FunctionResult.failure(err.error)
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                        "edit_replace_ok: $fileName find=\"${find.take(20)}\" ms=${System.currentTimeMillis() - t0}")
                    FunctionResult.success("✅ 已替换：「${find.take(20)}」→「${repl.take(20)}」")
                }

                "set_cell" -> {
                    if (ext != "xlsx") return@withContext FunctionResult.failure("set_cell 仅支持 xlsx")
                    val cellRef = (args["cell_ref"] as? String ?: "").uppercase()
                    val value = args["value"] as? String ?: ""
                    if (cellRef.isBlank()) return@withContext FunctionResult.failure("cell_ref 不能为空")

                    val newBytes = setCellValue(existingBytes, cellRef, value)
                        ?: return@withContext FunctionResult.failure("单元格 $cellRef 设置失败（可能超出已有范围）")
                    val err = workDir.writeBinary(fileName,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", newBytes)
                    if (err.error != null) return@withContext FunctionResult.failure(err.error)
                    traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                        "edit_set_cell_ok: $fileName $cellRef ms=${System.currentTimeMillis() - t0}")
                    FunctionResult.success("✅ 已设置 $cellRef = \"$value\"")
                }

                else -> return@withContext FunctionResult.failure("不支持的操作：$operation（可用 replace / set_cell）")
            }
        }

    // ═══ 手写 docx/xlsx（ZIP + 最小 OOXML——零依赖，POI 运行时崩溃的退路）═══

    /** 生成 docx ZIP 字节：[Content_Types].xml + _rels/.rels + word/document.xml */
    private fun createDocxZip(content: String): ByteArray {
        val lines = content.lines().filter { it.isNotBlank() }
        val bodyXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            lines.forEachIndexed { idx, line ->
                val text: String
                val bold: Boolean
                val large: Boolean
                val center: Boolean
                val indent: Boolean
                when {
                    idx == 0 -> { text = line; bold = true; large = true; center = true; indent = false }
                    line.startsWith("# ") -> { text = line.removePrefix("# "); bold = true; large = false; center = false; indent = false }
                    line.startsWith("- ") || line.startsWith("• ") -> { text = line.substring(2); bold = false; large = false; center = false; indent = true }
                    else -> { text = line; bold = false; large = false; center = false; indent = false }
                }
                append("""<w:p>""")
                if (center) append("""<w:pPr><w:jc w:val="center"/></w:pPr>""")
                if (indent) append("""<w:pPr><w:ind w:left="720"/></w:pPr>""")
                append("""<w:r><w:rPr>""")
                if (bold) append("""<w:b/>""")
                if (large) append("""<w:sz w:val="36"/>""") // 18pt
                append("""</w:rPr><w:t xml:space="preserve">""")
                append(escapeXml(text))
                append("""</w:t></w:r></w:p>""")
            }
            append("""</w:body></w:document>""")
        }
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
        return buildZip(mapOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rels,
            "word/document.xml" to bodyXml))
    }

    /** 生成 xlsx ZIP 字节：[Content_Types].xml + _rels/.rels + xl/workbook.xml + xl/_rels/workbook.xml.rels + xl/worksheets/sheet1.xml */
    private fun createXlsxZip(content: String): ByteArray {
        val lines = content.lines().filter { it.isNotBlank() }
        val sheetData = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            lines.forEachIndexed { rowIdx, line ->
                val cells = line.split(Regex("[,\\t]")).map { it.trim() }
                append("""<row r="${rowIdx + 1}">""")
                cells.forEachIndexed { colIdx, text ->
                    val col = colIdxToString(colIdx)
                    append("""<c r="$col${rowIdx + 1}" t="inlineStr"><is><t>""")
                    append(escapeXml(text))
                    append("""</t></is></c>""")
                }
                append("""</row>""")
            }
            append("""</sheetData></worksheet>""")
        }
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""
        val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""
        return buildZip(mapOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rels,
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to workbookRels,
            "xl/worksheets/sheet1.xml" to sheetData))
    }

    /** 构建 ZIP 字节（纯 java.util.zip，零依赖） */
    private fun buildZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** XML 特殊字符转义 */
    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    /** 列索引 → Excel 列名（0→A, 1→B, 25→Z, 26→AA） */
    private fun colIdxToString(idx: Int): String {
        var i = idx
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    // ═══ 追加辅助（v6.27）═══

    /** 重建 ZIP——替换指定条目内容，其余原样复制 */
    private fun rebuildZipWithEntry(bytes: ByteArray, entryToReplace: String, newContent: String): ByteArray {
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zipOut ->
            java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zipIn ->
                var entry: java.util.zip.ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    val name = entry!!.name
                    if (name == entryToReplace) {
                        zipOut.putNextEntry(java.util.zip.ZipEntry(name))
                        zipOut.write(newContent.toByteArray(Charsets.UTF_8))
                        zipOut.closeEntry()
                    } else {
                        zipOut.putNextEntry(java.util.zip.ZipEntry(name))
                        zipIn.copyTo(zipOut)
                        zipOut.closeEntry()
                    }
                }
            }
        }
        return output.toByteArray()
    }

    /** docx 追加：在 </w:body> 前插入新段落 */
    private fun appendDocx(existingBytes: ByteArray, newContent: String): ByteArray {
        val existingXml = readZipEntry(existingBytes, "word/document.xml")
            ?: return createDocxZip(newContent)  // 无 document.xml → 新建
        val newParas = buildString {
            for (line in newContent.lines().filter { it.isNotBlank() }) {
                if (line.startsWith("# ")) {
                    append("""<w:p><w:r><w:rPr><w:b/></w:rPr>""")
                    append("""<w:t xml:space="preserve">${escapeXml(line.removePrefix("# "))}</w:t></w:r></w:p>""")
                } else if (line.startsWith("- ") || line.startsWith("• ")) {
                    append("""<w:p><w:pPr><w:ind w:left="720"/></w:pPr><w:r>""")
                    append("""<w:t xml:space="preserve">${escapeXml(line.substring(2))}</w:t></w:r></w:p>""")
                } else {
                    append("""<w:p><w:r><w:t xml:space="preserve">${escapeXml(line)}</w:t></w:r></w:p>""")
                }
            }
        }
        val modified = existingXml.replace("</w:body>", "$newParas</w:body>")
        return rebuildZipWithEntry(existingBytes, "word/document.xml", modified)
    }

    /** xlsx 追加：在 </sheetData> 前插入新行（行号顺延） */
    private fun appendXlsx(existingBytes: ByteArray, newContent: String): ByteArray {
        val sheetXml = readZipEntry(existingBytes, "xl/worksheets/sheet1.xml")
            ?: return createXlsxZip(newContent)
        val maxRow = Regex("<row r=\"(\\d+)\"").findAll(sheetXml)
            .maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0
        val newRows = buildString {
            newContent.lines().filter { it.isNotBlank() }.forEachIndexed { idx, line ->
                val rowNum = maxRow + idx + 1
                val cells = line.split(Regex("[,\\t]")).map { it.trim() }
                append("""<row r="$rowNum">""")
                cells.forEachIndexed { colIdx, text ->
                    val col = colIdxToString(colIdx)
                    append("""<c r="$col$rowNum" t="inlineStr"><is><t>${escapeXml(text)}</t></is></c>""")
                }
                append("</row>")
            }
        }
        val modified = sheetXml.replace("</sheetData>", "$newRows</sheetData>")
        return rebuildZipWithEntry(existingBytes, "xl/worksheets/sheet1.xml", modified)
    }

    /** PDF 追加：添加新页写入文本（系统中文字体） */
    /**
     * PDF 追加（安全版，修复 08-31 闪退）：
     * 路径 A（原实现）：load 旧文档 → 内嵌字体 → 加页 → save。**load 后重存是高危路径**
     * （向已载入文档嵌 Type0 字体再序列化，native 崩不可 catch）。
     * 路径 B（降级）：A 抛 Throwable → 提取旧文本（extractPdf 实测稳定）+ 新内容 → 全新创建
     * （createPdfNew = 新文档单次嵌字体，与 load-doc 重存不同路径）。
     */
    private fun appendPdfSafe(existingBytes: ByteArray, newContent: String): ByteArray {
        try {
            if (findCjkTtf() == null)
                throw IllegalStateException("系统无中文 TTF——PdfBox 路径不可用，走画布重建")
            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(existingBytes)
            try {
                val font = loadChineseFont(doc)
                addPdfPageWithText(doc, font, newContent)
                val out = ByteArrayOutputStream()
                doc.save(out)
                return out.toByteArray()
            } finally { doc.close() }
        } catch (t: Throwable) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "pdf_append_regen: ${t.javaClass.simpleName} ${t.message?.take(40)}")
            val oldText = try {
                extractPdf(existingBytes) ?: ""
            } catch (_: Throwable) { "" }
            if (oldText.isBlank()) throw t  // 旧文提取也失败 → 上抛走统一失败文案
            return createPdfNew(oldText.trimEnd() + "\n\n" + newContent)
        }
    }

    /** 系统中文 TTF 定位（实测 08-31：DroidSansFallback 在新 ROM 可能不存在 → 仅此路径可用 PdfBox） */
    private fun findCjkTtf(): java.io.File? =
        listOf("/system/fonts/DroidSansFallback.ttf", "/system/fonts/DroidSansFallbackFull.ttf")
            .map { java.io.File(it) }.firstOrNull { it.exists() }

    /** PDF 从零创建：有中文 TTF 走 PdfBox；无则切系统画布引擎（Canvas 原生 CJK，writePdf 同族实测可靠） */
    private fun createPdfNew(content: String): ByteArray {
        if (findCjkTtf() == null) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "pdf_engine_canvas: 系统无中文 TTF，PdfDocument 引擎接管")
            return createPdfCanvas(content)
        }
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument()
        try {
            val font = loadChineseFont(doc)
            addPdfPageWithText(doc, font, content)
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        } finally { doc.close() }
    }

    /**
     * 系统画布 PDF 引擎（降级主用，修复 08-31）：
     * android.graphics.pdf + Canvas——中文经系统排版原生支持（不经字体文件，
     * DroidSansFallback 缺失的 ROM 上 PdfBox 必 .notdef 崩）。A4 自动折行分页。
     */
    private fun createPdfCanvas(content: String): ByteArray {
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT
            }
            val pageWidth = 595; val pageHeight = 842; val margin = 50f; val leading = 18f
            val maxWidth = pageWidth - 2 * margin

            // 预排版：按宽度折行
            val lines = mutableListOf<String>()
            content.lines().forEach { raw ->
                if (raw.isBlank()) { lines.add(""); return@forEach }
                var cur = StringBuilder()
                raw.forEach { ch ->
                    if (paint.measureText(cur.toString() + ch) > maxWidth) { lines.add(cur.toString()); cur = StringBuilder() }
                    cur.append(ch)
                }
                if (cur.isNotEmpty()) lines.add(cur.toString())
            }

            var pageNum = 0; var y = 0f
            var page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNum).create())
            var canvas = page.canvas
            lines.forEach { line ->
                if (y + leading > pageHeight - margin) {
                    pdf.finishPage(page)
                    page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNum).create())
                    canvas = page.canvas; y = 0f
                }
                y += leading
                canvas.drawText(line, margin, y, paint)
            }
            pdf.finishPage(page)
            val out = ByteArrayOutputStream()
            pdf.writeTo(out)
            return out.toByteArray()
        } finally { pdf.close() }
    }

    /**
     * 加载 Android 系统中文字体（DroidSansFallback 覆盖 CJK 全集）。
     * 修复（崩溃日志 08-31 13:58）：原 catch(Exception) 拦不住 PDType1Font 类初始化的
     * ExceptionInInitializerError（AFM 资源缺失时 <clinit> 抛 Error）→ 兜底分支自身崩进程。
     * 现：①优先系统 TTF（Type0 路径不触 Standard14 资源）；②全 Throwable 兜底转清晰
     * IllegalStateException（含原因），由外层 appendPdfSafe/createDocument 的 Throwable
     * 兜底承接为结构化失败文案，绝不裸抛 Error。
     */
    private fun loadChineseFont(doc: com.tom_roush.pdfbox.pdmodel.PDDocument): com.tom_roush.pdfbox.pdmodel.font.PDFont {
        return try {
            val fontFile = java.io.File("/system/fonts/DroidSansFallback.ttf")
            if (fontFile.exists()) {
                com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, fontFile)
            } else {
                com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
            }
        } catch (t: Throwable) {
            throw IllegalStateException("PDF 字体不可用（${t.javaClass.simpleName}: ${t.message?.take(60)}）", t)
        }
    }

    /** 添加 PDF 页并写入文本（自动分页，~35 字/行） */
    private fun addPdfPageWithText(doc: com.tom_roush.pdfbox.pdmodel.PDDocument,
                                     font: com.tom_roush.pdfbox.pdmodel.font.PDFont, text: String) {
        val pdRect = com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4
        val fontSize = 12f
        val leading = fontSize * 1.5f
        val margin = 50f
        val charsPerLine = ((pdRect.width - 2 * margin) / fontSize).toInt().coerceAtLeast(20)

        var page = com.tom_roush.pdfbox.pdmodel.PDPage(pdRect)
        doc.addPage(page)
        var stream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
        var y = pdRect.height - margin
        stream.beginText()
        stream.setFont(font, fontSize)
        stream.newLineAtOffset(margin, y)

        for (rawLine in text.lines()) {
            val wrappedLines = if (rawLine.length <= charsPerLine) listOf(rawLine)
            else rawLine.chunked(charsPerLine)
            for (line in wrappedLines) {
                if (y < margin) {
                    stream.endText()
                    stream.close()
                    page = com.tom_roush.pdfbox.pdmodel.PDPage(pdRect)
                    doc.addPage(page)
                    stream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
                    y = pdRect.height - margin
                    stream.beginText()
                    stream.setFont(font, fontSize)
                    stream.newLineAtOffset(margin, y)
                }
                stream.showText(line.ifBlank { " " })
                y -= leading
                stream.newLineAtOffset(0f, -leading)
            }
        }
        stream.endText()
        stream.close()
    }

    // ═══ 精准修改辅助（v6.27）═══

    /** pptx：遍历所有 slide 替换文本 */
    private fun replaceInAllSlides(bytes: ByteArray, find: String, replace: String): ByteArray? {
        val zipIn = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
        val entries = mutableListOf<Pair<String, String>>()
        var entry: java.util.zip.ZipEntry?
        while (zipIn.nextEntry.also { entry = it } != null) {
            entries.add(entry!!.name to zipIn.readBytes().toString(Charsets.UTF_8))
        }
        zipIn.close()
        var changed = false
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zipOut ->
            for ((name, content) in entries) {
                val newContent = if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                    val modified = content.replace(escapeXml(find), escapeXml(replace))
                    if (modified != content) changed = true
                    modified
                } else content
                zipOut.putNextEntry(java.util.zip.ZipEntry(name))
                zipOut.write(newContent.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }
        }
        return if (changed) output.toByteArray() else null
    }

    /** xlsx：设置指定单元格的值（替换已有内容或添加新行） */
    private fun setCellValue(bytes: ByteArray, cellRef: String, value: String): ByteArray? {
        val sheetXml = readZipEntry(bytes, "xl/worksheets/sheet1.xml") ?: return null
        // 尝试匹配已有单元格：<c r="B3" ...>...</c>
        val cellPattern = Regex("(<c r=\"$cellRef\"[^>]*>)(.*?)(</c>)")
        val modified = if (cellPattern.containsMatchIn(sheetXml)) {
            cellPattern.replace(sheetXml) { m ->
                "${m.groupValues[1]}<is><t>${escapeXml(value)}</t></is>${m.groupValues[3]}"
            }
        } else {
            // 单元格不存在 → 解析行号和列号 → 在对应行中插入
            val rowMatch = Regex("(\\d+)$").find(cellRef) ?: return null
            val rowNum = rowMatch.value.toInt()
            val colLetters = cellRef.removeSuffix(rowMatch.value)
            val rowPattern = Regex("(<row r=\"$rowNum\"[^>]*>)(.*?)(</row>)")
            if (rowPattern.containsMatchIn(sheetXml)) {
                rowPattern.replace(sheetXml) { m ->
                    "${m.groupValues[1]}<c r=\"$cellRef\" t=\"inlineStr\"><is><t>${escapeXml(value)}</t></is></c>${m.groupValues[3]}"
                }
            } else {
                // 行也不存在 → 在最后插入新行
                val insertRow = """<row r="$rowNum"><c r="$cellRef" t="inlineStr"><is><t>${escapeXml(value)}</t></is></c></row>"""
                sheetXml.replace("</sheetData>", "$insertRow</sheetData>")
            }
        }
        if (modified == sheetXml) return null
        return rebuildZipWithEntry(bytes, "xl/worksheets/sheet1.xml", modified)
    }
}
