package com.zero.v2.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.app.data.BackupProgress
import com.zero.v2.app.data.DataExporter
import com.zero.v2.app.data.DataImporter
import com.zero.v2.app.data.ExportStats
import com.zero.v2.app.data.ImportStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DataExportState(
    val busy: Boolean = false,
    val progress: BackupProgress? = null,
    val lastExport: ExportStats? = null,
    val lastImport: ImportStats? = null,
    val error: String? = null,
    val notice: String? = null,
    val operationId: Long = 0,
)

internal interface BackupActions {
    suspend fun export(uri: Uri, progress: (BackupProgress) -> Unit): Result<ExportStats>
    suspend fun restore(uri: Uri, progress: (BackupProgress) -> Unit): Result<ImportStats>
}

@HiltViewModel
class DataExportViewModel internal constructor(private val actions: BackupActions) : ViewModel() {
    @Inject constructor(exporter: DataExporter, importer: DataImporter) : this(object : BackupActions {
        override suspend fun export(uri: Uri, progress: (BackupProgress) -> Unit) = exporter.exportTo(uri, progress)
        override suspend fun restore(uri: Uri, progress: (BackupProgress) -> Unit) = importer.importFrom(uri, progress)
    })
    private val mutableState = MutableStateFlow(DataExportState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L

    fun exportTo(uri: Uri) = start { progress ->
        val result = actions.export(uri, progress)
        currentCoroutineContext().ensureActive()
        result.fold(
            { stats -> mutableState.update { it.copy(lastExport = stats) } },
            { failure -> showFailure(failure, "导出失败，目标文件可能不完整，请重新导出") },
        )
    }
    fun importFrom(uri: Uri) = start { progress ->
        val result = actions.restore(uri, progress)
        currentCoroutineContext().ensureActive()
        result.fold(
            { stats -> mutableState.update { it.copy(lastImport = stats) } },
            { failure -> showFailure(failure, "恢复失败，未提交的数据库修改已回滚，请核对备份后重试") },
        )
    }
    private fun start(work: suspend ((BackupProgress) -> Unit) -> Unit) {
        if (state.value.busy) return
        val owner = ++generation
        mutableState.value = DataExportState(busy = true, progress = BackupProgress("正在准备"), operationId = owner)
        operation = viewModelScope.launch {
            try {
                work { progress ->
                    mutableState.update {
                        if (it.operationId == owner && it.busy) it.copy(progress = progress) else it
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(notice = "操作已取消；已完成的文件写入或提交不会撤回") }
                throw cancelled
            } catch (failure: Exception) {
                showFailure(failure, "操作失败，请重试")
            } finally {
                mutableState.update { it.copy(busy = false, progress = null) }
            }
        }
    }
    fun cancel() {
        if (state.value.busy && state.value.progress?.cancellable != false) operation?.cancel()
    }
    private fun showFailure(failure: Throwable, fallback: String) {
        if (failure is CancellationException) throw failure
        mutableState.update { it.copy(error = "$fallback：${failure.message?.take(160).orEmpty()}") }
    }
}
