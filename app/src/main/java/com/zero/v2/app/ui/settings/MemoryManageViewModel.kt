package com.zero.v2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.repository.MemoryDeletionRepository
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** 记忆管理 UI 状态 */
data class MemoryUiState(
    val all: List<MemoryEntity> = emptyList(),
    val query: String = "",
    val typeFilter: MemoryType? = null,
    val deleteError: String? = null,
    val isLoading: Boolean = false,
    val readError: String? = null,
    val conflicts: List<com.zero.v2.core.data.entity.MemoryConflictEntity> = emptyList(),
)

/**
 * 记忆管理 ViewModel — Step 4.12。
 * 实时流读 observeAll；搜索/筛选在展示层计算（数据量级小）。
 */
@HiltViewModel
class MemoryManageViewModel @Inject constructor(
    private val memoryDao: MemoryDao,
    private val deletions: MemoryDeletionRepository,
    private val traceManager: AgentTraceManager
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryUiState(isLoading = true))
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()
    private var readJob: Job? = null
    private val conflictMutex = Mutex()

    init { retryRead() }

    /** One live Room subscription; explicit retry preserves the last data and filters. */
    fun retryRead() {
        if (readJob?.isActive == true) return
        _state.update { it.copy(isLoading = true, readError = null) }
        readJob = viewModelScope.launch {
            try {
                combine(memoryDao.observeAll(), memoryDao.observeConflicts()) { list, conflicts -> list to conflicts }.collect { (list, conflicts) ->
                    _state.update { it.copy(all = list, conflicts = conflicts, isLoading = false, readError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false,
                    readError = "无法读取记忆，请重试；已加载的内容仍保留") }
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun setTypeFilter(type: MemoryType?) = _state.update { it.copy(typeFilter = type) }
    fun clearDeleteError() = _state.update { it.copy(deleteError = null) }

    fun resolveConflict(id: String, choice: String) {
        viewModelScope.launch {
            try {
                val applied = conflictMutex.withLock { memoryDao.resolveConflict(id, choice, System.currentTimeMillis()) }
                if (!applied) _state.update { it.copy(deleteError = "记忆已变化，请按最新内容重新确认") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(deleteError = "确认未完成，请重试") } }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                deletions.deleteById(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(deleteError = "删除未完成，数据已保留，请重试") }
                return@launch
            }
            traceManager.record("ui_memory", NodeType.SYSTEM_EVENT, "memory_ui", "ui_delete_completed")
        }
    }
}
