package com.zero.v2.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.zero.v2.core.data.db.ZeroDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.dao.AutomationDao
import com.zero.v2.core.data.dao.CharacterDao
import com.zero.v2.core.data.dao.ConversationDao
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.dao.MessageDao
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.core.data.entity.CharacterEntity
import com.zero.v2.core.data.entity.ConversationEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MessageEntity
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 导入统计 */
data class ImportStats(
    val conversations: Int, val messages: Int, val characters: Int,
    val memories: Int, val automations: Int, val skipped: Int, val memoryReviews: Int = 0, val chronicleSources: Int = 0
)

/**
 * 数据导入器 — Step 5.6。
 *
 * 语义: 记忆及复核保留本机同ID状态；其余域按既有覆盖合并，复核来源消息不回退。
 * 校验: `__format == zero_backup` 且 version ≤ 当前支持版本；逐条容错（坏条跳过计 skipped）。
 * 导入的记忆无向量 — 启动时 Embedding 回填 Job 自动重嵌（4.6-pre）。
 */
@Singleton
class DataImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val characterDao: CharacterDao,
    private val memoryDao: MemoryDao,
    private val automationDao: AutomationDao,
    private val traceManager: AgentTraceManager,
    private val database: ZeroDatabase
) {

    companion object {
        private const val SUPPORTED_VERSION = 6
    }
    private val importMutex = Mutex()

    suspend fun importFrom(uri: Uri, progress: (BackupProgress) -> Unit = {}): Result<ImportStats> = withContext(Dispatchers.IO) {
        try {
            progress(BackupProgress("正在读取备份"))
            currentCoroutineContext().ensureActive()
            val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 32 * 1024 * 1024) { "备份超过32MiB导入上限" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
                ?: return@withContext Result.failure(IllegalStateException("无法读取文件"))

            // 格式校验
            progress(BackupProgress("正在校验备份"))
            currentCoroutineContext().ensureActive()
            val root = JSONObject(raw)
            if (root.optString("__format") != "zero_backup") {
                return@withContext Result.failure(IllegalArgumentException("不是 Zero 备份文件（缺少 __format 标识）"))
            }
            val version = root.optInt("version", 1)
            if (version > SUPPORTED_VERSION) {
                return@withContext Result.failure(IllegalArgumentException(
                    "备份版本 $version 高于本应用支持（$SUPPORTED_VERSION），请先升级 App"))
            }
            require(version in 1..SUPPORTED_VERSION) { "备份版本无效" }

            val reviews = MemoryReviewBackup.decode(root)
            val chronicle = ChronicleBackup.decode(root)
            val derivations = MemoryDerivationBackup.decode(root)
            val stats = importMutex.withLock {
            currentCoroutineContext().ensureActive()
            progress(BackupProgress("正在事务恢复，请等待完成", cancellable = false))
            currentCoroutineContext().ensureActive()
            database.withTransaction {
            ChronicleBackup.restoreDeletions(database, chronicle)
            MemoryReviewBackup.restoreSources(database, root)
            val deletions = root.optJSONArray("deletedMemories")
            if (version >= 2 && deletions != null) {
                for (i in 0 until deletions.length()) {
                    val deletion = deletions.getJSONObject(i)
                    val id = deletion.getString("id")
                    val deletedAt = deletion.getLong("deletedAt")
                    require(id.isNotBlank() && deletedAt >= 0) { "删除记录无效" }
                    database.memoryExtractionJobDao().forgetById(id, deletedAt)
                }
            }
            var skipped = 0
            ChronicleBackup.propagateMemoryDeletions(database, root)
            MemoryDerivationBackup.propagateDeletions(database, derivations, chronicle.sources)
            val restoredSources = ChronicleBackup.restoreSources(database, chronicle)
            val restoredDerivations = MemoryDerivationBackup.restore(database, derivations)

            // 顺序: Character（被 Conversation 引用）→ Conversation（被 Message 引用）→ 其余
            val characters = root.optJSONArray("characters").mapObjects { it.toCharacter() }
            restoreCharacters(characters)
            skipped += characters?.count { it == null } ?: 0

            val conversations = root.optJSONArray("conversations").mapObjects { it.toConversation() }?.map { c ->
                c?.takeUnless { database.chronicleSourceDao().chronicleBlocked("conversation", it.id, null, null) }
            }
            conversations?.filterNotNull()?.forEach { conversationDao.mergeImported(it) }
            skipped += conversations?.count { it == null } ?: 0

            val protectedSources = (reviews + database.memoryReviewDao().observe().first())
                .mapNotNull { it.sourceMessageId }.toSet()
            val messages = root.optJSONArray("messages").mapObjects { it.toMessage() }?.map { message ->
                message?.takeUnless {
                    database.chronicleSourceDao().transcriptDeleted(it.id, it.conversationId) ||
                        ((it.id in protectedSources || database.chronicleSourceDao().hasAnchor("message", it.id) ||
                            database.chronicleSourceDao().chronicleBlocked("message", it.id, it.conversationId, null)) &&
                            database.memoryReviewDao().message(it.id) != null)
                }
            }
            messages?.filterNotNull()?.let { if (it.isNotEmpty()) messageDao.insertAll(it) }
            skipped += messages?.count { it == null } ?: 0
            recountConversations(conversations, messages)

            val blockedResults = MemoryReviewBackup.blockedResults(database, reviews)
            val memories = root.optJSONArray("memories").mapObjects { it.toMemory() }?.map { memory ->
                memory?.takeUnless { !MemoryDerivationBackup.allowsMemory(database, it) || memoryDao.wasDeleted(it.id) || it.id in blockedResults || memoryDao.getById(it.id) != null ||
                    it.sourceMessageId?.let { id -> memoryDao.sourceRedacted(id) } == true ||
                    it.sourceConversationId?.let { id -> memoryDao.conversationRedacted(id) } == true }
            }
            memories?.filterNotNull()?.let { if (it.isNotEmpty()) memoryDao.insertAll(it) }
            skipped += memories?.count { it == null } ?: 0
            val restoredReviews = MemoryReviewBackup.restore(database, reviews, blockedResults)
            skipped += reviews.size - restoredReviews
            MemoryReviewBackup.verifyNewConfirmations(database, memories.orEmpty().filterNotNull())
            MemoryDerivationBackup.verify(database, restoredDerivations)

            val automations = root.optJSONArray("automations").mapObjects { it.toAutomation() }
            automations?.filterNotNull()?.forEach { automationDao.insert(it) }
            skipped += automations?.count { it == null } ?: 0

            ImportStats(
                conversations = conversations?.count { it != null } ?: 0,
                messages = messages?.count { it != null } ?: 0,
                characters = characters?.count { it != null } ?: 0,
                memories = memories?.count { it != null } ?: 0,
                automations = automations?.count { it != null } ?: 0,
                skipped = skipped, memoryReviews = restoredReviews, chronicleSources = restoredSources
            )
            } }
            traceManager.record("ui_backup", NodeType.SYSTEM_EVENT, "backup",
                "import_done: conv=${stats.conversations} msgs=${stats.messages} chars=${stats.characters} " +
                        "mem=${stats.memories} auto=${stats.automations} skipped=${stats.skipped}")
            Result.success(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            traceManager.record("ui_backup", NodeType.ERROR, "backup", "import_failed: ${e.message?.take(60)}")
            Result.failure(e)
        }
    }

    /** 逐条容错映射: 单条解析失败返回 null 元素（计 skipped），不阻断整批 */
    private inline fun <T> JSONArray?.mapObjects(parse: (JSONObject) -> T?): List<T?>? =
        this?.let { arr -> (0 until arr.length()).map { i ->
            arr.optJSONObject(i)?.takeIf { it.optString("id").isNotBlank() }?.let(parse)
        } }

    private suspend fun recountConversations(conversations: List<ConversationEntity?>?, messages: List<MessageEntity?>?) {
        val ids = conversations.orEmpty().filterNotNull().map { it.id } +
            messages.orEmpty().filterNotNull().map { it.conversationId }
        ids.distinct().forEach { conversationDao.recountImportedMessages(it) }
    }

    /** Merge keeps the local current role; an empty owner adopts the backup role. Caller owns the restore transaction. */
    private suspend fun restoreCharacters(rows: List<CharacterEntity?>?) {
        val imported = rows.orEmpty().filterNotNull()
        if (imported.isEmpty()) return
        val selected = characterDao.getActive()?.id ?: imported.firstOrNull { it.isActive }?.id
        imported.forEach { characterDao.insert(it.copy(isActive = false)) }
        selected?.let { characterDao.activateExisting(it) }
    }
}

// ═══ 实体 JSON 解码（导入侧；枚举解析失败回退安全默认）═══

private fun JSONObject.toConversation(): ConversationEntity? = try {
    ConversationEntity(
        id = optString("id"), title = optString("title", "导入对话"),
        characterId = optStringOrNull("characterId"),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        updatedAt = optLong("updatedAt", System.currentTimeMillis()),
        messageCount = optInt("messageCount", 0),
        summary = optStringOrNull("summary"), summaryUpto = optStringOrNull("summaryUpto")
    )
} catch (_: Exception) { null }

private fun JSONObject.toMessage(): MessageEntity? = try {
    MessageEntity(
        id = optString("id"), conversationId = optString("conversationId"),
        role = optString("role", "user"),
        content = optStringOrNull("content"),
        toolCalls = optStringOrNull("toolCalls"),
        automationRunId = optStringOrNull("automationRunId"),
        tokensUsed = optLongOrNull("tokensUsed"),
        attachments = optStringOrNull("attachments"),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        source = optStringOrNull("source"), systemSource = optStringOrNull("systemSource"),
        triggerId = optStringOrNull("triggerId")
    )
} catch (_: Exception) { null }

private fun JSONObject.toCharacter(): CharacterEntity? = try {
    CharacterEntity(
        id = optString("id"), name = optString("name", "未命名"),
        description = optStringOrNull("description"),
        systemPrompt = optString("systemPrompt", ""),
        isPreset = optBoolean("isPreset", false),
        isActive = optBoolean("isActive", false),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        updatedAt = optLong("updatedAt", System.currentTimeMillis())
    )
} catch (_: Exception) { null }

private fun JSONObject.toMemory(): MemoryEntity? = try {
    MemoryEntity(
        id = optString("id"), content = optString("content"),
        level = optInt("level", 0),
        importance = optDouble("importance", 0.5).toFloat(),
        sourceConversationId = optStringOrNull("sourceConversationId"),
        keywords = optStringOrNull("keywords"),
        tier = optInt("tier", 1),
        type = try { MemoryType.valueOf(optString("type", "EVENT")) } catch (_: Exception) { MemoryType.EVENT },
        status = try { MemoryStatus.valueOf(optString("status", "ACTIVE")) } catch (_: Exception) { MemoryStatus.ACTIVE },
        confidence = optDouble("confidence", 0.5).toFloat(),
        reinforcementCount = optInt("reinforcementCount", 0),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        accessCount = optInt("accessCount", 0),
        lastAccessedAt = optLong("lastAccessedAt", System.currentTimeMillis()),
        origin = optString("origin", "UNKNOWN"), sourceMessageId = optStringOrNull("sourceMessageId"),
        occurredAt = optLongOrNull("occurredAt"), validFrom = optLongOrNull("validFrom"), validUntil = optLongOrNull("validUntil"),
        supersedesId = optStringOrNull("supersedesId"), revision = optInt("revision", 1), scope = optString("scope", "personal")
    )
} catch (_: Exception) { null }

private fun JSONObject.toAutomation(): AutomationEntity? = try {
    AutomationEntity(
        id = optString("id"), title = optString("title", ""), description = optString("description", ""),
        planJson = optString("planJson", "{}"), status = optString("status", "ACTIVE"),
        scheduleType = optString("scheduleType", "MANUAL"), scheduleValue = optString("scheduleValue", ""),
        execMode = optString("execMode", "PLAN"),
        lastRunAt = optLongOrNull("lastRunAt"),
        nextRunAt = optLong("nextRunAt", Long.MAX_VALUE),
        runCount = optInt("runCount", 0),
        createdAt = optLong("createdAt", System.currentTimeMillis())
    )
} catch (_: Exception) { null }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, null)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key, 0L)
