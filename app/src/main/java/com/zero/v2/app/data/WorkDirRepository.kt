package com.zero.v2.app.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero 工作目录仓库 — Step 6.1（D36：SAF 用户选定目录，免权限持续写入）。
 *
 * 用户经系统选择器选定目录（persistUriPermission 持久授权）后：
 *   - writeText(name, content, append) — txt/md 追加或覆盖写
 *   - writePdf(name, textPages) — PdfDocument 生成（A4 自动换页）
 *   - readText(name) / listFiles() — 供 FC 与 Live 词汇闭环（6.7）读取
 *
 * 未选定目录时所有写操作返回失败并携带引导文案（调用方可发起 pickDirectory）。
 */
@Singleton
class WorkDirRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceManager: AgentTraceManager
) {

    companion object {
        private const val PREFS = "zero_work_dir"
        private const val KEY_TREE_URI = "tree_uri"
        /** 单文件写入上限（防 LLM 生成超长内容撑爆存储） */
        private const val MAX_TEXT_BYTES = 2_000_000L
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * 写互斥 — 开发前规范化修复（实测 08-31：FC 并行工具调用对同一文件交错 "wa"/"w" 流，
     * 致追加截断/覆盖残留）。SAF 流非原子，全部变更操作（写/删）串行化（同 Room Mutex 先例）。
     */
    private val writeMutex = Mutex()

    /** 当前工作目录 Uri（未选定 = null） */
    fun currentDirUri(): Uri? =
        prefs.getString(KEY_TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun hasDirectory(): Boolean = currentDirUri() != null

    /**
     * 持久化用户选定的工作目录（SAF 授权）。
     * @return true 保存成功
     */
    fun saveDirectory(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
            "dir_saved: ${uri.lastPathSegment?.take(60)}")
        true
    } catch (e: Exception) {
        traceManager.record("work_dir", NodeType.ERROR, "workdir",
            "dir_save_failed: ${e.message?.take(50)}")
        false
    }

    /** 清除工作目录授权 */
    fun clearDirectory() {
        currentDirUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    /**
     * S9.1-fix2（用户裁定 2026-09-11）：写族统一回执——error=null 时 finalRelPath=**真实落点**
     * （含 SAF 撞名/MIME 改名后的实名与子目录前缀），消灭"回显请求路径"的失真回执。
     */
    data class WriteOutcome(val error: String?, val finalRelPath: String = "")

    /**
     * S9.1-fix2：写族路径化拆解——(父目录Uri, 基名)；父目录不存在则逐级创建（createFolder 同则，
     * 拒 ".."/"."）。基名非法（空/./..）返回 null。
     */
    private fun ensureParentDir(rootDocUri: Uri, fileName: String): Pair<Uri, String>? {
        val parts = RelativePathPolicy.segments(fileName) ?: return null
        val base = parts.lastOrNull() ?: return null
        var cur = rootDocUri
        parts.dropLast(1).forEach { seg ->
            cur = findChild(cur, seg) ?: DocumentsContract.createDocument(
                context.contentResolver, cur,
                DocumentsContract.Document.MIME_TYPE_DIR, seg) ?: return null
        }
        return cur to base
    }

    /** 相对路径前缀（fileName 含 '/' 时保留目录部分，尾随 '/'） */
    private fun dirPrefixOf(fileName: String): String =
        if ('/' in fileName) fileName.trim().substringBeforeLast('/') + "/" else ""

    /**
     * 写入/追加文本文件（txt/md）。S9.1-fix2：支持子目录相对路径（父目录自动创建）；
     * 回执=真实落点（SAF 撞名"(1)"/MIME 改名后的实名）。
     */
    suspend fun writeText(fileName: String, content: String, append: Boolean = true): WriteOutcome =
        writeMutex.withLock { withContext(Dispatchers.IO) {
            if (content.isBlank()) return@withContext WriteOutcome("内容为空")
            if (content.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
                return@withContext WriteOutcome("内容超过单文件上限（2MB），请分段写入")
            }
            val tree = currentDirUri()
                ?: return@withContext WriteOutcome(NOT_CONFIGURED_HINT)
            val prefix = dirPrefixOf(fileName)
            try {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree))
                // S9.1-fix2：原直接以整串（可能含 '/'）在根目录建文件——provider 把 '/' 压成 '_'
                // 落根目录 + 撞名自动 "(1)"，回执再回显请求路径 → 双重失真（Zero agent 实测）
                val (parentDir, base) = ensureParentDir(docUri, fileName)
                    ?: return@withContext WriteOutcome("文件名非法: $fileName")
                val existing = findChild(parentDir, base)
                when {
                    // 追加到已有文件：读旧合并 + "w" 全量重写（appendSmart——"wa" 追加流语义
                    // 由 provider 自行决定不可信，2026-09-18 真机实测静默截断丢数据后移除）
                    append && existing != null -> {
                        val err = appendSmart(existing, base, content)
                        WriteOutcome(err, prefix + base)
                    }
                    // 覆盖已有文件：writeFullBytes（"w" 截断语义 provider 不保证——实测原位
                    // 覆写不 truncate 残留旧文尾，2026-09-18 二轮修复；原 createDocument 路径
                    // 另有 SAF 撞名 "(1)" 问题，均已在 writeFullBytes 内消解）
                    !append && existing != null -> {
                        val bytes = content.toByteArray(Charsets.UTF_8)
                        val err = writeFullBytes(existing, bytes)
                        if (err == null) traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                            "text_written: $prefix$base append=false(overwrite,trunc) bytes=${bytes.size}")
                        WriteOutcome(err, prefix + base)
                    }
                    else -> {
                        // 新建：失败即删空壳——绝不留 0 字节文件污染目录（用户 P1 明确要求）
                        var created: Uri? = null
                        try {
                            created = DocumentsContract.createDocument(
                                context.contentResolver, parentDir, "text/plain", base)
                                ?: return@withContext WriteOutcome("创建文件失败（目录权限可能已失效）")
                            // v6.8 根因修复：SAF 可能按 MIME 改名（如 .md 被追加 .txt）——renameDocument
                            // 返回【新 Uri】，旧代码弃用返回值继续用改名前 Uri 打流 →
                            // "Failed to determine if primary:..." + 0 字节空壳（.md 文件必现，trace 实证）
                            val target = renameIfChanged(created, base)
                            val out = context.contentResolver.openOutputStream(target, "w")
                                ?: throw IllegalStateException("打开输出流失败")
                            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                            // 真实落点：改名后的实名（v6.8 同因）+ 目录前缀
                            val finalName = queryDisplayName(target) ?: base
                            traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                                "text_written: $prefix$finalName append=$append(create) bytes=${content.toByteArray(Charsets.UTF_8).size}")
                            WriteOutcome(null, prefix + finalName)
                        } catch (e: Exception) {
                            created?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                traceManager.record("work_dir", NodeType.ERROR, "workdir",
                    "text_write_failed: $fileName ${e.message?.take(50)}")
                WriteOutcome("写入失败: ${e.message?.take(60)}")
            }
        } }

    /**
     * 追加写（2026-09-18 真机反馈修复：数据丢失级）。
     * 原 "wa" 直写不可靠——openOutputStream 的 mode 语义由各 DocumentsProvider 自行实现，
     * 部分 provider 对 "wa" **不抛异常却按截断打开** → append=true 只留新段、原内容不可恢复
     * 丢失（知识库写入路径实测）；且旧降级链把"读旧失败"吞成空串再全量重写，同样丢原文。
     * 现恒定走 SAF 上唯一语义可靠路径：读旧（带上限）→ 内存合并 → "w" 全量重写；
     * 读旧失败**拒写**并留痕（E28——宁可不写，绝不与空串合并覆盖原内容）。
     */
    private fun appendSmart(existing: Uri, fileName: String, content: String): String? {
        val old: String = try {
            val ins = context.contentResolver.openInputStream(existing)
                ?: return appendReadFailed(fileName, "openInputStream null")
            ins.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var overLimit = false
                while (!overLimit) {
                    val n = s.read(chunk)
                    if (n <= 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_TEXT_BYTES) overLimit = true
                }
                if (overLimit) return "目标文件已超单文件上限（2MB），无法安全追加——请另建分卷文件"
                buf.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return appendReadFailed(fileName, "${e.javaClass.simpleName} ${e.message?.take(40)}")
        }
        val merged = (old + content).toByteArray(Charsets.UTF_8)
        if (merged.size > MAX_TEXT_BYTES) return "内容超过单文件上限（2MB），请分段写入"
        val err = writeFullBytes(existing, merged)
        if (err == null) {
            traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                "text_written: $fileName append=true(merge rewrite) " +
                        "oldBytes=${old.toByteArray(Charsets.UTF_8).size} totalBytes=${merged.size}")
        }
        return err
    }

    /** 读旧失败 → 拒写保护原内容（2026-09-18：追加前置失败绝不演变为覆盖）+ E28 留痕 */
    private fun appendReadFailed(fileName: String, why: String?): String {
        traceManager.record("work_dir", NodeType.ERROR, "workdir",
            "text_append_read_failed: $fileName $why（拒写保护原内容）")
        return "读取原文件失败，已拒绝写入以保护原内容（$fileName：$why）"
    }

    /**
     * 全量覆写既有文档（2026-09-18 真机反馈二轮：覆盖不截断）。
     * openOutputStream(uri, "w") 的截断语义同样由 provider 自行实现——实测部分 provider
     * 按 "rw" 原位覆写打开（不 truncate）：写短内容后文件长度不变、尾部残留旧文（回执
     * 正常、零痕迹；知识库检索会命中早该消失的旧段）。文件型 provider 的流是
     * ParcelFileDescriptor.AutoCloseOutputStream（FileOutputStream 子类）——写前
     * channel.truncate(0) 确定性清空；管道型 provider（云端等）整段替换无残留语义，
     * cast 不中自然跳过。三个既有文档覆写点统一走此 helper（writeText 覆盖分支 /
     * appendSmart 合并重写 / writeBinary——短 docx 覆盖长文件会留损坏 ZIP 尾）。
     */
    private fun writeFullBytes(target: Uri, bytes: ByteArray): String? = try {
        val out = context.contentResolver.openOutputStream(target, "w")
            ?: return "打开输出流失败"
        out.use { o ->
            (o as? java.io.FileOutputStream)?.channel?.truncate(0L)
            o.write(bytes)
        }
        null
    } catch (e: Exception) {
        traceManager.record("work_dir", NodeType.ERROR, "workdir",
            "write_full_failed: ${e.message?.take(50)}")
        "写入失败: ${e.message?.take(60)}"
    }

    /**
     * 读取文本文件（6.7 词汇闭环读取用）。
     * @return 文本内容；文件不存在/失败 = null
     */
    suspend fun readText(fileName: String): String? = withContext(Dispatchers.IO) {
        currentDirUri() ?: return@withContext null
        try {
            // S9.1-fix：原仅根目录精确名——子目录文本文件读不到；接入统一引用解析
            val (child, _) = resolveFileRef(fileName)
            child ?: return@withContext null
            context.contentResolver.openInputStream(child)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) { null }
    }

    /** 分页读取结果（N1.1：LLM 分页翻读大文件用，read_work_file offset 契约） */
    data class PagedText(
        val content: String,   // 本页内容（≤length 字）
        val totalLen: Int,     // 全文长度（字）
        val offset: Int,       // 本页起始偏移
        val hasMore: Boolean   // 是否还有后文
    )

    /**
     * 分页读取文本文件（N1.1）——旧调用方沿用 readText(fileName)；分页翻读走本重载。
     * SAF 无 seek：全读后内存切片（写入侧已有 2MB 上限，读取同量级可控）。
     * offset 越界自动钳到 [0, totalLen]。
     * @return null = 文件不存在/失败
     */
    suspend fun readText(fileName: String, offset: Int, length: Int): PagedText? =
        withContext(Dispatchers.IO) {
            val full = readText(fileName) ?: return@withContext null
            val safeOffset = offset.coerceIn(0, full.length)
            val end = minOf(safeOffset + length.coerceAtLeast(1), full.length)
            PagedText(
                content = full.substring(safeOffset, end),
                totalLen = full.length,
                offset = safeOffset,
                hasMore = end < full.length
            )
        }

    /**
     * 读取二进制文件原始字节（Step 8.6：office 文件读取用）。
     * v6.26b: 依次搜索根目录 → downloads/ 子目录（download_file 落盘子目录）。
     * @return ByteArray；文件不存在/失败/超限 = null
     */
    suspend fun readBinary(fileName: String, maxBytes: Long = 5L * 1024 * 1024): ByteArray? =
        withContext(Dispatchers.IO) {
            val tree = currentDirUri() ?: return@withContext null
            try {
                // 根目录找 → downloads/ 子目录找（S9.1-fix：升级为统一引用解析——路径精确+全树唯一）
                val (child, _) = resolveFileRef(fileName)
                child ?: return@withContext null
                context.contentResolver.openInputStream(child)?.use { ins ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(chunk)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) return@withContext null  // 超限
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
            } catch (_: Exception) { null }
        }

    /**
     * 打开工作目录文件输入流（根 → downloads/ 回退；C3.4b 上传流式通道——大文件免全量进内存）。
     * @return (流, 字节长度；未知=-1 → 调用方走 chunked)；调用方负责 close。未找到 = null
     */
    suspend fun openReadStream(fileName: String): Pair<InputStream, Long>? =
        withContext(Dispatchers.IO) {
            val tree = currentDirUri() ?: return@withContext null
            try {
                // S9.1-fix：升级为统一引用解析（原剥路径仅 root→downloads 两级——深层子目录不可达）
                val (child, _) = resolveFileRef(fileName)
                child ?: return@withContext null
                val len = try {
                    context.contentResolver.openAssetFileDescriptor(child, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) {
                    -1L
                }
                context.contentResolver.openInputStream(child)?.let { it to len }
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 写二进制文件（Step 8.6：office 文件生成用）。S9.1-fix2：支持子目录相对路径 +
     * 回执真实落点（用户裁定：写族不设子文件夹限制）。
     */
    suspend fun writeBinary(fileName: String, mime: String, bytes: ByteArray): WriteOutcome =
        writeMutex.withLock { withContext(Dispatchers.IO) {
            val tree = currentDirUri()
                ?: return@withContext WriteOutcome(NOT_CONFIGURED_HINT)
            val prefix = dirPrefixOf(fileName)
            var created: Uri? = null
            try {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree))
                val (parentDir, base) = ensureParentDir(docUri, fileName)
                    ?: return@withContext WriteOutcome("文件名非法: $fileName")
                // 修复（实测 08-31）：优先覆盖已存在文件——原实现无条件 createDocument，
                // SAF 撞名自动"(1)"副本，docx/xlsx 追加/编辑因此生成副本而非写回
                val existing = findChild(parentDir, base)
                val target: Uri
                val isOverwrite: Boolean
                if (existing != null) {
                    target = existing; isOverwrite = true
                } else {
                    val doc = DocumentsContract.createDocument(context.contentResolver, parentDir, mime, base)
                        ?: return@withContext WriteOutcome("创建文件失败（目录权限可能已失效）")
                    created = doc; target = doc; isOverwrite = false
                }
                val finalName = if (isOverwrite) base else queryDisplayName(target) ?: base
                // writeFullBytes：既有文档覆盖须确定性截断（短内容覆长文件残留旧尾 → ZIP 损坏）
                val writeErr = writeFullBytes(target, bytes)
                if (writeErr != null) throw IllegalStateException(writeErr)
                traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                    "binary_written: $prefix$finalName bytes=${bytes.size} overwrite=$isOverwrite")
                WriteOutcome(null, prefix + finalName)
            } catch (e: Exception) {
                created?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                WriteOutcome("写入失败: ${e.message?.take(60)}")
            }
        } }

    /** 工作目录文件信息（v6.7 可视化 UI 用） */
    data class WorkFileInfo(
        val name: String,
        val uri: Uri,
        val sizeBytes: Long,
        val lastModified: Long,
        val isDirectory: Boolean = false,
    )

    /** 列出指定目录的直接子项（目录优先、按名称；未设目录 = 空表）。 */
    suspend fun listFiles(relativePath: String = ""): List<WorkFileInfo> = withContext(Dispatchers.IO) {
        val tree = currentDirUri() ?: return@withContext emptyList()
        val root = rootDocUri() ?: return@withContext emptyList()
        val directory = resolvePath(root, relativePath) ?: error("目录不存在或授权已失效")
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(directory))
            val out = mutableListOf<WorkFileInfo>()
            val cursor = context.contentResolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null) ?: error("目录提供者未返回结果")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out.add(WorkFileInfo(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        sizeBytes = c.getLong(2),
                        lastModified = c.getLong(3),
                        isDirectory = c.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
            out.sortedWith(compareByDescending<WorkFileInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /**
     * 删除工作目录文件（v6.7 FC/UI 管理）。
     * @return 成功 = null；失败 = 用户可读文案
     */
    suspend fun deleteFile(fileName: String): String? = writeMutex.withLock { withContext(Dispatchers.IO) {
        val tree = currentDirUri() ?: return@withContext NOT_CONFIGURED_HINT
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree))
            // S9.1-fix：路径/文件名/全树唯一三级解析（原根目录精确匹配——子目录文件永远删不掉）
            val (child, ambiguity) = resolveFileRef(fileName)
            if (child == null) {
                return@withContext if (ambiguity != null)
                    "同名文件存在于 ${ambiguity.size} 处，请带路径指定其一: ${ambiguity.take(3).joinToString("、")}"
                else "文件不存在: $fileName"
            }
            // 文件夹守卫：目录删除走 delete_folder（递归语义，含审计清单）
            if (queryMime(child) == DocumentsContract.Document.MIME_TYPE_DIR)
                return@withContext "「$fileName」是文件夹——请用 delete_folder（支持 force 递归删除）"
            if (DocumentsContract.deleteDocument(context.contentResolver, child)) {
                traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir", "file_deleted: $fileName")
                null
            } else "删除失败: $fileName"
        } catch (e: Exception) { "删除失败: ${e.message?.take(40)}" }
    } }

    /**
     * 生成 PDF（A4 自动换页，正文逐页排版）。S9.1-fix2：子目录相对路径 + 真实落点回执。
     */
    suspend fun writePdf(fileName: String, title: String, text: String): WriteOutcome =
        writeMutex.withLock { withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext WriteOutcome("内容为空")
            val tree = currentDirUri()
                ?: return@withContext WriteOutcome(NOT_CONFIGURED_HINT)
            val prefix = dirPrefixOf(fileName)
            val finalRelPath = try {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree))
                val (parentDir, base) = ensureParentDir(docUri, fileName)
                    ?: return@withContext WriteOutcome("文件名非法: $fileName")
                val pdf = PdfDocument()
                try {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 12f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    }
                    val titlePaint = Paint(paint).apply {
                        textSize = 18f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    val pageWidth = 595   // A4 @72dpi
                    val pageHeight = 842
                    val margin = 48f
                    val lineSpacing = 8f
                    val maxWidth = pageWidth - 2 * margin

                    // 预排版：按宽度折行
                    val lines = buildList {
                        measureLines(title, titlePaint, maxWidth).forEach { add(Triple(it, titlePaint, 28f)) }
                        add(Triple("", paint, 16f))
                        text.split('\n').forEach { raw ->
                            if (raw.isBlank()) add(Triple("", paint, 12f))
                            else measureLines(raw, paint, maxWidth).forEach { add(Triple(it, paint, 16f)) }
                        }
                    }
                    var pageNum = 0
                    var y = 0f
                    var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNum).create())
                    var canvas = page.canvas
                    lines.forEach { (line, p, size) ->
                        if (y + size + lineSpacing > pageHeight - margin) {
                            pdf.finishPage(page)
                            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNum).create())
                            canvas = page.canvas
                            y = 0f
                        }
                        y += size + lineSpacing
                        canvas.drawText(line, margin, y, p)
                    }
                    pdf.finishPage(page)

                    val created = DocumentsContract.createDocument(
                        context.contentResolver, parentDir, "application/pdf", base)
                        ?: return@withContext WriteOutcome("创建 PDF 失败")
                    val target = renameIfChanged(created, base)
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        pdf.writeTo(out)
                    } ?: return@withContext WriteOutcome("打开输出流失败")
                    prefix + (queryDisplayName(target) ?: base)
                } finally {
                    pdf.close()
                }
            } catch (e: Exception) {
                traceManager.record("work_dir", NodeType.ERROR, "workdir",
                    "pdf_write_failed: $fileName ${e.message?.take(50)}")
                return@withContext WriteOutcome("PDF 生成失败: ${e.message?.take(60)}")
            }
            traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                "pdf_written: $finalRelPath pages=1")
            WriteOutcome(null, finalRelPath)
        } }

    // ═══ 内部 ═══

    /** 二进制流式写入结果（8.1 download_file 用） */
    data class BinaryResult(
        val error: String?,          // 成功 = null
        val bytesWritten: Long,
        val finalName: String        // SAF 撞名自动序号后的实际文件名
    )

    /**
     * 流式写入二进制文件到指定子目录（Step 8.1：download_file 落盘通道）。
     * 重名走 SAF 原生撞名自动序号（"xxx (1).pdf"）——不做覆盖（下载语义：新文件不 silently 覆盖旧文件）。
     * 超限/中断 → 删除半成品，不留污染。
     * @return BinaryResult（error 为用户可读文案或 null）
     */
    suspend fun writeBinaryStream(
        subdir: String,
        fileName: String,
        mime: String,
        maxBytes: Long,
        input: java.io.InputStream
    ): BinaryResult = writeMutex.withLock { withContext(Dispatchers.IO) {
        val tree = currentDirUri()
            ?: return@withContext BinaryResult(NOT_CONFIGURED_HINT, 0, fileName)
        var created: Uri? = null
        try {
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree))
            val dirUri = ensureDir(rootDoc, subdir)
                ?: return@withContext BinaryResult("创建子目录失败: $subdir", 0, fileName)
            val doc = DocumentsContract.createDocument(
                context.contentResolver, dirUri, mime.ifBlank { "application/octet-stream" }, fileName)
                ?: return@withContext BinaryResult("创建文件失败（目录权限可能已失效）", 0, fileName)
            created = doc
            // SAF 撞名自动序号后的实际名（供返回与 UI 展示一致）
            val finalName = queryDisplayName(doc) ?: fileName
            var written = 0L
            val buf = ByteArray(64 * 1024)
            context.contentResolver.openOutputStream(doc, "w")?.use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    written += n
                    if (written > maxBytes) {
                        out.flush()
                        throw IllegalStateException("超过单文件大小上限（${maxBytes / 1_048_576}MB）")
                    }
                    out.write(buf, 0, n)
                }
            } ?: throw IllegalStateException("打开输出流失败")
            traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                "binary_written: $subdir/$finalName bytes=$written")
            BinaryResult(null, written, finalName)
        } catch (e: Exception) {
            created?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            traceManager.record("work_dir", NodeType.ERROR, "workdir",
                "binary_write_failed: $fileName ${e.message?.take(50)}")
            BinaryResult("下载失败: ${e.message?.take(60)}", 0, fileName)
        }
    } }

    /** 查询文档显示名（SAF 撞名序号后） */
    private fun queryDisplayName(docUri: Uri): String? = try {
        context.contentResolver.query(
            docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    /** 查找或创建子目录（已存在同名文件而非目录时返回 null 由调用方报错） */
    private fun ensureDir(rootDocUri: Uri, name: String): Uri? {
        findChild(rootDocUri, name)?.let { return it }
        return try {
            DocumentsContract.createDocument(
                context.contentResolver, rootDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR, name)
        } catch (_: Exception) { null }
    }

    /** 在指定父目录下按名查找子文档（v8.1 泛化：查询父目录自身的孩子；旧实现恒查根目录） */
    private fun findChild(parentDocUri: Uri, displayName: String): Uri? = try {
        val children = context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentDocUri, DocumentsContract.getDocumentId(parentDocUri)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)
        children?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            var found: Uri? = null
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == displayName) {
                    found = DocumentsContract.buildDocumentUriUsingTree(parentDocUri, c.getString(idIdx))
                    break
                }
            }
            found
        }
    } catch (_: Exception) { null }

    /** SAF 按 MIME 自动追加扩展名时恢复用户指定文件名 */
    /** 改名为用户指定名（SAF 可能按 MIME 加后缀）。v6.8：返回改名后的有效 Uri——
     *  renameDocument 会换新 Uri，旧 Uri 随即失效（继续用即 "Failed to determine if primary"） */
    private fun renameIfChanged(docUri: Uri, desiredName: String): Uri {
        return try {
            val name = context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return docUri
            if (name != desiredName) {
                DocumentsContract.renameDocument(context.contentResolver, docUri, desiredName)
                    ?: docUri
            } else docUri
        } catch (_: Exception) { docUri }
    }

    private fun measureLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        text.forEach { ch ->
            if (paint.measureText(current.toString() + ch) > maxWidth) {
                result.add(current.toString())
                current = StringBuilder()
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    // ═══ S9.1（D123）：文件夹操作——move / createFolder / deleteTree / listTree ═══

    /** 目录树节点（listTree 用；LLM 空间感知载体） */
    data class TreeNode(
        val path: String,          // 相对路径（如 "downloads/a.pdf"）
        val name: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val lastModified: Long
    )

    /** 递归删除结果（G15 审计载体） */
    data class TreeDeleteResult(
        val refused: Boolean,          // true = 非空未带 force（统计已填，供 LLM/用户决策）
        val fileCount: Int, val dirCount: Int, val totalBytes: Long,
        val fileManifest: List<String>, // 文件清单（拒绝时=将删；成功时=已删）
        val failed: List<String>,       // 删除失败残留路径
        val error: String?              // 失败文案（refused 时为拒绝理由）
    )

    /** 相对路径解析（逐级 findChild；拒绝 ".."/"." 段——D57 无路径穿越） */
    private fun resolvePath(rootDocUri: Uri, relPath: String): Uri? {
        var cur = rootDocUri
        RelativePathPolicy.segments(relPath, allowEmpty = true)?.forEach { seg ->
            cur = findChild(cur, seg) ?: return null
        } ?: return null
        return cur
    }

    /**
     * S9.1-fix（Zero agent 实测 2026-09-11）：文件引用统一解析——delete/read 族共享。
     * 形态：① 含 '/' 的相对路径 → resolvePath 精确解析（拒 ".." 穿越）
     *      ② 纯文件名 → 根 → downloads/ 两级（v6.26b 既有语义，快路径在前）
     *      ③ 未命中 → 全树按名唯一匹配（S9.1 目录树后子目录文件可达；多命中报歧义防误删）
     * @return (uri, null)=命中；(null, null)=不存在；(null, 同名清单)=歧义
     */
    private suspend fun resolveFileRef(ref: String): Pair<Uri?, List<String>?> {
        val clean = ref.trim()
        if (clean.isEmpty()) return null to null
        val root = rootDocUri() ?: return null to null
        if ('/' in clean) return resolvePath(root, clean) to null
        findChild(root, clean)?.let { return it to null }
        findChild(root, "downloads")?.let { dl -> findChild(dl, clean)?.let { return it to null } }
        val hits = listTree().filter { !it.isDir && it.name == clean }
        return when (hits.size) {
            0 -> null to null
            1 -> resolvePath(root, hits.first().path) to null
            else -> null to hits.map { it.path }
        }
    }

    private fun rootDocUri(): Uri? {
        val tree = currentDirUri() ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
    }

    /**
     * MU1.2（音乐轨设计 §九 薄约定）：子树内相对路径解析为文档 Uri——resolvePath 的受控出口。
     * 拒穿越（".." / "." 段，D57）；未设目录/子树根不存在/路径不存在返回 null。
     * @param subdir 工作目录一级子目录名（如 "llmmusic"）
     * @param relativePath 相对该子目录的路径（空串=子树根本身）
     */
    fun resolveInSubtree(subdir: String, relativePath: String): Uri? {
        val root = rootDocUri() ?: return null
        val subRoot = resolvePath(root, subdir) ?: return null
        val clean = relativePath.trim()
        return if (clean.isEmpty()) subRoot else resolvePath(subRoot, clean)
    }

    private fun queryMime(docUri: Uri): String? = try {
        context.contentResolver.query(
            docUri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    /** 子项详情（一次 query 取全列——listTree/copy/删清单共用） */
    private class ChildEntry(
        val uri: Uri, val name: String,
        val isDir: Boolean, val size: Long, val lastModified: Long
    )

    private fun listChildrenDetailed(parentDocUri: Uri, strict: Boolean = false): List<ChildEntry> = try {
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentDocUri, DocumentsContract.getDocumentId(parentDocUri)),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null)?.use { c ->
            val out = mutableListOf<ChildEntry>()
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out.add(ChildEntry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(parentDocUri, c.getString(0)),
                    name = name,
                    isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = c.getLong(3),
                    lastModified = c.getLong(4)))
            }
            out
        } ?: if (strict) error("Directory query returned no cursor") else emptyList()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (failure: Exception) { if (strict) throw failure else emptyList() }

    /**
     * 移动/重命名（S9.1/D123）：同目录 renameDocument 快路径（SR7）；跨目录/改名失败 =
     * copy+delete 兜底（SR7 语义）。路径均为工作目录内相对路径（可含子目录）。
     * 目标已存在 → 拒绝不覆盖。@return 成功 = null；失败 = 用户可读文案
     */
    suspend fun moveEntry(relPath: String, target: String): String? =
        writeMutex.withLock { withContext(Dispatchers.IO) {
            if (relPath.isBlank() || target.isBlank()) return@withContext "路径不能为空"
            val rootDoc = rootDocUri() ?: return@withContext NOT_CONFIGURED_HINT
            try {
                val src = resolvePath(rootDoc, relPath) ?: return@withContext "不存在: $relPath"
                val srcParent = if ('/' in relPath) resolvePath(rootDoc, relPath.substringBeforeLast('/'))
                else rootDoc
                val dstParent: Uri
                val dstName: String
                if ('/' in target) {
                    dstParent = resolvePath(rootDoc, target.substringBeforeLast('/'))
                        ?: return@withContext "目标目录不存在: ${target.substringBeforeLast('/')}"
                    dstName = target.substringAfterLast('/')
                } else { dstParent = srcParent ?: rootDoc; dstName = target }
                if (dstName.isBlank() || dstName == "." || dstName == ".." || dstName.contains('/'))
                    return@withContext "非法目标名: $target"
                if (findChild(dstParent, dstName) != null)
                    return@withContext "目标已存在: $target（不覆盖）"

                val sameDir = srcParent != null && dstParent != null &&
                        DocumentsContract.getDocumentId(srcParent) == DocumentsContract.getDocumentId(dstParent)
                var ok = false
                var mode = "copy+delete"
                if (sameDir) {
                    // SR7 快路径：同目录 rename（renameDocument 返回新 Uri，弃用旧 Uri）
                    ok = try {
                        DocumentsContract.renameDocument(context.contentResolver, src, dstName) != null
                    } catch (_: Exception) { false }
                    if (ok) mode = "rename"
                }
                if (!ok) {
                    // SR7 兜底：跨目录 = 递归复制 + 删源
                    ok = copyRecursive(src, dstParent, dstName) &&
                            DocumentsContract.deleteDocument(context.contentResolver, src)
                }
                if (ok) {
                    traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                        "entry_moved: $relPath -> $target ($mode)")
                    null
                } else "移动失败: $relPath -> $target"
            } catch (e: Exception) {
                traceManager.record("work_dir", NodeType.ERROR, "workdir",
                    "move_failed: $relPath -> $target ${e.message?.take(50)}")
                "移动失败: ${e.message?.take(60)}"
            }
        } }

    /** 递归复制（目录=重建+递归；文件=流复制）。@return 全部成功 = true */
    private fun copyRecursive(src: Uri, dstParent: Uri, name: String): Boolean = try {
        val mime = queryMime(src) ?: return false
        val created = DocumentsContract.createDocument(
            context.contentResolver, dstParent, mime, name) ?: return false
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            listChildrenDetailed(src).all { child -> copyRecursive(child.uri, created, child.name) }
        } else {
            val copied = context.contentResolver.openInputStream(src)?.use { ins ->
                context.contentResolver.openOutputStream(created, "w")?.use { out ->
                    ins.copyTo(out); true
                } ?: false
            } ?: false
            if (!copied) runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, created)
            }
            copied
        }
    } catch (_: Exception) { false }

    /**
     * 新建文件夹（S9.1/D123：含嵌套路径，逐级 ensureDir；同名文件存在 → 报错）。
     * @return 成功 = null；失败 = 用户可读文案
     */
    suspend fun createFolder(relPath: String): String? =
        writeMutex.withLock { withContext(Dispatchers.IO) {
            if (relPath.isBlank()) return@withContext "路径不能为空"
            val rootDoc = rootDocUri() ?: return@withContext NOT_CONFIGURED_HINT
            try {
                var cur = rootDoc
                relPath.split('/').map { it.trim() }.filter { it.isNotEmpty() }.forEach { seg ->
                    if (seg == ".." || seg == ".") return@withContext "非法路径: $relPath"
                    val existing = findChild(cur, seg)
                    cur = if (existing != null) {
                        if (queryMime(existing) != DocumentsContract.Document.MIME_TYPE_DIR)
                            return@withContext "同名文件已存在: $seg"
                        existing
                    } else DocumentsContract.createDocument(
                        context.contentResolver, cur,
                        DocumentsContract.Document.MIME_TYPE_DIR, seg)
                        ?: return@withContext "创建文件夹失败: $seg（权限可能已失效）"
                }
                traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                    "folder_created: $relPath")
                null
            } catch (e: Exception) {
                traceManager.record("work_dir", NodeType.ERROR, "workdir",
                    "folder_create_failed: $relPath ${e.message?.take(50)}")
                "创建文件夹失败: ${e.message?.take(60)}"
            }
        } }

    /** 后序收集删除清单（孩子先于父目录——顺序删除即满足 SR7 空目录要求） */
    private fun collectDeleteList(
        docUri: Uri, path: String,
        files: MutableList<Pair<Uri, String>>, dirs: MutableList<Pair<Uri, String>>
    ): Long {
        var bytes = 0L
        val isDir = queryMime(docUri) == DocumentsContract.Document.MIME_TYPE_DIR
        if (isDir) {
            listChildrenDetailed(docUri).forEach { child ->
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                bytes += collectDeleteList(child.uri, childPath, files, dirs)
            }
            dirs.add(docUri to path)
        } else {
            files.add(docUri to path)
            bytes += try {
                context.contentResolver.openAssetFileDescriptor(docUri, "r")?.use { it.length } ?: 0L
            } catch (_: Exception) { 0L }
        }
        return bytes
    }

    /**
     * 递归删除（S9.1/D123：force 控制；G15 审计——先收集清单（数量/大小/文件列表），
     * 拒绝时返回统计供决策，成功时 manifest=已删清单 + RunLog 记档）。
     */
    suspend fun deleteTree(relPath: String, force: Boolean): TreeDeleteResult =
        writeMutex.withLock { withContext(Dispatchers.IO) {
            if (RelativePathPolicy.segments(relPath) == null) {
                return@withContext TreeDeleteResult(false, 0, 0, 0, emptyList(), emptyList(),
                    "不允许删除工作根目录或非法路径，请指定工作目录内的子目录")
            }
            val rootDoc = rootDocUri()
                ?: return@withContext TreeDeleteResult(false, 0, 0, 0, emptyList(), emptyList(), NOT_CONFIGURED_HINT)
            try {
                val target = resolvePath(rootDoc, relPath)
                    ?: return@withContext TreeDeleteResult(false, 0, 0, 0, emptyList(), emptyList(), "路径不存在: $relPath")
                val files = mutableListOf<Pair<Uri, String>>()
                val dirs = mutableListOf<Pair<Uri, String>>()
                val bytes = collectDeleteList(target, relPath.substringAfterLast('/'), files, dirs)
                // collectDeleteList includes the target itself; only descendants make a directory nonempty.
                if ((files.isNotEmpty() || dirs.size > 1) && !force) {
                    return@withContext TreeDeleteResult(
                        refused = true, files.size, dirs.size, bytes,
                        files.map { it.second }.take(30), emptyList(),
                        "非空文件夹（${files.size} 文件 / ${dirs.size} 子目录 / ${bytes / 1024}KB）——需 force=true 确认递归删除")
                }
                val deleted = mutableListOf<String>()
                val failed = mutableListOf<String>()
                (files + dirs).forEach { (uri, p) ->
                    if (DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                        if (files.any { it.second == p }) deleted.add(p)
                    } else failed.add(p)
                }
                traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
                    "tree_deleted: $relPath files=${files.size} dirs=${dirs.size} bytes=$bytes " +
                            "failed=${failed.size} manifest=${deleted.take(30).joinToString(",") { it.take(40) }}")
                TreeDeleteResult(
                    refused = false, files.size, dirs.size, bytes, deleted,
                    failed, if (failed.isEmpty()) null else "部分条目删除失败 ${failed.size} 项（清单见返回）")
            } catch (e: Exception) {
                traceManager.record("work_dir", NodeType.ERROR, "workdir",
                    "tree_delete_failed: $relPath ${e.message?.take(50)}")
                TreeDeleteResult(false, 0, 0, 0, emptyList(), emptyList(), "删除失败: ${e.message?.take(60)}")
            }
        } }

    /**
     * 目录树（S9.1/D123：含文件夹标注 dir/file；SR7 深度上限 5 防逐级递归性能坑）。
     * @return 扁平节点表（path 含父路径，渲染层按 '/' 缩进）
     */
    suspend fun listTree(maxDepth: Int = 5): List<TreeNode> = listTreeSnapshot(maxDepth).nodes

    data class TreeListing(val nodes: List<TreeNode>, val complete: Boolean)

    /** Completeness is required before an index may infer deletion from an absent path. */
    suspend fun listTreeSnapshot(maxDepth: Int = 5): TreeListing = withContext(Dispatchers.IO) {
        val rootDoc = rootDocUri() ?: return@withContext TreeListing(emptyList(), false)
        val out = mutableListOf<TreeNode>()
        var truncatedDirs = 0
        var complete = true
        fun walk(dir: Uri, prefix: String, depth: Int) {
            val children = try { listChildrenDetailed(dir, strict = true) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { complete = false; return }
            children.forEach { child ->
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                out.add(TreeNode(path, child.name, child.isDir, child.size, child.lastModified))
                if (child.isDir) {
                    if (depth + 1 < maxDepth) walk(child.uri, path, depth + 1) else truncatedDirs++
                }
            }
        }
        try { walk(rootDoc, "", 0) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { complete = false }
        traceManager.record("work_dir", NodeType.SYSTEM_EVENT, "workdir",
            "tree_listed: ${out.size} truncatedDirs=$truncatedDirs maxDepth=$maxDepth")
        TreeListing(out, complete && truncatedDirs == 0)
    }
}

private const val NOT_CONFIGURED_HINT =
    "尚未设置 Zero 工作目录。请引导用户到 资料 → 知识与工作文件选择工作目录后重试。"
