package com.sharedfinance.data.repository

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import com.google.gson.Gson
import com.sharedfinance.data.local.ConflictResolutionLogEntity
import com.sharedfinance.data.local.ExpenseEntity
import com.sharedfinance.data.local.HistoryEntity
import com.sharedfinance.data.local.ParticipantEntity
import com.sharedfinance.data.local.ProjectEntity
import com.sharedfinance.data.local.SharedFinanceDatabase
import com.sharedfinance.data.local.SyncLogEntity
import com.sharedfinance.model.ChangeHistoryEntry
import com.sharedfinance.model.ConflictDecisionSource
import com.sharedfinance.model.ConflictResolutionDecision
import com.sharedfinance.model.ConflictResolutionLogEntry
import com.sharedfinance.model.Expense
import com.sharedfinance.model.HistoryOperationType
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
import com.sharedfinance.model.ProjectStatus
import com.sharedfinance.model.ResolvedSyncDecision
import com.sharedfinance.model.SyncConflict
import com.sharedfinance.model.SyncLogEntry
import com.sharedfinance.model.SyncPayload
import com.sharedfinance.model.SyncResultType
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

class RoomSharedFinanceRepository(context: Context) : SharedFinanceRepository {
    private val gson = Gson()
    private val database = Room.databaseBuilder(
        context.applicationContext,
        SharedFinanceDatabase::class.java,
        DB_NAME
    )
        .fallbackToDestructiveMigration()
        .build()
    private val dao = database.sharedFinanceDao()

    override fun observeProjects(): Flow<List<Project>> = dao.observeProjects().map { items ->
        items.map { it.toModel() }
    }

    override fun observeParticipants(): Flow<List<Participant>> = dao.observeParticipants().map { items ->
        items.map { it.toModel() }
    }

    override fun observeExpenses(): Flow<List<Expense>> = dao.observeExpenses().map { items ->
        items.map { it.toModel() }
    }

    override fun observeHistory(): Flow<List<ChangeHistoryEntry>> = dao.observeHistory().map { items ->
        items.map { it.toModel() }
    }

    override fun observeSyncLogs(): Flow<List<SyncLogEntry>> = dao.observeSyncLogs().map { items ->
        items.map { it.toModel() }
    }

    override fun observeConflictResolutionLogs(): Flow<List<ConflictResolutionLogEntry>> =
        dao.observeConflictResolutionLogs().map { items -> items.map { it.toModel() } }

    override fun appendSyncLog(entry: SyncLogEntry) {
        runBlocking {
            dao.upsertSyncLogs(listOf(entry.toEntity()))
        }
    }

    override fun appendConflictResolutionLog(entry: ConflictResolutionLogEntry) {
        runBlocking {
            dao.upsertConflictResolutionLogs(listOf(entry.toEntity()))
        }
    }

    override suspend fun createProject(title: String, details: String) {
        val project = Project(title = title, details = details)
        database.withTransaction {
            var projects = loadProjects()
            projects += project
            persistCoreState(
                projects = projects,
                participants = loadParticipants(),
                expenses = loadExpenses(),
                history = loadHistory()
            )
            appendHistoryInternal("Project created: $title", HistoryOperationType.CREATE)
        }
    }

    override suspend fun updateProject(project: Project) {
        database.withTransaction {
            val projects = loadProjects().map { current ->
                if (current.id == project.id) {
                    project.copy(
                        recordVersion = maxOf(current.recordVersion + 1, project.recordVersion),
                        updatedAt = Date()
                    )
                } else {
                    current
                }
            }
            persistCoreState(
                projects = projects,
                participants = loadParticipants(),
                expenses = loadExpenses(),
                history = loadHistory()
            )
            appendHistoryInternal("Project updated: ${project.title}", HistoryOperationType.UPDATE)
        }
    }

    override suspend fun archiveProject(projectId: UUID) {
        val project = loadProjects().firstOrNull { it.id == projectId } ?: return
        updateProject(project.copy(status = ProjectStatus.ARCHIVED))
    }

    override suspend fun deleteProject(projectId: UUID) {
        database.withTransaction {
            val projects = loadProjects()
            val target = projects.firstOrNull { it.id == projectId } ?: return@withTransaction
            val remainingProjects = projects.filterNot { it.id == projectId }
            val remainingParticipantIds = target.participantIds.toSet()
            val remainingExpenseIds = target.expenseIds.toSet()
            val participants = loadParticipants().filterNot { it.id in remainingParticipantIds }
            val expenses = loadExpenses().filterNot { it.id in remainingExpenseIds || it.projectId == projectId }
            persistCoreState(
                projects = remainingProjects,
                participants = participants,
                expenses = expenses,
                history = loadHistory()
            )
            appendHistoryInternal("Project deleted: ${target.title}", HistoryOperationType.DELETE)
        }
    }

