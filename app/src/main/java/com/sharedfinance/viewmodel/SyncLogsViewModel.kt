package com.sharedfinance.viewmodel

import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.SyncLogEntry
import com.sharedfinance.model.SyncResultType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

enum class SyncLogResultFilter {
    ALL,
    SUCCESS,
    CONFLICT,
    FAILED
}

data class SyncLogsUiState(
    val syncLogs: List<SyncLogEntry> = emptyList(),
    val filteredSyncLogs: List<SyncLogEntry> = emptyList(),
    val searchText: String = "",
    val selectedResultFilter: SyncLogResultFilter = SyncLogResultFilter.ALL
)

class SyncLogsViewModel(repository: SharedFinanceRepository) {
    private companion object {
        const val MAX_SYNC_LOG_RECORDS = 500
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SyncLogsUiState())
    val state: StateFlow<SyncLogsUiState> = _state.asStateFlow()

    init {
        repository.observeSyncLogs()
            .onEach { logs ->
                _state.value = _state.value.copy(
                    syncLogs = logs.sortedByDescending { it.date.time }.take(MAX_SYNC_LOG_RECORDS)
                )
                recompute()
            }
            .launchIn(scope)
    }

    fun updateSearchText(value: String) {
        _state.value = _state.value.copy(searchText = value)
        recompute()
    }

    fun updateResultFilter(value: SyncLogResultFilter) {
        _state.value = _state.value.copy(selectedResultFilter = value)
        recompute()
    }

    private fun recompute() {
        val query = _state.value.searchText.trim()
        val filtered = _state.value.syncLogs.filter { item ->
            val matchesSearch = query.isEmpty() ||
                item.deviceName.contains(query, ignoreCase = true) ||
                item.result.name.contains(query, ignoreCase = true)

            val matchesResult = when (_state.value.selectedResultFilter) {
                SyncLogResultFilter.ALL -> true
                SyncLogResultFilter.SUCCESS -> item.result == SyncResultType.SUCCESS
                SyncLogResultFilter.CONFLICT -> item.result == SyncResultType.CONFLICT
                SyncLogResultFilter.FAILED -> item.result == SyncResultType.FAILED
            }

            matchesSearch && matchesResult
        }

        _state.value = _state.value.copy(filteredSyncLogs = filtered)
    }
}
