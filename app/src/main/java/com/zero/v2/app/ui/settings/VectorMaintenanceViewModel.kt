package com.zero.v2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.embedding.VectorIndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal interface VectorMaintenanceActions {
    suspend fun status(domain: VectorDomain): VectorIndexWorker.Status
    suspend fun rollback(domain: VectorDomain, expected: String): Boolean
}

data class VectorMaintenanceState(
    val rows: Map<VectorDomain, VectorIndexWorker.Status> = emptyMap(),
    val busy: Boolean = false, val message: String? = null,
)

@HiltViewModel
class VectorMaintenanceViewModel internal constructor(private val actions: VectorMaintenanceActions) : ViewModel() {
    @Inject constructor(worker: VectorIndexWorker) : this(object : VectorMaintenanceActions {
        override suspend fun status(domain: VectorDomain) = worker.status(domain)
        override suspend fun rollback(domain: VectorDomain, expected: String) =
            worker.maintenance.rollbackValidated(domain, expected)
    })
    private val mutable = MutableStateFlow(VectorMaintenanceState())
    val state = mutable.asStateFlow()
    init { refresh() }

    fun refresh() = perform { reload(); "状态已刷新；未构建索引或切换模型" }
    fun rollback(domain: VectorDomain, expected: String) = perform {
        check(mutable.value.rows[domain]?.active == expected) { "vector_base_changed" }
        check(actions.rollback(domain, expected)) { "vector_rollback_rejected" }
        reload()
        "已回滚${domainLabel(domain)}索引；原记忆与模型配置保持不变"
    }
    private suspend fun reload() {
        val rows = VectorDomain.entries.associateWith { actions.status(it) }
        mutable.update { it.copy(rows = rows) }
    }
    private fun perform(action: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { val message = action(); mutable.update { it.copy(message = message) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.update { it.copy(rows = emptyMap(), message = "操作未完成：索引、来源或验收条件可能已变化，请刷新后重试") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}

internal fun domainLabel(domain: VectorDomain) = if (domain == VectorDomain.MEMORY) "记忆" else "知识库"
