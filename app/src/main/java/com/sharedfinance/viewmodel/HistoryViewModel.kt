package com.sharedfinance.viewmodel

import com.google.gson.GsonBuilder
import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.ChangeHistoryEntry
import com.sharedfinance.model.ConflictResolutionDecision
import com.sharedfinance.model.ConflictResolutionLogEntry
import com.sharedfinance.model.HistoryOperationType
import com.sharedfinance.model.SyncLogEntry
import com.sharedfinance.model.SyncResultType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Calendar
import java.util.Date

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

enum class HistorySyncResultFilter {
    ALL,
    SUCCESS,
    CONFLICT,
    FAILED
}

enum class ConflictDecisionFilter {
    ALL,
    ACCEPT_REMOTE,
    KEEP_LOCAL
}

data class HistoryGroup(
    val date: Date,
    val items: List<ChangeHistoryEntry>
)

data class HistoryUiState(
    val entries: List<ChangeHistoryEntry> = emptyList(),
    val syncLogs: List<SyncLogEntry> = emptyList(),
    val conflictResolutionLogs: List<ConflictResolutionLogEntry> = emptyList(),
    val groupedEntries: List<HistoryGroup> = emptyList(),
    val filteredSyncLogs: List<SyncLogEntry> = emptyList(),
    val filteredConflictResolutionLogs: List<ConflictResolutionLogEntry> = emptyList(),
    val searchText: String = "",
    val selectedOperation: HistoryOperationFilter = HistoryOperationFilter.ALL,
    val selectedDateFilter: HistoryDateFilter = HistoryDateFilter.ALL,
    val selectedSyncResultFilter: HistorySyncResultFilter = HistorySyncResultFilter.ALL,
    val selectedDecisionFilter: ConflictDecisionFilter = ConflictDecisionFilter.ALL
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
    private val gson = GsonBuilder().setPrettyPrinting().create()

    init {
        combine(
            repository.observeHistory(),
            repository.observeSyncLogs(),
            repository.observeConflictResolutionLogs()
        ) { entries, syncLogs, conflictLogs ->
            Triple(entries, syncLogs, conflictLogs)
        }
            .onEach { (entries, syncLogs, conflictLogs) ->
                _state.value = _state.value.copy(
                    entries = entries
                        .sortedByDescending { it.date.time }
                        .take(MAX_HISTORY_RECORDS),
                    syncLogs = syncLogs
                        .sortedByDescending { it.date.time }
                        .take(MAX_HISTORY_RECORDS),
                    conflictResolutionLogs = conflictLogs
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

    fun updateSyncResultFilter(value: HistorySyncResultFilter) {
        _state.value = _state.value.copy(selectedSyncResultFilter = value)
        recompute()
    }

    fun updateDecisionFilter(value: ConflictDecisionFilter) {
        _state.value = _state.value.copy(selectedDecisionFilter = value)
        recompute()
    }

    fun exportFilteredConflictLogsData(): ByteArray? {
        return runCatching {
            gson.toJson(_state.value.filteredConflictResolutionLogs).toByteArray()
        }.getOrNull()
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

        val filteredSyncLogs = _state.value.syncLogs.filter { item ->
            val matchesSearch = query.isEmpty() ||
                item.deviceName.contains(query, ignoreCase = true) ||
                item.result.name.contains(query, ignoreCase = true)

            val matchesResult = when (_state.value.selectedSyncResultFilter) {
                HistorySyncResultFilter.ALL -> true
                HistorySyncResultFilter.SUCCESS -> item.result == SyncResultType.SUCCESS
                HistorySyncResultFilter.CONFLICT -> item.result == SyncResultType.CONFLICT
                HistorySyncResultFilter.FAILED -> item.result == SyncResultType.FAILED
            }

            matchesSearch && matchesResult
        }

        val filteredConflictLogs = _state.value.conflictResolutionLogs.filter { item ->
            val matchesSearch = query.isEmpty() ||
                item.entityName.contains(query, ignoreCase = true) ||
                item.localValue.contains(query, ignoreCase = true) ||
                item.remoteValue.contains(query, ignoreCase = true)

            val matchesDecision = when (_state.value.selectedDecisionFilter) {
                ConflictDecisionFilter.ALL -> true
                ConflictDecisionFilter.ACCEPT_REMOTE -> item.decision == ConflictResolutionDecision.ACCEPT_REMOTE
                ConflictDecisionFilter.KEEP_LOCAL -> item.decision == ConflictResolutionDecision.KEEP_LOCAL
            }

            val matchesDate = when (_state.value.selectedDateFilter) {
                HistoryDateFilter.ALL -> true
                HistoryDateFilter.TODAY -> {
                    calendar.time = now
                    val startToday = calendar.apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.time
                    item.date >= startToday
                }
                HistoryDateFilter.SEVEN_DAYS -> {
                    calendar.time = now
                    val threshold = calendar.apply { add(Calendar.DAY_OF_YEAR, -7) }.time
                    item.date >= threshold
                }
                HistoryDateFilter.THIRTY_DAYS -> {
                    calendar.time = now
                    val threshold = calendar.apply { add(Calendar.DAY_OF_YEAR, -30) }.time
                    item.date >= threshold
                }
            }

            matchesSearch && matchesDecision && matchesDate
        }

        _state.value = _state.value.copy(
            groupedEntries = grouped,
            filteredSyncLogs = filteredSyncLogs,
            filteredConflictResolutionLogs = filteredConflictLogs
        )
    }
}
