package com.zero.v2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryReviewEntity
import com.zero.v2.core.data.repository.MemoryReviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryReviewState(
    val memories: List<MemoryEntity> = emptyList(),
    val proposals: List<MemoryReviewEntity> = emptyList(),
    val historicalSources: List<com.zero.v2.core.data.entity.MessageEntity> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val preferenceCandidates: List<PreferenceSuggestions.Candidate> = emptyList(),
)

@HiltViewModel
class MemoryReviewViewModel @Inject constructor(
    private val memories: MemoryDao, private val reviews: MemoryReviewRepository,
    private val migration: com.zero.v2.core.data.store.HistoricalSourceMigration,
) : ViewModel() {
    private val mutable = MutableStateFlow(MemoryReviewState())
    val state = mutable.asStateFlow()
    private var subscription: Job? = null
    init { retry() }
    fun retry() {
        if (subscription?.isActive == true) return
        mutable.update { it.copy(loading = true, error = null) }
        subscription = viewModelScope.launch {
            try {
                combine(memories.observeAll(), reviews.observe()) { m, p -> m to p }.collect { (m, p) ->
                    mutable.update { it.copy(memories = m, proposals = p, loading = false, preferenceCandidates = emptyList()) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, error = "读取失败，请重试") } }
        }
    }
    fun propose(m: MemoryEntity, content: String, type: MemoryType, scope: String, reason: String) = perform {
        if (reviews.propose(m, content, type, scope, reason) != null) "提案已保存；重复或已处理提案保持原状态"
        else "原记忆已变化，请重新选择"
    }
    fun searchHistory(query: String) = perform {
        val rows = migration.search(query)
        mutable.update { it.copy(historicalSources = rows) }
        "找到${rows.size}条合格历史原文（最多50条）；搜索不修改记忆"
    }
    fun importHistory(message: com.zero.v2.core.data.entity.MessageEntity) = perform {
        if (migration.retain(message)) "原文已导入来源库，保留原日期；未改写记忆"
        else "此原文已在来源库中，无需重复导入"
    }
    fun proposeEdited(m: MemoryEntity, content: String, type: MemoryType, scope: String, reason: String,
        times: com.zero.v2.core.data.entity.MemoryReviewTimes) = perform {
        if (reviews.propose(m, content, type, scope, reason, times) != null) "提案已保存；请核对后确认"
        else "原记忆已变化，请重新选择"
    }
    fun suggestPreferences() = perform {
        val rows = mutable.value.memories
        val messages = rows.filter { it.type == MemoryType.PREFERENCE && it.origin == "USER_QUOTE" }
            .mapNotNull { it.sourceMessageId }.distinct().take(200)
            .mapNotNull { id -> reviews.source(id)?.let { id to it } }.toMap()
        val candidates = PreferenceSuggestions.generate(rows, messages, System.currentTimeMillis(),
            mutable.value.proposals.map { it.baseVersion }.toSet(),
            memories.observeConflicts().first().flatMap { listOf(it.leftId, it.rightId) }.toSet())
        mutable.update { it.copy(preferenceCandidates = candidates) }
        "本地核对完成：${candidates.size}组重复明示偏好；最多核对200个来源，建议未保存、未生效"
    }
    fun decide(id: String, accept: Boolean) = perform {
        if (reviews.decide(id, accept)) if (accept) "已确认新版本，旧原文已归档" else "已拒绝，历史记忆未修改"
        else "提案已处理或来源已变化，未应用本次操作"
    }
    private fun perform(action: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { val message = action(); mutable.update { it.copy(notice = message) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(notice = "操作未完成，请保留提案后重试") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    suspend fun source(id: String): String = try {
        reviews.source(id)?.let {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(it.createdAt))
            "原消息（$date，仅供对照，不代表来源已认证）：\n${it.content.orEmpty()}"
        }
            ?: "原消息已不可用；确认会形成新的用户证据"
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { "来源读取失败，请稍后重试；提交时仍会重新核对" }
}
