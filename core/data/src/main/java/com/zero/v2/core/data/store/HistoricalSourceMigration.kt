package com.zero.v2.core.data.store

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ChronicleSourceEntity
import com.zero.v2.core.data.entity.MemorySearchQuery
import com.zero.v2.core.data.entity.MessageEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Explicit local user action only. Search never writes and import never rewrites a memory. */
@Singleton
class HistoricalSourceMigration @Inject constructor(private val db: ZeroDatabase) {
    private val mutex = Mutex()
    suspend fun search(query: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        val words = MemorySearchQuery.terms(query)
        require(words.isNotEmpty()) { "请输入历史原文关键词" }
        val clause = words.joinToString(" AND ") { "instr(lower(content),?)>0" }
        val sql = "SELECT * FROM messages WHERE role='user' AND source IN ('CHAT_WINDOW','VOICE','REMOTE') " +
            "AND system_source IS NULL AND trigger_id IS NULL AND automation_run_id IS NULL AND ($clause) " +
            "AND NOT EXISTS(SELECT 1 FROM chronicle_deletions d WHERE " +
            "(d.kind IN ('message','transcript') AND d.anchorId=messages.id) OR " +
            "(d.kind='conversation' AND d.anchorId=messages.conversation_id)) ORDER BY created_at DESC,id LIMIT 50"
        db.chronicleSourceDao().historicalMessages(SimpleSQLiteQuery(sql, words.toTypedArray()))
    }
    suspend fun retain(expected: MessageEntity): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { db.withTransaction {
            val dao = db.chronicleSourceDao()
            val current = dao.historicalMessage(expected.id)
            require(current == expected) { "原消息已变化，请重新搜索" }
            val source = requireNotNull(ChronicleSourceEntity.message(expected, System.currentTimeMillis()))
            require(source.hasDirectUserProvenance()) { "不能证明直接用户来源，不自动补认证" }
            require(!dao.chronicleBlocked(source.kind, source.anchorId, source.conversationId, null)) { "来源已删除" }
            if (dao.hasAnchor("message", expected.id)) {
                require(dao.chronicle(source.id) != null) { "已有不同版本来源，不能覆盖" }
                false
            } else dao.retainSource(source)
        } }
    }
}
