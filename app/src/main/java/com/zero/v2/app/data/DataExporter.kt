package com.zero.v2.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.zero.v2.core.data.db.ZeroDatabase
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 导出统计 */
data class ExportStats(
    val conversations: Int, val messages: Int, val characters: Int,
    val memories: Int, val automations: Int, val memoryReviews: Int = 0, val chronicleSources: Int = 0
)

/**
 * 数据导出器 — Step 5.6（用户数据主权：可携带）。
 *
 * 范围: Conversation + Message + Character + Memory（不含向量，导入后回填重嵌）+ Automation（活跃/暂停）。
 * 不导出: API Key（SecureKeyStore，安全边界）、module_configs（含引用可重建）、
 *        UserGraph（可由记忆重新提取）、AgentTrace（诊断数据）。
 * 格式: 单文件 JSON（`__format: zero_backup`，版本化，D8 迁移链兼容）。
 */
@Singleton
class DataExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val characterDao: CharacterDao,
    private val memoryDao: MemoryDao,
    private val automationDao: AutomationDao,
    private val traceManager: AgentTraceManager,
    private val database: ZeroDatabase
) {

    suspend fun exportTo(uri: Uri, progress: (BackupProgress) -> Unit = {}): Result<ExportStats> = withContext(Dispatchers.IO) {
        try {
            progress(BackupProgress("正在准备数据快照"))
            currentCoroutineContext().ensureActive()
            val json = database.withTransaction { buildJson() }
            val bytes = json.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 32 * 1024 * 1024) { "备份超过32MiB上限" }
            currentCoroutineContext().ensureActive()
            progress(BackupProgress("正在写入备份，请等待完成", cancellable = false))
            currentCoroutineContext().ensureActive()
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                for (offset in bytes.indices step (16 * 1024)) {
                    out.write(bytes, offset, minOf(16 * 1024, bytes.size - offset))
                }
                out.flush()
            } ?: return@withContext Result.failure(IllegalStateException("无法打开输出流"))
            val stats = parseStats(json)
            traceManager.record("ui_backup", NodeType.SYSTEM_EVENT, "backup",
                "export_done: conv=${stats.conversations} msgs=${stats.messages} chars=${stats.characters} " +
                        "mem=${stats.memories} auto=${stats.automations}")
            Result.success(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            traceManager.record("ui_backup", NodeType.ERROR, "backup", "export_failed: ${e.message?.take(60)}")
            Result.failure(e)
        }
    }

    private suspend fun buildJson(): String {
        val conversations = conversationDao.getAll()
        val messages = conversations.flatMap { messageDao.getByConversation(it.id) }
        val characters = characterDao.getAll()
        val memories = memoryDao.observeAll().first()
        val automations = automationDao.getActive()

        return JSONObject().apply {
            put("__format", "zero_backup")
            put("version", 6)
            put("deletedMemories", JSONArray(database.memoryExtractionJobDao().deletions().map {
                JSONObject().put("id", it.id).put("deletedAt", it.deletedAt)
            }))
            MemoryReviewBackup.export(database, this)
            ChronicleBackup.export(database, this)
            MemoryDerivationBackup.export(database, this)
            put("exportedAt", System.currentTimeMillis())
            put("conversations", JSONArray(conversations.map { it.toJson() }))
            put("messages", JSONArray(messages.map { it.toJson() }))
            put("characters", JSONArray(characters.map { it.toJson() }))
            put("memories", JSONArray(memories.map { it.toJson() }))
            put("automations", JSONArray(automations.map { it.toJson() }))
        }.toString()
    }

    private fun parseStats(json: String): ExportStats {
        val obj = JSONObject(json)
        return ExportStats(
            conversations = obj.optJSONArray("conversations")?.length() ?: 0,
            messages = obj.optJSONArray("messages")?.length() ?: 0,
            characters = obj.optJSONArray("characters")?.length() ?: 0,
            memories = obj.optJSONArray("memories")?.length() ?: 0,
            automations = obj.optJSONArray("automations")?.length() ?: 0,
            memoryReviews = obj.optJSONArray("memoryReviews")?.length() ?: 0,
            chronicleSources = obj.optJSONArray("chronicleSources")?.length() ?: 0
        )
    }
}

// ═══ 实体 JSON 编解码（导出侧）═══

internal fun ConversationEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title)
    put("characterId", characterId ?: JSONObject.NULL)
    put("createdAt", createdAt); put("updatedAt", updatedAt); put("messageCount", messageCount)
    put("summary", summary ?: JSONObject.NULL)
    put("summaryUpto", summaryUpto ?: JSONObject.NULL)
}

internal fun MessageEntity.toJson(): JSONObject = JSONObject().apply {
    put("source", source ?: JSONObject.NULL)
    put("systemSource", systemSource ?: JSONObject.NULL)
    put("triggerId", triggerId ?: JSONObject.NULL)
    put("id", id); put("conversationId", conversationId); put("role", role)
    put("content", content ?: JSONObject.NULL)
    put("toolCalls", toolCalls ?: JSONObject.NULL)
    put("automationRunId", automationRunId ?: JSONObject.NULL)
    put("tokensUsed", tokensUsed ?: JSONObject.NULL)
    put("attachments", attachments ?: JSONObject.NULL)
    put("createdAt", createdAt)
}

internal fun CharacterEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("name", name)
    put("description", description ?: JSONObject.NULL)
    put("systemPrompt", systemPrompt)
    put("isPreset", isPreset); put("isActive", isActive)
    put("createdAt", createdAt); put("updatedAt", updatedAt)
}

internal fun MemoryEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("content", content); put("level", level)
    put("importance", importance.toDouble())
    put("sourceConversationId", sourceConversationId ?: JSONObject.NULL)
    put("keywords", keywords ?: JSONObject.NULL)
    put("tier", tier); put("type", type.name); put("status", status.name)
    put("confidence", confidence.toDouble()); put("reinforcementCount", reinforcementCount)
    put("createdAt", createdAt); put("accessCount", accessCount); put("lastAccessedAt", lastAccessedAt)
    put("origin", origin); put("sourceMessageId", sourceMessageId ?: JSONObject.NULL)
    put("occurredAt", occurredAt ?: JSONObject.NULL); put("validFrom", validFrom ?: JSONObject.NULL)
    put("validUntil", validUntil ?: JSONObject.NULL); put("supersedesId", supersedesId ?: JSONObject.NULL)
    put("revision", revision); put("scope", scope)
    // embedding（二进制向量）不导出 — 导入后由回填 Job 重嵌
}

internal fun AutomationEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("description", description)
    put("planJson", planJson); put("status", status)
    put("scheduleType", scheduleType); put("scheduleValue", scheduleValue)
    put("execMode", execMode)
    put("lastRunAt", lastRunAt ?: JSONObject.NULL)
    put("nextRunAt", nextRunAt); put("runCount", runCount); put("createdAt", createdAt)
}
