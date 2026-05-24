package com.sharedfinance.viewmodel

import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

enum class BalanceSortOption {
    NAME_ASC,
    BALANCE_DESC,
    BALANCE_ASC
}

enum class BalanceFilterOption {
    ALL,
    POSITIVE,
    NEGATIVE,
    ZERO
}

data class BalanceUiState(
    val participants: List<Participant> = emptyList(),
    val filteredParticipants: List<Participant> = emptyList(),
    val searchText: String = "",
    val selectedSort: BalanceSortOption = BalanceSortOption.BALANCE_DESC,
    val selectedFilter: BalanceFilterOption = BalanceFilterOption.ALL,
    val totalContribution: Double = 0.0,
    val totalExpense: Double = 0.0,
    val netBalance: Double = 0.0
)

class BalanceViewModel(
    private val repository: SharedFinanceRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BalanceUiState())
    val state: StateFlow<BalanceUiState> = _state.asStateFlow()

    init {
        combine(
            repository.observeParticipants(),
            repository.observeExpenses()
        ) { participants, expenses ->
            val expensesByParticipant = expenses.groupBy { it.participantId }
            val enrichedParticipants = participants.map { participant ->
                val participantExpense = expensesByParticipant[participant.id]?.sumOf { it.amount } ?: 0.0
                participant.copy(
                    expenseAmount = participantExpense,
                    balanceAmount = participant.contributionAmount - participantExpense
                )
            }
            val totalContribution = participants.sumOf { it.contributionAmount }
            val totalExpense = expenses.sumOf { it.amount }
            enrichedParticipants to Triple(totalContribution, totalExpense, totalContribution - totalExpense)
        }
            .onEach { (participants, totals) ->
                _state.value = _state.value.copy(
                    participants = participants,
                    totalContribution = totals.first,
                    totalExpense = totals.second,
                    netBalance = totals.third
                )
                recomputeFilter()
            }
            .launchIn(scope)
    }

    fun updateSearchText(value: String) {
        _state.value = _state.value.copy(searchText = value)
        recomputeFilter()
    }

    fun updateSort(sort: BalanceSortOption) {
        _state.value = _state.value.copy(selectedSort = sort)
        recomputeFilter()
    }

    fun updateFilter(filter: BalanceFilterOption) {
        _state.value = _state.value.copy(selectedFilter = filter)
        recomputeFilter()
    }

    fun participantHasExpenses(participantId: UUID): Boolean {
        return _state.value.participants.firstOrNull { it.id == participantId }?.expenseAmount?.let { it > 0.0 } == true
    }

    fun deleteParticipant(participantId: UUID) {
        scope.launch {
            repository.deleteParticipant(participantId, null)
        }
    }

    private fun recomputeFilter() {
        val query = _state.value.searchText.trim()
        val filtered = _state.value.participants
            .filter { participant ->
                val matchesSearch = query.isEmpty() || participant.name.contains(query, ignoreCase = true)
                val matchesFilter = when (_state.value.selectedFilter) {
                    BalanceFilterOption.ALL -> true
                    BalanceFilterOption.POSITIVE -> participant.balanceAmount > 0.0
                    BalanceFilterOption.NEGATIVE -> participant.balanceAmount < 0.0
                    BalanceFilterOption.ZERO -> participant.balanceAmount == 0.0
                }
                matchesSearch && matchesFilter
            }
            .sortedWith(
                when (_state.value.selectedSort) {
                    BalanceSortOption.NAME_ASC -> compareBy { it.name.lowercase() }
                    BalanceSortOption.BALANCE_DESC -> compareByDescending<Participant> { it.balanceAmount }
                        .thenBy { it.name.lowercase() }
                    BalanceSortOption.BALANCE_ASC -> compareBy<Participant> { it.balanceAmount }
                        .thenBy { it.name.lowercase() }
                }
            )

        _state.value = _state.value.copy(filteredParticipants = filtered)
    }
}
