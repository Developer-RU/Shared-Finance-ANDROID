package com.sharedfinance.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.sharedfinance.model.ChangeHistoryEntry
import com.sharedfinance.model.ConflictDecisionSource
import com.sharedfinance.model.ConflictResolutionDecision
import com.sharedfinance.model.ConflictResolutionLogEntry
import com.sharedfinance.model.Expense
import com.sharedfinance.model.HistoryOperationType
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
import com.sharedfinance.model.ResolvedSyncDecision
import com.sharedfinance.model.SyncConflict
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
    private val conflictLog = MutableStateFlow<List<ConflictResolutionLogEntry>>(emptyList())

    private data class PersistedState(
        val projects: List<Project> = emptyList(),
        val participants: List<Participant> = emptyList(),
        val expenses: List<Expense> = emptyList(),
        val history: List<ChangeHistoryEntry> = emptyList(),
        val syncLogs: List<SyncLogEntry> = emptyList(),
        val conflictLog: List<ConflictResolutionLogEntry> = emptyList()
    )

    init {
        loadState()
    }

    override fun observeProjects(): Flow<List<Project>> = projects

    override fun observeParticipants(): Flow<List<Participant>> = participants

    override fun observeExpenses(): Flow<List<Expense>> = expenses

    override fun observeHistory(): Flow<List<ChangeHistoryEntry>> = history

    override fun observeSyncLogs(): Flow<List<SyncLogEntry>> = syncLogs

    override fun observeConflictResolutionLogs(): Flow<List<ConflictResolutionLogEntry>> = conflictLog

    override fun appendSyncLog(entry: SyncLogEntry) {
        syncLogs.update { it + entry }
        persistState()
    }

    override fun appendConflictResolutionLog(entry: ConflictResolutionLogEntry) {
        conflictLog.update { it + entry }
        persistState()
    }

    override suspend fun createProject(title: String, details: String) {
        val project = Project(title = title, details = details)
        projects.update { it + project }
        appendHistory("Project created: $title", HistoryOperationType.CREATE)
        persistState()
    }

    override suspend fun updateProject(project: Project) {
        projects.update { current ->
            current.map {
                if (it.id == project.id) {
                    project.copy(
                        recordVersion = maxOf(it.recordVersion + 1, project.recordVersion),
                        updatedAt = Date()
                    )
                } else {
                    it
                }
            }
        }
        appendHistory("Project updated: ${project.title}", HistoryOperationType.UPDATE)
        persistState()
    }

    override suspend fun archiveProject(projectId: UUID) {
        val project = projects.value.firstOrNull { it.id == projectId } ?: return
        updateProject(project.copy(status = com.sharedfinance.model.ProjectStatus.ARCHIVED))
    }

    override suspend fun deleteProject(projectId: UUID) {
        val project = projects.value.firstOrNull { it.id == projectId } ?: return
        projects.update { list -> list.filterNot { it.id == projectId } }

        val participantIds = project.participantIds.toSet()
        val expenseIds = project.expenseIds.toSet()
        participants.update { list -> list.filterNot { it.id in participantIds } }
        expenses.update { list -> list.filterNot { it.id in expenseIds || it.projectId == projectId } }

        appendHistory("Project deleted: ${project.title}", HistoryOperationType.DELETE)
        persistState()
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
        persistState()
    }

    override suspend fun deleteParticipant(participantId: UUID, projectId: UUID?) {
        val participant = participants.value.firstOrNull { it.id == participantId } ?: return
        participants.update { it.filterNot { item -> item.id == participantId } }

        projects.update { current ->
            current.map { project ->
                val shouldTouch = participantId in project.participantIds && (projectId == null || project.id == projectId)
                if (shouldTouch) {
                    project.copy(
                        participantIds = project.participantIds.filterNot { it == participantId },
                        recordVersion = project.recordVersion + 1,
                        updatedAt = Date()
                    )
                } else {
                    project
                }
            }
        }

        appendHistory("Participant deleted: ${participant.name}", HistoryOperationType.DELETE)
        persistState()
    }

    override suspend fun createExpense(expense: Expense) {
        expenses.update { it + expense.copy(updatedAt = Date()) }

        projects.update { current ->
            current.map { project ->
                if (project.id == expense.projectId && expense.id !in project.expenseIds) {
                    project.copy(
                        expenseIds = project.expenseIds + expense.id,
                        recordVersion = project.recordVersion + 1,
                        updatedAt = Date()
                    )
                } else {
                    project
                }
            }
        }

        appendHistory("Expense added: ${expense.title}", HistoryOperationType.CREATE)
        persistState()
    }

    override suspend fun deleteExpense(expenseId: UUID) {
        val expense = expenses.value.firstOrNull { it.id == expenseId } ?: return
        expenses.update { list -> list.filterNot { it.id == expenseId } }
        projects.update { current ->
            current.map { project ->
                if (expenseId in project.expenseIds) {
                    project.copy(
                        expenseIds = project.expenseIds.filterNot { it == expenseId },
                        recordVersion = project.recordVersion + 1,
                        updatedAt = Date()
                    )
                } else {
                    project
                }
            }
        }
        appendHistory("Expense deleted: ${expense.title}", HistoryOperationType.DELETE)
        persistState()
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
        val mergedParticipants = mergeRecords(participants.value, payload.participants, { it.id }, { it.recordVersion }, { it.updatedAt })
        val mergedExpenses = mergeRecords(expenses.value, payload.expenses, { it.id }, { it.recordVersion }, { it.updatedAt })
        projects.value = mergeProjects(payload.projects, emptySet(), mergedExpenses)
        participants.value = mergedParticipants
        expenses.value = mergedExpenses
        history.value = mergeRecords(history.value, payload.history, { it.id }, { it.recordVersion }, { it.date })
        persistState()
    }

    override suspend fun buildSyncPayload(): SyncPayload {
        return exportPayload()
    }

    override suspend fun detectConflicts(remote: SyncPayload): List<SyncConflict> {
        val localProjectsById = projects.value.associateBy { it.id }
        val conflicts = mutableListOf<SyncConflict>()
        remote.projects.forEach { remoteProject ->
            val localProject = localProjectsById[remoteProject.id] ?: return@forEach
            if (remoteProject.recordVersion != localProject.recordVersion && remoteProject.updatedAt != localProject.updatedAt) {
                conflicts += SyncConflict(
                    entityName = "Project",
                    entityId = remoteProject.id,
                    localValue = "${localProject.title} (${localProject.details})",
                    remoteValue = "${remoteProject.title} (${remoteProject.details})",
                    localRecordVersion = localProject.recordVersion,
                    remoteRecordVersion = remoteProject.recordVersion,
                    localUpdatedAt = localProject.updatedAt,
                    remoteUpdatedAt = remoteProject.updatedAt
                )
            }
        }
        return conflicts
    }

    override suspend fun applySync(remote: SyncPayload, decisions: List<ResolvedSyncDecision>) {
        val decisionById = decisions.associateBy { it.conflictId }
        val conflicts = detectConflicts(remote)
        val allowedRemoteIds = conflicts
            .filter { conflict -> decisionById[conflict.id]?.acceptRemote == true }
            .map { it.entityId }
            .toSet()

        val mergedParticipants = mergeRecords(participants.value, remote.participants, { it.id }, { it.recordVersion }, { it.updatedAt })
        val mergedExpenses = mergeRecords(expenses.value, remote.expenses, { it.id }, { it.recordVersion }, { it.updatedAt })
        val mergedProjects = mergeProjects(remote.projects, allowedRemoteIds, mergedExpenses)
        projects.value = mergedProjects

        participants.value = mergedParticipants
        expenses.value = mergedExpenses
        history.value = mergeRecords(history.value, remote.history, { it.id }, { it.recordVersion }, { it.date })

        conflicts.forEach { conflict ->
            val decision = decisionById[conflict.id]
            if (decision != null) {
                appendConflictResolutionLog(
                    ConflictResolutionLogEntry(
                        entityName = conflict.entityName,
                        entityId = conflict.entityId,
                        localValue = conflict.localValue,
                        remoteValue = conflict.remoteValue,
                        decision = if (decision.acceptRemote) ConflictResolutionDecision.ACCEPT_REMOTE else ConflictResolutionDecision.KEEP_LOCAL,
                        decisionSource = decision.source,
                        isApplied = true,
                        date = Date()
                    )
                )
            }
        }

        appendSyncLog(
            SyncLogEntry(
                date = Date(),
                deviceName = "SharedFinance Peer",
                result = if (conflicts.isEmpty()) SyncResultType.SUCCESS else SyncResultType.CONFLICT,
                changedRecordsCount = remote.projects.size + remote.participants.size + remote.expenses.size + remote.history.size
            )
        )

        Log.d(
            "SharedFinanceSync",
            "import_payload projects=${remote.projects.size} participants=${remote.participants.size} expenses=${remote.expenses.size} history=${remote.history.size} syncLogs=${remote.syncLogs.size} conflicts=${conflicts.size}"
        )

        appendHistory("Sync completed", HistoryOperationType.SYNC)
        persistState()
    }

    private fun mergeProjects(remote: List<Project>, allowedRemoteIds: Set<UUID>, mergedExpenses: List<Expense>): List<Project> {
        val localMap = projects.value.associateBy { it.id }.toMutableMap()
        remote.forEach { remoteProject ->
            val localProject = localMap[remoteProject.id]
            if (localProject == null) {
                localMap[remoteProject.id] = enrichProjectAssociations(remoteProject, mergedExpenses)
                return@forEach
            }

            val preferRemote = remoteProject.id in allowedRemoteIds || shouldPreferRemote(
                localItem = localProject,
                remoteItem = remoteProject,
                versionSelector = { it.recordVersion },
                updatedAtSelector = { it.updatedAt }
            )
            localMap[remoteProject.id] = mergeProjectRecord(localProject, remoteProject, preferRemote, mergedExpenses)
        }
        return localMap.values
            .map { enrichProjectAssociations(it, mergedExpenses) }
            .sortedByDescending { it.updatedAt.time }
    }

    private fun mergeProjectRecord(
        localProject: Project,
        remoteProject: Project,
        preferRemoteScalars: Boolean,
        mergedExpenses: List<Expense>
    ): Project {
        val baseProject = if (preferRemoteScalars) remoteProject else localProject
        val projectExpenses = mergedExpenses.filter { it.projectId == localProject.id }
        return baseProject.copy(
            participantIds = (localProject.participantIds + remoteProject.participantIds + projectExpenses.map { it.participantId }).distinct(),
            expenseIds = (localProject.expenseIds + remoteProject.expenseIds + projectExpenses.map { it.id }).distinct(),
            recordVersion = maxOf(localProject.recordVersion, remoteProject.recordVersion),
            updatedAt = maxOf(localProject.updatedAt, remoteProject.updatedAt)
        )
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
        remote.forEach { remoteItem ->
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
        if (remoteVersion != localVersion) {
            return remoteVersion > localVersion
        }
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
            syncLogs = syncLogs.value,
            conflictLog = conflictLog.value
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
                    conflictLog.value = state.conflictLog
                }
            }
    }

    private companion object {
        const val PREFS_NAME = "shared_finance_repo"
        const val KEY_STATE = "repository_state"
    }
}
