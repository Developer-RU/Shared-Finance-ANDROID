package com.sharedfinance.viewmodel

import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.ChangeHistoryEntry
import com.sharedfinance.model.HistoryOperationType
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

enum class HistoryOperationFilter {
    ALL,
    CREATE,
    UPDATE,
    DELETE,
    SYNC
}

enum class HistoryDateFilter {
    ALL,
    TODAY,
    SEVEN_DAYS,
    THIRTY_DAYS
}

data class HistoryGroup(
    val date: Date,
    val items: List<ChangeHistoryEntry>
)

data class HistoryUiState(
    val entries: List<ChangeHistoryEntry> = emptyList(),
    val groupedEntries: List<HistoryGroup> = emptyList(),
    val searchText: String = "",
    val selectedOperation: HistoryOperationFilter = HistoryOperationFilter.ALL,
    val selectedDateFilter: HistoryDateFilter = HistoryDateFilter.ALL
)

class HistoryViewModel(
    repository: SharedFinanceRepository
) {
    private companion object {
        const val MAX_HISTORY_RECORDS = 500
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        repository.observeHistory()
            .onEach { entries ->
                _state.value = _state.value.copy(
                    entries = entries
                        .sortedByDescending { it.date.time }
                        .take(MAX_HISTORY_RECORDS)
                )
                recompute()
            }
            .launchIn(scope)
    }

    fun updateSearchText(value: String) {
        _state.value = _state.value.copy(searchText = value)
        recompute()
    }

    fun updateOperationFilter(value: HistoryOperationFilter) {
        _state.value = _state.value.copy(selectedOperation = value)
        recompute()
    }

    fun updateDateFilter(value: HistoryDateFilter) {
        _state.value = _state.value.copy(selectedDateFilter = value)
        recompute()
    }

    private fun recompute() {
        val query = _state.value.searchText.trim()
        val now = Date()
        val calendar = Calendar.getInstance()
        val filtered = _state.value.entries.filter { entry ->
            val searchMatch = query.isEmpty() ||
                entry.description.contains(query, ignoreCase = true) ||
                entry.actorName.contains(query, ignoreCase = true)

            val operationMatch = when (_state.value.selectedOperation) {
                HistoryOperationFilter.ALL -> true
                HistoryOperationFilter.CREATE -> entry.operationType == HistoryOperationType.CREATE
                HistoryOperationFilter.UPDATE -> entry.operationType == HistoryOperationType.UPDATE
                HistoryOperationFilter.DELETE -> entry.operationType == HistoryOperationType.DELETE
                HistoryOperationFilter.SYNC -> entry.operationType == HistoryOperationType.SYNC
            }

            val dateMatch = when (_state.value.selectedDateFilter) {
                HistoryDateFilter.ALL -> true
                HistoryDateFilter.TODAY -> {
                    calendar.time = now
                    val startToday = calendar.apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.time
                    entry.date >= startToday
                }
                HistoryDateFilter.SEVEN_DAYS -> {
                    calendar.time = now
                    val threshold = calendar.apply { add(Calendar.DAY_OF_YEAR, -7) }.time
                    entry.date >= threshold
                }
                HistoryDateFilter.THIRTY_DAYS -> {
                    calendar.time = now
                    val threshold = calendar.apply { add(Calendar.DAY_OF_YEAR, -30) }.time
                    entry.date >= threshold
                }
            }

            searchMatch && operationMatch && dateMatch
        }

        val grouped = filtered
            .groupBy { entry ->
                calendar.time = entry.date
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.time
            }
            .toList()
            .sortedByDescending { it.first.time }
            .map { (date, entries) ->
                HistoryGroup(
                    date = date,
                    items = entries.sortedByDescending { it.date.time }
                )
            }

        _state.value = _state.value.copy(groupedEntries = grouped)
    }
}
