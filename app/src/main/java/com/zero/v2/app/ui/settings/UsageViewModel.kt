package com.zero.v2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.data.dao.UsageRequestDao
import com.zero.v2.core.data.dao.UsageBucket
import com.zero.v2.core.data.dao.UsageRoute
import com.zero.v2.core.data.entity.UsageRequestEntity
import com.zero.v2.services.agent.BudgetGuard
import com.zero.v2.services.llm.UsageLedger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class UsageFilter(val window: UsageWindow = UsageWindow(LocalDate.now(), LocalDate.now()),
    val connection: String = "", val model: String = "", val offset: Int = 0, val revision: Long = 0,
    val rollingDays: Int? = 1) {
    fun calendar(today: LocalDate, zone: java.time.ZoneId): UsageFilter {
        val current = rollingDays?.let { UsageWindow(today.minusDays(it - 1L), today, zone) }
            ?: window.copy(zone = zone)
        return if (current == window) this else copy(window = current, offset = 0)
    }
}

data class UsagePageState(val buckets: List<UsageBucket> = emptyList(),
    val details: List<UsageRequestEntity> = emptyList(), val routes: List<UsageRoute> = emptyList(),
    val incomplete: Boolean = false, val loading: Boolean = true, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsageViewModel @Inject constructor(dao: UsageRequestDao, ledger: UsageLedger,
    private val budgetGuard: BudgetGuard) : ViewModel() {
    private val selected = MutableStateFlow(UsageFilter())
    val filter = selected.asStateFlow()
    val state = selected.flatMapLatest { f ->
        combine(dao.buckets(f.window.start, f.window.end, f.connection, f.model, f.window.first != f.window.last),
            dao.details(f.window.start, f.window.end, f.connection, f.model, f.offset),
            dao.routes(), ledger.incomplete) { buckets, details, routes, incomplete ->
                UsagePageState(buckets, details.reversed(), routes, incomplete, loading = false)
            }.onStart { emit(UsagePageState()) }
            .catch { emit(UsagePageState(loading = false, error = "读取用量失败，请重试")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsagePageState())

    fun select(value: UsageFilter) { selected.value = value }
    fun refreshCalendar() {
        val zone = java.time.ZoneId.systemDefault()
        selected.update { it.calendar(LocalDate.now(zone), zone) }
    }
    fun refresh() {
        refreshCalendar()
        selected.update { it.copy(revision = it.revision + 1) }
    }
    fun pricingSummary() = budgetGuard.pricingSummary()
    fun legacyBudget() = budgetGuard.snapshot()
}