    override suspend fun createParticipant(name: String, projectId: UUID?, contributionAmount: Double) {
        val participant = Participant(name = name, contributionAmount = contributionAmount)
        database.withTransaction {
            val participants = loadParticipants() + participant
            val projects = if (projectId == null) {
                loadProjects()
            } else {
                loadProjects().map { project ->
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
            persistCoreState(
                projects = projects,
                participants = participants,
                expenses = loadExpenses(),
                history = loadHistory()
            )
            appendHistoryInternal("Participant added: $name", HistoryOperationType.CREATE)
        }
    }

    override suspend fun deleteParticipant(participantId: UUID, projectId: UUID?) {
        database.withTransaction {
            val participant = loadParticipants().firstOrNull { it.id == participantId } ?: return@withTransaction
            val participants = loadParticipants().filterNot { it.id == participantId }
            val projects = loadProjects().map { project ->
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
            persistCoreState(
                projects = projects,
                participants = participants,
                expenses = loadExpenses(),
                history = loadHistory()
            )
            appendHistoryInternal("Participant deleted: ${participant.name}", HistoryOperationType.DELETE)
        }
    }

    override suspend fun createExpense(expense: Expense) {
        database.withTransaction {
            val projects = loadProjects()
            val project = projects.firstOrNull { it.id == expense.projectId }
            val participant = loadParticipants().firstOrNull { it.id == expense.participantId }
            if (project == null || participant == null || expense.participantId !in project.participantIds) {
                return@withTransaction
            }

            val expenses = loadExpenses() + expense.copy(updatedAt = Date())
            val updatedProjects = projects.map { current ->
                if (current.id == expense.projectId && expense.id !in current.expenseIds) {
                    current.copy(
                        expenseIds = current.expenseIds + expense.id,
                        recordVersion = current.recordVersion + 1,
                        updatedAt = Date()
                    )
                } else {
                    current
                }
            }
            persistCoreState(
                projects = updatedProjects,
                participants = loadParticipants(),
                expenses = expenses,
                history = loadHistory()
            )
            appendHistoryInternal("Expense added: ${expense.title}", HistoryOperationType.CREATE)
        }
    }

    override suspend fun deleteExpense(expenseId: UUID) {
        database.withTransaction {
            val expense = loadExpenses().firstOrNull { it.id == expenseId } ?: return@withTransaction
            val expenses = loadExpenses().filterNot { it.id == expenseId }
            val projects = loadProjects().map { project ->
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
            persistCoreState(
                projects = projects,
                participants = loadParticipants(),
                expenses = expenses,
                history = loadHistory()
            )
            appendHistoryInternal("Expense deleted: ${expense.title}", HistoryOperationType.DELETE)
        }
    }

    override suspend fun exportPayload(): SyncPayload {
        return SyncPayload(
            databaseVersion = "android-room-v1",
            projects = loadProjects(),
            participants = loadParticipants(),
            expenses = loadExpenses(),
            history = loadHistory(),
            syncLogs = loadSyncLogs()
        )
    }

    override suspend fun importPayload(payload: SyncPayload) {
        database.withTransaction {
            val localProjects = loadProjects()
            val localParticipants = loadParticipants()
            val localExpenses = loadExpenses()
            val localHistory = loadHistory()
            val mergedParticipants = mergeRecords(localParticipants, payload.participants, { it.id }, { it.recordVersion }, { it.updatedAt })
            val mergedExpenses = mergeRecords(localExpenses, payload.expenses, { it.id }, { it.recordVersion }, { it.updatedAt })

            persistCoreState(
                projects = mergeProjects(localProjects, payload.projects, emptySet(), mergedExpenses),
                participants = mergedParticipants,
                expenses = mergedExpenses,
                history = mergeRecords(localHistory, payload.history, { it.id }, { it.recordVersion }, { it.date })
            )
        }
    }

    override suspend fun buildSyncPayload(): SyncPayload = exportPayload()

    override suspend fun detectConflicts(remote: SyncPayload): List<SyncConflict> {
        val localProjectsById = loadProjects().associateBy { it.id }
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

        database.withTransaction {
            val currentProjects = loadProjects()
            val mergedParticipants = mergeRecords(loadParticipants(), remote.participants, { it.id }, { it.recordVersion }, { it.updatedAt })
            val mergedExpenses = mergeRecords(loadExpenses(), remote.expenses, { it.id }, { it.recordVersion }, { it.updatedAt })
            val mergedProjects = mergeProjects(currentProjects, remote.projects, allowedRemoteIds, mergedExpenses)
            persistCoreState(
                projects = mergedProjects,
                participants = mergedParticipants,
                expenses = mergedExpenses,
                history = mergeRecords(loadHistory(), remote.history, { it.id }, { it.recordVersion }, { it.date })
            )

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

            appendHistoryInternal("Sync completed", HistoryOperationType.SYNC)
        }
    }

    private suspend fun appendHistoryInternal(description: String, operationType: HistoryOperationType) {
        val currentHistory = loadHistory() + ChangeHistoryEntry(
            operationType = operationType,
            actorName = "Android user",
            description = description,
            date = Date(),
            recordVersion = loadHistory().size + 1
        )
        persistCoreState(
            projects = loadProjects(),
            participants = loadParticipants(),
            expenses = loadExpenses(),
            history = currentHistory
        )
    }

    private suspend fun persistCoreState(
        projects: List<Project>,
        participants: List<Participant>,
        expenses: List<Expense>,
        history: List<ChangeHistoryEntry>
    ) {
        dao.clearProjects()
        dao.clearParticipants()
        dao.clearExpenses()
        dao.clearHistory()
        dao.upsertProjects(projects.map { it.toEntity() })
        dao.upsertParticipants(participants.map { it.toEntity() })
        dao.upsertExpenses(expenses.map { it.toEntity() })
        dao.upsertHistory(history.map { it.toEntity() })
    }

    private suspend fun loadProjects(): List<Project> = dao.getProjectsSnapshot().map { it.toModel() }

    private suspend fun loadParticipants(): List<Participant> = dao.getParticipantsSnapshot().map { it.toModel() }

    private suspend fun loadExpenses(): List<Expense> = dao.getExpensesSnapshot().map { it.toModel() }

    private suspend fun loadHistory(): List<ChangeHistoryEntry> = dao.getHistorySnapshot().map { it.toModel() }

    private suspend fun loadSyncLogs(): List<SyncLogEntry> = dao.getSyncLogsSnapshot().map { it.toModel() }

    private suspend fun loadConflictLogs(): List<ConflictResolutionLogEntry> = dao.getConflictResolutionLogsSnapshot().map { it.toModel() }

    private fun mergeProjects(
        currentProjects: List<Project>,
        remoteProjects: List<Project>,
        allowedRemoteIds: Set<UUID>,
        mergedExpenses: List<Expense>
    ): List<Project> {
        val localMap = currentProjects.associateBy { it.id }.toMutableMap()
        val conflictEntityIds = detectProjectConflictIds(currentProjects, remoteProjects)
        remoteProjects.forEach { remoteProject ->
            val localProject = localMap[remoteProject.id]
            if (localProject == null) {
                localMap[remoteProject.id] = enrichProjectAssociations(remoteProject, mergedExpenses)
                return@forEach
            }

            val isConflict = localProject.id in conflictEntityIds
            val preferRemote = if (isConflict) {
                remoteProject.id in allowedRemoteIds
            } else {
                shouldPreferRemote(
                    localItem = localProject,
                    remoteItem = remoteProject,
                    versionSelector = { it.recordVersion },
                    updatedAtSelector = { it.updatedAt }
                )
            }

            localMap[localProject.id] = mergeProjectRecord(
                localProject = localProject,
                remoteProject = remoteProject,
                preferRemoteScalars = preferRemote,
                mergedExpenses = mergedExpenses
            )
        }
        return localMap.values
            .map { enrichProjectAssociations(it, mergedExpenses) }
            .sortedByDescending { it.updatedAt.time }
    }

    private fun detectProjectConflictIds(localProjects: List<Project>, remoteProjects: List<Project>): Set<UUID> {
        val localById = localProjects.associateBy { it.id }
        return remoteProjects.asSequence()
            .mapNotNull { remoteProject ->
                val localProject = localById[remoteProject.id] ?: return@mapNotNull null
                if (remoteProject.recordVersion != localProject.recordVersion && remoteProject.updatedAt != localProject.updatedAt) {
                    remoteProject.id
                } else {
                    null
                }
            }
            .toSet()
    }

    private fun mergeProjectRecord(
        localProject: Project,
        remoteProject: Project,
        preferRemoteScalars: Boolean,
        mergedExpenses: List<Expense>
    ): Project {
        val baseProject = if (preferRemoteScalars) remoteProject else localProject
        val expenseIds = (localProject.expenseIds + remoteProject.expenseIds + mergedExpenses.filter { it.projectId == localProject.id }.map { it.id })
            .distinct()
        val participantIds = (localProject.participantIds + remoteProject.participantIds + mergedExpenses.filter { it.projectId == localProject.id }.map { it.participantId })
            .distinct()
        return baseProject.copy(
            participantIds = participantIds,
            expenseIds = expenseIds,
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

    private fun Project.toEntity(): ProjectEntity = ProjectEntity(
        id = id.toString(),
        title = title,
        details = details,
        status = status.name,
        recordVersion = recordVersion,
        updatedAt = updatedAt.time,
        createdAt = createdAt.time,
        participantIdsJson = gson.toJson(participantIds.map { it.toString() }),
        expenseIdsJson = gson.toJson(expenseIds.map { it.toString() })
    )

    private fun ProjectEntity.toModel(): Project = Project(
        id = UUID.fromString(id),
        title = title,
        details = details,
        createdAt = Date(createdAt),
        participantIds = decodeUuidList(participantIdsJson),
        expenseIds = decodeUuidList(expenseIdsJson),
        status = runCatching { ProjectStatus.valueOf(status) }.getOrDefault(ProjectStatus.ACTIVE),
        recordVersion = recordVersion,
        updatedAt = Date(updatedAt)
    )

    private fun Participant.toEntity(): ParticipantEntity = ParticipantEntity(
        id = id.toString(),
        name = name,
        contributionAmount = contributionAmount,
        expenseAmount = expenseAmount,
        balanceAmount = balanceAmount,
        comment = comment,
        recordVersion = recordVersion,
        updatedAt = updatedAt.time,
        createdAt = createdAt.time
    )

    private fun ParticipantEntity.toModel(): Participant = Participant(
        id = UUID.fromString(id),
        name = name,
        createdAt = Date(createdAt),
        contributionAmount = contributionAmount,
        expenseAmount = expenseAmount,
        balanceAmount = balanceAmount,
        comment = comment,
        recordVersion = recordVersion,
        updatedAt = Date(updatedAt)
    )

    private fun Expense.toEntity(): ExpenseEntity = ExpenseEntity(
        id = id.toString(),
        projectId = projectId.toString(),
        participantId = participantId.toString(),
        categoryId = categoryId.toString(),
        amount = amount,
        title = title,
        comment = comment,
        date = date.time,
        recordVersion = recordVersion,
        updatedAt = updatedAt.time
    )

    private fun ExpenseEntity.toModel(): Expense = Expense(
        id = UUID.fromString(id),
        projectId = UUID.fromString(projectId),
        participantId = UUID.fromString(participantId),
        amount = amount,
        categoryId = UUID.fromString(categoryId),
        title = title,
        comment = comment,
        date = Date(date),
        recordVersion = recordVersion,
        updatedAt = Date(updatedAt)
    )

    private fun ChangeHistoryEntry.toEntity(): HistoryEntity = HistoryEntity(
        id = id.toString(),
        operationType = operationType.name,
        actorName = actorName,
        date = date.time,
        description = description,
        recordVersion = recordVersion
    )

    private fun HistoryEntity.toModel(): ChangeHistoryEntry = ChangeHistoryEntry(
        id = UUID.fromString(id),
        operationType = runCatching { HistoryOperationType.valueOf(operationType) }.getOrDefault(HistoryOperationType.SYNC),
        actorName = actorName,
        date = Date(date),
        description = description,
        recordVersion = recordVersion
    )

    private fun SyncLogEntry.toEntity(): SyncLogEntity = SyncLogEntity(
        id = id.toString(),
        date = date.time,
        deviceName = deviceName,
        result = result.name,
        changedRecordsCount = changedRecordsCount
    )

    private fun SyncLogEntity.toModel(): SyncLogEntry = SyncLogEntry(
        id = UUID.fromString(id),
        date = Date(date),
        deviceName = deviceName,
        result = runCatching { SyncResultType.valueOf(result) }.getOrDefault(SyncResultType.FAILED),
        changedRecordsCount = changedRecordsCount
    )

    private fun ConflictResolutionLogEntry.toEntity(): ConflictResolutionLogEntity = ConflictResolutionLogEntity(
        id = id.toString(),
        date = date.time,
        entityName = entityName,
        entityId = entityId.toString(),
        localValue = localValue,
        remoteValue = remoteValue,
        decision = decision.name,
        decisionSource = decisionSource.name,
        isApplied = isApplied
    )

    private fun ConflictResolutionLogEntity.toModel(): ConflictResolutionLogEntry = ConflictResolutionLogEntry(
        id = UUID.fromString(id),
        date = Date(date),
        entityName = entityName,
        entityId = UUID.fromString(entityId),
        localValue = localValue,
        remoteValue = remoteValue,
        decision = runCatching { ConflictResolutionDecision.valueOf(decision) }.getOrDefault(ConflictResolutionDecision.KEEP_LOCAL),
        decisionSource = runCatching { ConflictDecisionSource.valueOf(decisionSource) }.getOrDefault(ConflictDecisionSource.MANUAL),
        isApplied = isApplied
    )

    private fun decodeUuidList(json: String): List<UUID> {
        if (json.isBlank()) return emptyList()
        val items = runCatching { gson.fromJson(json, Array<String>::class.java)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        return items.mapNotNull { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
    }

    private companion object {
        const val DB_NAME = "shared_finance.db"
    }
}
