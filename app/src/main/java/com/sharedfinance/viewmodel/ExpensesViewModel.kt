package com.sharedfinance.viewmodel

import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.Expense
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
import java.util.Date
import java.util.UUID

enum class ExpenseSortOption {
    NEWEST,
    OLDEST,
    AMOUNT_DESC,
    AMOUNT_ASC
}

data class ExpensesUiState(
    val expenses: List<Expense> = emptyList(),
    val participants: List<Participant> = emptyList(),
    val filteredExpenses: List<Expense> = emptyList(),
    val searchText: String = "",
    val draftTitle: String = "",
    val draftAmount: String = "",
    val draftComment: String = "",
    val selectedDraftParticipantId: UUID? = null,
    val selectedFilterParticipantId: UUID? = null,
    val selectedSort: ExpenseSortOption = ExpenseSortOption.NEWEST,
    val isLoading: Boolean = true
)

class ExpensesViewModel(
    private val repository: SharedFinanceRepository,
    private val projectId: UUID
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ExpensesUiState())
    val state: StateFlow<ExpensesUiState> = _state.asStateFlow()

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
            val projectExpenses = expenses
                .filter { it.projectId == projectId }
            Triple(projectParticipants, projectExpenses, project)
        }
            .onEach { (projectParticipants, projectExpenses, _) ->
                val currentDraft = _state.value.selectedDraftParticipantId
                val currentFilter = _state.value.selectedFilterParticipantId
                val selectedDraftId = currentDraft?.takeIf { id -> projectParticipants.any { it.id == id } }
                    ?: projectParticipants.firstOrNull()?.id
                val selectedFilterId = currentFilter?.takeIf { id -> projectParticipants.any { it.id == id } }
                _state.value = _state.value.copy(
                    participants = projectParticipants,
                    expenses = projectExpenses,
                    selectedDraftParticipantId = selectedDraftId,
                    selectedFilterParticipantId = selectedFilterId,
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

    fun updateDraftTitle(value: String) {
        _state.value = _state.value.copy(draftTitle = value)
    }

    fun updateDraftAmount(value: String) {
        _state.value = _state.value.copy(draftAmount = value)
    }

    fun updateDraftComment(value: String) {
        _state.value = _state.value.copy(draftComment = value)
    }

    fun selectDraftParticipant(participantId: UUID?) {
        _state.value = _state.value.copy(selectedDraftParticipantId = participantId)
    }

    fun selectFilterParticipant(participantId: UUID?) {
        _state.value = _state.value.copy(selectedFilterParticipantId = participantId)
        recomputeFilter()
    }

    fun updateSort(sort: ExpenseSortOption) {
        _state.value = _state.value.copy(selectedSort = sort)
        recomputeFilter()
    }

    fun addExpense() {
        val title = _state.value.draftTitle.trim()
        val amount = _state.value.draftAmount.replace(",", ".").toDoubleOrNull() ?: return
        val participantId = _state.value.selectedDraftParticipantId ?: _state.value.participants.firstOrNull()?.id ?: return
        if (title.isEmpty() || amount <= 0.0) return

        scope.launch {
            repository.createExpense(
                Expense(
                    projectId = projectId,
                    participantId = participantId,
                    amount = amount,
                    categoryId = UUID.randomUUID(),
                    title = title,
                    comment = _state.value.draftComment.trim(),
                    date = Date()
                )
            )
            _state.value = _state.value.copy(draftTitle = "", draftAmount = "", draftComment = "")
        }
    }

    fun deleteExpense(expenseId: UUID) {
        scope.launch {
            repository.deleteExpense(expenseId)
        }
    }

    private fun recomputeFilter() {
        val query = _state.value.searchText.trim()
        val selectedParticipantId = _state.value.selectedFilterParticipantId
        val filtered = _state.value.expenses.filter { expense ->
            val matchesSearch = query.isEmpty() ||
                expense.title.contains(query, ignoreCase = true) ||
                expense.comment.contains(query, ignoreCase = true)
            val matchesParticipant = selectedParticipantId == null || expense.participantId == selectedParticipantId
            matchesSearch && matchesParticipant
        }.sortedWith(
            when (_state.value.selectedSort) {
                ExpenseSortOption.NEWEST -> compareByDescending<Expense> { it.date.time }
                ExpenseSortOption.OLDEST -> compareBy<Expense> { it.date.time }
                ExpenseSortOption.AMOUNT_DESC -> compareByDescending<Expense> { it.amount }
                    .thenByDescending { it.date.time }
                ExpenseSortOption.AMOUNT_ASC -> compareBy<Expense> { it.amount }
                    .thenByDescending { it.date.time }
            }
        )
        _state.value = _state.value.copy(filteredExpenses = filtered)
    }
}
