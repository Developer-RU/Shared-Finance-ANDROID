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

enum class ParticipantSortOption {
    NAME_ASC,
    CONTRIBUTION_DESC,
    CONTRIBUTION_ASC,
    BALANCE_DESC,
    BALANCE_ASC
}

enum class ParticipantBalanceFilter {
    ALL,
    POSITIVE,
    NEGATIVE,
    ZERO
}

data class ParticipantsUiState(
    val participants: List<Participant> = emptyList(),
    val filteredParticipants: List<Participant> = emptyList(),
    val balancesByParticipantId: Map<UUID, Double> = emptyMap(),
    val searchText: String = "",
    val selectedSort: ParticipantSortOption = ParticipantSortOption.NAME_ASC,
    val selectedBalanceFilter: ParticipantBalanceFilter = ParticipantBalanceFilter.ALL,
    val draftName: String = "",
    val draftContribution: String = "",
    val isLoading: Boolean = true
)

class ParticipantsViewModel(
    private val repository: SharedFinanceRepository,
    private val projectId: UUID
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ParticipantsUiState())
    val state: StateFlow<ParticipantsUiState> = _state.asStateFlow()

    init {
        combine(
            repository.observeProjects(),
            repository.observeParticipants(),
            repository.observeExpenses()
        ) { projects, participants, expenses ->
            val project = projects.firstOrNull { it.id == projectId }
            val projectParticipants = participants.filter { participant ->
                project?.participantIds?.contains(participant.id) == true
            }
            val projectExpenses = expenses.filter { it.projectId == projectId }
            val expensesByParticipant = projectExpenses.groupBy { it.participantId }
            val balances = projectParticipants.associate { participant ->
                val expenseAmount = expensesByParticipant[participant.id]?.sumOf { it.amount } ?: 0.0
                participant.id to (participant.contributionAmount - expenseAmount)
            }
            projectParticipants to balances
        }
            .onEach { (items, balances) ->
                _state.value = _state.value.copy(
                    participants = items,
                    balancesByParticipantId = balances,
                    isLoading = false
                )
                recomputeFilter()
            }
            .launchIn(scope)
    }

    fun updateSearchText(value: String) {
        _state.value = _state.value.copy(searchText = value)
        recomputeFilter()
    }

    fun updateSort(value: ParticipantSortOption) {
        _state.value = _state.value.copy(selectedSort = value)
        recomputeFilter()
    }

    fun updateBalanceFilter(value: ParticipantBalanceFilter) {
        _state.value = _state.value.copy(selectedBalanceFilter = value)
        recomputeFilter()
    }

    fun updateDraftName(value: String) {
        _state.value = _state.value.copy(draftName = value)
    }

    fun updateDraftContribution(value: String) {
        _state.value = _state.value.copy(draftContribution = value)
    }

    fun addParticipant() {
        val name = _state.value.draftName.trim()
        if (name.isEmpty()) return

        val contribution = _state.value.draftContribution.replace(",", ".").toDoubleOrNull() ?: 0.0
        scope.launch {
            repository.createParticipant(name = name, projectId = projectId, contributionAmount = contribution)
            _state.value = _state.value.copy(draftName = "", draftContribution = "")
        }
    }

    fun deleteParticipant(participantId: UUID) {
        scope.launch {
            repository.deleteParticipant(participantId, projectId)
        }
    }

    fun updateParticipantName(participantId: UUID, newName: String) {
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty()) return
        val participant = _state.value.participants.firstOrNull { it.id == participantId } ?: return
        scope.launch {
            repository.updateParticipant(participant.copy(name = trimmedName), projectId)
        }
    }

    fun participantHasExpenses(participantId: UUID): Boolean {
        val participantBalance = _state.value.balancesByParticipantId[participantId] ?: 0.0
        val participant = _state.value.participants.firstOrNull { it.id == participantId } ?: return false
        return participant.contributionAmount - participantBalance > 0.0
    }

    private fun recomputeFilter() {
        val query = _state.value.searchText.trim()
        val base = _state.value.participants
        val filtered = base
            .filter { participant ->
                val balance = _state.value.balancesByParticipantId[participant.id] ?: 0.0
                val matchesSearch = query.isEmpty() || participant.name.contains(query, ignoreCase = true)
                val matchesBalance = when (_state.value.selectedBalanceFilter) {
                    ParticipantBalanceFilter.ALL -> true
                    ParticipantBalanceFilter.POSITIVE -> balance > 0.0
                    ParticipantBalanceFilter.NEGATIVE -> balance < 0.0
                    ParticipantBalanceFilter.ZERO -> balance == 0.0
                }
                matchesSearch && matchesBalance
            }
            .sortedWith(
                when (_state.value.selectedSort) {
                    ParticipantSortOption.NAME_ASC -> compareBy { it.name.lowercase() }
                    ParticipantSortOption.CONTRIBUTION_DESC -> compareByDescending<Participant> { it.contributionAmount }
                        .thenBy { it.name.lowercase() }
                    ParticipantSortOption.CONTRIBUTION_ASC -> compareBy<Participant> { it.contributionAmount }
                        .thenBy { it.name.lowercase() }
                    ParticipantSortOption.BALANCE_DESC -> compareByDescending<Participant> {
                        _state.value.balancesByParticipantId[it.id] ?: 0.0
                    }.thenBy { it.name.lowercase() }
                    ParticipantSortOption.BALANCE_ASC -> compareBy<Participant> {
                        _state.value.balancesByParticipantId[it.id] ?: 0.0
                    }.thenBy { it.name.lowercase() }
                }
            )
        _state.value = _state.value.copy(filteredParticipants = filtered)
    }
}
