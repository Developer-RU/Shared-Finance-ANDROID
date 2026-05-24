package com.sharedfinance.data.repository

import android.content.Context
import com.google.gson.Gson
import com.sharedfinance.model.ChangeHistoryEntry
import com.sharedfinance.model.Expense
import com.sharedfinance.model.HistoryOperationType
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
import com.sharedfinance.model.ProjectStatus
import com.sharedfinance.model.SyncLogEntry
import com.sharedfinance.model.SyncPayload
import com.sharedfinance.model.SyncResultType
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemorySharedFinanceRepository(context: Context) : SharedFinanceRepository {
    private val gson = Gson()
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val projects = MutableStateFlow<List<Project>>(emptyList())
    private val participants = MutableStateFlow<List<Participant>>(emptyList())
    private val expenses = MutableStateFlow<List<Expense>>(emptyList())
    private val history = MutableStateFlow<List<ChangeHistoryEntry>>(emptyList())
    private val syncLogs = MutableStateFlow<List<SyncLogEntry>>(emptyList())

    private data class PersistedState(
        val projects: List<Project> = emptyList(),
        val participants: List<Participant> = emptyList(),
        val expenses: List<Expense> = emptyList(),
        val history: List<ChangeHistoryEntry> = emptyList(),
        val syncLogs: List<SyncLogEntry> = emptyList()
    )

    init {
        loadState()
    }

    override fun observeProjects(): Flow<List<Project>> = projects
    override fun observeParticipants(): Flow<List<Participant>> = participants
    override fun observeExpenses(): Flow<List<Expense>> = expenses
    override fun observeHistory(): Flow<List<ChangeHistoryEntry>> = history
    override fun observeSyncLogs(): Flow<List<SyncLogEntry>> = syncLogs

    override fun appendSyncLog(entry: SyncLogEntry) {
        syncLogs.update { it + entry }
        persistState()
    }

    override suspend fun createProject(title: String, details: String) {
        projects.update { it + Project(title = title, details = details) }
        appendHistory("Project created: $title", HistoryOperationType.CREATE)
    }

    override suspend fun updateProject(project: Project) {
        projects.update { current ->
            current.map {
                if (it.id == project.id) {
                    project.copy(
                        recordVersion = maxOf(project.recordVersion, it.recordVersion + 1),
                        updatedAt = Date()
                    )
                } else {
                    it
                }
            }
        }
        appendHistory("Project updated: ${project.title}", HistoryOperationType.UPDATE)
    }

    override suspend fun archiveProject(projectId: UUID) {
        val project = projects.value.firstOrNull { it.id == projectId } ?: return
        val status = if (project.status == ProjectStatus.ACTIVE) ProjectStatus.ARCHIVED else ProjectStatus.ACTIVE
        updateProject(project.copy(status = status))
    }

    override suspend fun deleteProject(projectId: UUID) {
        val project = projects.value.firstOrNull { it.id == projectId } ?: return
        val remainingProjects = projects.value.filterNot { it.id == projectId }
        val removedExpenseIds = expenses.value.filter { it.projectId == projectId }.map { it.id }.toSet()
        val remainingExpenses = expenses.value.filterNot { it.id in removedExpenseIds }
        val participantIdsInProject = project.participantIds.toSet()
        val participantIdsStillReferenced = remainingProjects.flatMap { it.participantIds }.toSet()
        val orphanParticipantIds = participantIdsInProject.filterNot { it in participantIdsStillReferenced }.toSet()
        val remainingParticipants = participants.value.filterNot { it.id in orphanParticipantIds }

        projects.value = remainingProjects
        expenses.value = remainingExpenses
        participants.value = remainingParticipants
        appendHistory("Project deleted: ${project.title}", HistoryOperationType.DELETE)
    }

    override suspend fun createParticipant(name: String, projectId: UUID?, contributionAmount: Double) {
        val participant = Participant(name = name, contributionAmount = contributionAmount)
        participants.update { it + participant }
        if (projectId != null) {
            projects.update { current ->
                current.map { project ->
                    if (project.id == projectId && participant.id !in project.participantIds) {
                        project.copy(
                            participantIds = project.participantIds + participant.id,
                            recordVersion = project.recordVersion + 1,
                            updatedAt = Date()
                        )
                    } else {
                        project
                    }
                }
            }
        }
        appendHistory("Participant added: $name", HistoryOperationType.CREATE)
    }

    override suspend fun updateParticipant(participant: Participant, projectId: UUID?) {
        participants.update { current ->
            current.map {
                if (it.id == participant.id) {
                    participant.copy(
                        recordVersion = maxOf(participant.recordVersion, it.recordVersion + 1),
                        updatedAt = Date()
                    )
                } else {
                    it
                }
            }
        }
        appendHistory("Participant updated: ${participant.name}", HistoryOperationType.UPDATE)
    }

    override suspend fun deleteParticipant(participantId: UUID, projectId: UUID?): Boolean {
        val participant = participants.value.firstOrNull { it.id == participantId } ?: return false
        val allExpenses = expenses.value
        val participantHasExpensesAnywhere = allExpenses.any { it.participantId == participantId }
        val participantReferencedByAnyProject = projects.value.any { participantId in it.participantIds }
        val targetProjectIds = if (projectId == null) {
            projects.value.filter { participantId in it.participantIds }.map { it.id }.toSet()
        } else {
            setOf(projectId)
        }

        if (targetProjectIds.isEmpty()) {
            if (participantReferencedByAnyProject || participantHasExpensesAnywhere) return false
            participants.value = participants.value.filterNot { it.id == participantId }
            appendHistory("Participant deleted: ${participant.name}", HistoryOperationType.DELETE)
            return true
        }

        val affectedProjectIds = projects.value
            .filter { project ->
                project.id in targetProjectIds && (
                    participantId in project.participantIds ||
                        allExpenses.any { expense -> expense.projectId == project.id && expense.participantId == participantId }
                    )
            }
            .map { it.id }
            .toSet()
        if (affectedProjectIds.isEmpty()) return false

        val expensesToDelete = allExpenses.filter { it.participantId == participantId && it.projectId in affectedProjectIds }
        expenses.value = expenses.value.filterNot { it.id in expensesToDelete.map { exp -> exp.id }.toSet() }

        projects.update { current ->
            current.map { project ->
                if (project.id in affectedProjectIds) {
                    project.copy(
                        participantIds = project.participantIds.filterNot { it == participantId },
                        expenseIds = project.expenseIds.filterNot { expId -> expensesToDelete.any { it.id == expId } },
                        recordVersion = project.recordVersion + 1,
                        updatedAt = Date()
                    )
                } else {
                    project
                }
            }
        }

        val isStillReferenced = projects.value.any { participantId in it.participantIds }
        if (!isStillReferenced) {
            participants.value = participants.value.filterNot { it.id == participantId }
        }

        appendHistory("Participant deleted: ${participant.name}", HistoryOperationType.DELETE)
        return true
    }

    override suspend fun createExpense(expense: Expense) {
        val project = projects.value.firstOrNull { it.id == expense.projectId } ?: return
        val participant = participants.value.firstOrNull { it.id == expense.participantId } ?: return
        if (participant.id !in project.participantIds || expense.amount <= 0.0) return

        expenses.update { it + expense.copy(updatedAt = Date()) }
        projects.update { current ->
            current.map {
                if (it.id == expense.projectId && expense.id !in it.expenseIds) {
                    it.copy(
                        expenseIds = it.expenseIds + expense.id,
                        recordVersion = it.recordVersion + 1,
                        updatedAt = Date()
                    )
                } else {
                    it
                }
            }
        }
        appendHistory("Expense added: ${expense.title}", HistoryOperationType.CREATE)
    }

    override suspend fun updateExpense(expense: Expense): Boolean {
        if (expense.amount <= 0.0) return false
        val project = projects.value.firstOrNull { it.id == expense.projectId } ?: return false
        val participant = participants.value.firstOrNull { it.id == expense.participantId } ?: return false
        if (participant.id !in project.participantIds) return false

        val current = expenses.value.firstOrNull { it.id == expense.id } ?: return false
        expenses.update { list ->
            list.map {
                if (it.id == expense.id) {
                    expense.copy(
                        recordVersion = maxOf(expense.recordVersion, current.recordVersion + 1),
                        updatedAt = Date()
                    )
                } else {
                    it
                }
            }
        }
        appendHistory("Expense updated: ${expense.title}", HistoryOperationType.UPDATE)
        return true
    }

    override suspend fun deleteExpense(expenseId: UUID) {
        val expense = expenses.value.firstOrNull { it.id == expenseId } ?: return
        expenses.value = expenses.value.filterNot { it.id == expenseId }
        projects.update { current ->
            current.map {
                if (expenseId in it.expenseIds) {
                    it.copy(
                        expenseIds = it.expenseIds.filterNot { id -> id == expenseId },
                        recordVersion = it.recordVersion + 1,
                        updatedAt = Date()
                    )
                } else {
                    it
                }
            }
        }
        appendHistory("Expense deleted: ${expense.title}", HistoryOperationType.DELETE)
    }

    override suspend fun exportPayload(): SyncPayload {
        return SyncPayload(
            databaseVersion = "android-kotlin-v1",
            projects = projects.value,
            participants = participants.value,
            expenses = expenses.value,
            history = history.value,
            syncLogs = syncLogs.value
        )
    }

    override suspend fun importPayload(payload: SyncPayload) {
        participants.value = mergeRecords(participants.value, payload.participants, { it.id }, { it.recordVersion }, { it.updatedAt })
        expenses.value = mergeRecords(expenses.value, payload.expenses, { it.id }, { it.recordVersion }, { it.updatedAt })
        projects.value = mergeProjects(projects.value, payload.projects, expenses.value)
        history.value = mergeRecords(history.value, payload.history, { it.id }, { it.recordVersion }, { it.date })
        syncLogs.value = mergeRecords(syncLogs.value, payload.syncLogs, { it.id }, { 1 }, { it.date })
        persistState()
    }

    override suspend fun buildSyncPayload(): SyncPayload = exportPayload()

    private fun mergeProjects(local: List<Project>, remote: List<Project>, mergedExpenses: List<Expense>): List<Project> {
        val merged = local.associateBy { it.id }.toMutableMap()
        for (remoteProject in remote) {
            val localProject = merged[remoteProject.id]
            if (localProject == null) {
                merged[remoteProject.id] = enrichProjectAssociations(remoteProject, mergedExpenses)
                continue
            }
            val preferRemote = shouldPreferRemote(localProject, remoteProject, { it.recordVersion }, { it.updatedAt })
            val base = if (preferRemote) remoteProject else localProject
            val projectExpenses = mergedExpenses.filter { it.projectId == base.id }
            merged[base.id] = base.copy(
                participantIds = (localProject.participantIds + remoteProject.participantIds + projectExpenses.map { it.participantId }).distinct(),
                expenseIds = (localProject.expenseIds + remoteProject.expenseIds + projectExpenses.map { it.id }).distinct(),
                recordVersion = maxOf(localProject.recordVersion, remoteProject.recordVersion),
                updatedAt = maxOf(localProject.updatedAt, remoteProject.updatedAt)
            )
        }
        return merged.values.map { enrichProjectAssociations(it, mergedExpenses) }
    }

    private fun enrichProjectAssociations(project: Project, mergedExpenses: List<Expense>): Project {
        val projectExpenses = mergedExpenses.filter { it.projectId == project.id }
        return project.copy(
            participantIds = (project.participantIds + projectExpenses.map { it.participantId }).distinct(),
            expenseIds = (project.expenseIds + projectExpenses.map { it.id }).distinct()
        )
    }

    private fun <T, K> mergeRecords(
        local: List<T>,
        remote: List<T>,
        keySelector: (T) -> K,
        versionSelector: (T) -> Int,
        updatedAtSelector: (T) -> Date
    ): List<T> {
        val merged = local.associateBy(keySelector).toMutableMap()
        for (remoteItem in remote) {
            val key = keySelector(remoteItem)
            val localItem = merged[key]
            if (localItem == null || shouldPreferRemote(localItem, remoteItem, versionSelector, updatedAtSelector)) {
                merged[key] = remoteItem
            }
        }
        return merged.values.toList()
    }

    private fun <T> shouldPreferRemote(
        localItem: T,
        remoteItem: T,
        versionSelector: (T) -> Int,
        updatedAtSelector: (T) -> Date
    ): Boolean {
        val localVersion = versionSelector(localItem)
        val remoteVersion = versionSelector(remoteItem)
        if (remoteVersion != localVersion) return remoteVersion > localVersion
        return updatedAtSelector(remoteItem).after(updatedAtSelector(localItem))
    }

    private fun appendHistory(description: String, operationType: HistoryOperationType) {
        history.update {
            it + ChangeHistoryEntry(
                operationType = operationType,
                actorName = "Android user",
                description = description,
                date = Date(),
                recordVersion = (it.size + 1)
            )
        }
        persistState()
    }

    private fun persistState() {
        val state = PersistedState(
            projects = projects.value,
            participants = participants.value,
            expenses = expenses.value,
            history = history.value,
            syncLogs = syncLogs.value
        )
        prefs.edit().putString(KEY_STATE, gson.toJson(state)).apply()
    }

    private fun loadState() {
        val rawState = prefs.getString(KEY_STATE, null) ?: return
        runCatching { gson.fromJson(rawState, PersistedState::class.java) }
            .onSuccess { state ->
                if (state != null) {
                    projects.value = state.projects
                    participants.value = state.participants
                    expenses.value = state.expenses
                    history.value = state.history
                    syncLogs.value = state.syncLogs
                }
            }
    }

    private companion object {
        const val PREFS_NAME = "shared_finance_repo"
        const val KEY_STATE = "repository_state"
    }
}
