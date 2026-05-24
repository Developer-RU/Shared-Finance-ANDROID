package com.sharedfinance.viewmodel

import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.Expense
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID

data class ProjectDetailUiState(
    val project: Project? = null,
    val participants: List<Participant> = emptyList(),
    val participantBalances: Map<UUID, Double> = emptyMap(),
    val expenses: List<Expense> = emptyList(),
    val totalExpenses: Double = 0.0,
    val isLoading: Boolean = true
)

class ProjectDetailViewModel(
    repository: SharedFinanceRepository,
    private val projectId: UUID
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ProjectDetailUiState())
    val state: StateFlow<ProjectDetailUiState> = _state.asStateFlow()

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
            val expensesByParticipantId = projectExpenses.groupBy { it.participantId }
            val participantBalances = projectParticipants.associate { participant ->
                val spent = expensesByParticipantId[participant.id]?.sumOf { it.amount } ?: 0.0
                participant.id to (participant.contributionAmount - spent)
            }
            ProjectDetailUiState(
                project = project,
                participants = projectParticipants,
                participantBalances = participantBalances,
                expenses = projectExpenses.sortedByDescending { it.date.time },
                totalExpenses = projectExpenses.sumOf { it.amount },
                isLoading = false
            )
        }
            .onEach { _state.value = it }
            .launchIn(scope)
    }
}
