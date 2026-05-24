package com.sharedfinance.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.google.gson.Gson
import com.sharedfinance.data.local.ExpenseEntity
import com.sharedfinance.data.local.HistoryEntity
import com.sharedfinance.data.local.ParticipantEntity
import com.sharedfinance.data.local.ProjectEntity
import com.sharedfinance.data.local.SharedFinanceDatabase
import com.sharedfinance.data.local.SyncLogEntity
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

    override fun appendSyncLog(entry: SyncLogEntry) {
        runBlocking {
            dao.upsertSyncLogs(listOf(entry.toEntity()))
        }
    }

    override suspend fun createProject(title: String, details: String) {
        val project = Project(title = title, details = details)
        database.withTransaction {
            dao.upsertProjects(listOf(project.toEntity()))
            appendHistoryInternal("Project created: $title", HistoryOperationType.CREATE)
        }
    }

    override suspend fun updateProject(project: Project) {
        database.withTransaction {
            val current = loadProjects().firstOrNull { it.id == project.id } ?: return@withTransaction
            val updated = project.copy(
                recordVersion = maxOf(project.recordVersion, current.recordVersion + 1),
                updatedAt = Date()
            )
            dao.upsertProjects(listOf(updated.toEntity()))
            appendHistoryInternal("Project updated: ${updated.title}", HistoryOperationType.UPDATE)
        }
    }

    override suspend fun archiveProject(projectId: UUID) {
        val project = loadProjects().firstOrNull { it.id == projectId } ?: return
        val status = if (project.status == ProjectStatus.ACTIVE) ProjectStatus.ARCHIVED else ProjectStatus.ACTIVE
        updateProject(project.copy(status = status))
    }

    override suspend fun deleteProject(projectId: UUID) {
        database.withTransaction {
            val projects = loadProjects()
            val target = projects.firstOrNull { it.id == projectId } ?: return@withTransaction
            val remainingProjects = projects.filterNot { it.id == projectId }

            val projectExpenses = loadExpenses().filter { it.projectId == projectId }
            val remainingExpenses = loadExpenses().filterNot { it.id in projectExpenses.map { exp -> exp.id }.toSet() }

            val participantIdsInDeletedProject = target.participantIds.toSet()
            val participantIdsStillReferenced = remainingProjects.flatMap { it.participantIds }.toSet()
            val orphanParticipantIds = participantIdsInDeletedProject.filterNot { it in participantIdsStillReferenced }.toSet()
            val remainingParticipants = loadParticipants().filterNot { it.id in orphanParticipantIds }

            persistCoreState(
                projects = remainingProjects,
                participants = remainingParticipants,
                expenses = remainingExpenses,
                history = loadHistory()
            )
            appendHistoryInternal("Project deleted: ${target.title}", HistoryOperationType.DELETE)
        }
    }

    override suspend fun createParticipant(name: String, projectId: UUID?, contributionAmount: Double) {
        val participant = Participant(name = name, contributionAmount = contributionAmount)
        database.withTransaction {
            val updatedParticipants = loadParticipants() + participant
            val updatedProjects = if (projectId == null) {
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
                projects = updatedProjects,
                participants = updatedParticipants,
                expenses = loadExpenses(),
                history = loadHistory()
            )
            appendHistoryInternal("Participant added: $name", HistoryOperationType.CREATE)
        }
    }

    override suspend fun updateParticipant(participant: Participant, projectId: UUID?) {
        database.withTransaction {
            val current = loadParticipants().firstOrNull { it.id == participant.id } ?: return@withTransaction
            val updated = participant.copy(
                recordVersion = maxOf(participant.recordVersion, current.recordVersion + 1),
                updatedAt = Date()
            )
            val participants = loadParticipants().map { if (it.id == updated.id) updated else it }
            persistCoreState(
                projects = loadProjects(),
                participants = participants,
                expenses = loadExpenses(),
                history = loadHistory()
            )
            appendHistoryInternal("Participant updated: ${updated.name}", HistoryOperationType.UPDATE)
        }
    }

    override suspend fun deleteParticipant(participantId: UUID, projectId: UUID?): Boolean {
        return database.withTransaction {
            val participant = loadParticipants().firstOrNull { it.id == participantId } ?: return@withTransaction false
            val projects = loadProjects()
            val allExpenses = loadExpenses()
            val participantHasExpensesAnywhere = allExpenses.any { it.participantId == participantId }
            val participantReferencedByAnyProject = projects.any { participantId in it.participantIds }
            val targetProjectIds = if (projectId == null) {
                projects.filter { participantId in it.participantIds }.map { it.id }.toSet()
            } else {
                setOf(projectId)
            }

            if (targetProjectIds.isEmpty()) {
                if (participantReferencedByAnyProject || participantHasExpensesAnywhere) {
                    return@withTransaction false
                }

                persistCoreState(
                    projects = projects,
                    participants = loadParticipants().filterNot { it.id == participantId },
                    expenses = allExpenses,
                    history = loadHistory()
                )
                appendHistoryInternal("Participant deleted: ${participant.name}", HistoryOperationType.DELETE)
                return@withTransaction true
            }

            val affectedProjectIds = projects
                .filter { project ->
                    project.id in targetProjectIds && (
                        participantId in project.participantIds ||
                            allExpenses.any { expense -> expense.projectId == project.id && expense.participantId == participantId }
                        )
                }
                .map { it.id }
                .toSet()

            if (affectedProjectIds.isEmpty()) {
                return@withTransaction false
            }

            val expensesToDelete = allExpenses.filter { expense ->
                expense.participantId == participantId && expense.projectId in affectedProjectIds
            }
            val remainingExpenses = allExpenses.filterNot { it.id in expensesToDelete.map { exp -> exp.id }.toSet() }

            val updatedProjects = projects.map { project ->
                if (project.id in affectedProjectIds) {
                    project.copy(
                        participantIds = project.participantIds.filterNot { it == participantId },
                        expenseIds = project.expenseIds.filterNot { expenseId -> expensesToDelete.any { it.id == expenseId } },
                        recordVersion = project.recordVersion + 1,
                        updatedAt = Date()
                    )
                } else {
                    project
                }
            }

            val isStillReferenced = updatedProjects.any { participantId in it.participantIds }
            val participants = if (isStillReferenced) {
                loadParticipants()
            } else {
                loadParticipants().filterNot { it.id == participantId }
            }

            persistCoreState(
                projects = updatedProjects,
                participants = participants,
                expenses = remainingExpenses,
                history = loadHistory()
            )
            appendHistoryInternal("Participant deleted: ${participant.name}", HistoryOperationType.DELETE)
            true
        }
    }

    override suspend fun createExpense(expense: Expense) {
        database.withTransaction {
            val project = loadProjects().firstOrNull { it.id == expense.projectId } ?: return@withTransaction
            val participant = loadParticipants().firstOrNull { it.id == expense.participantId } ?: return@withTransaction
            if (participant.id !in project.participantIds || expense.amount <= 0.0) return@withTransaction

            val expenses = loadExpenses() + expense.copy(updatedAt = Date())
            val projects = loadProjects().map { current ->
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
                projects = projects,
                participants = loadParticipants(),
                expenses = expenses,
                history = loadHistory()
            )
            appendHistoryInternal("Expense added: ${expense.title}", HistoryOperationType.CREATE)
        }
    }

    override suspend fun updateExpense(expense: Expense): Boolean {
        return database.withTransaction {
            if (expense.amount <= 0.0) return@withTransaction false
            val project = loadProjects().firstOrNull { it.id == expense.projectId } ?: return@withTransaction false
            val participant = loadParticipants().firstOrNull { it.id == expense.participantId } ?: return@withTransaction false
            if (participant.id !in project.participantIds) return@withTransaction false

            val current = loadExpenses().firstOrNull { it.id == expense.id } ?: return@withTransaction false
            val updated = expense.copy(
                recordVersion = maxOf(expense.recordVersion, current.recordVersion + 1),
                updatedAt = Date()
            )
            val expenses = loadExpenses().map { if (it.id == updated.id) updated else it }

            persistCoreState(
                projects = loadProjects(),
                participants = loadParticipants(),
                expenses = expenses,
                history = loadHistory()
            )
            appendHistoryInternal("Expense updated: ${updated.title}", HistoryOperationType.UPDATE)
            true
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
            val mergedParticipants = mergeRecords(loadParticipants(), payload.participants, { it.id }, { it.recordVersion }, { it.updatedAt })
            val mergedExpenses = mergeRecords(loadExpenses(), payload.expenses, { it.id }, { it.recordVersion }, { it.updatedAt })
            val mergedProjects = mergeProjects(loadProjects(), payload.projects, mergedExpenses)
            val mergedHistory = mergeRecords(loadHistory(), payload.history, { it.id }, { it.recordVersion }, { it.date })
            val mergedSyncLogs = mergeRecords(loadSyncLogs(), payload.syncLogs, { it.id }, { 1 }, { it.date })

            persistCoreState(
                projects = mergedProjects,
                participants = mergedParticipants,
                expenses = mergedExpenses,
                history = mergedHistory
            )
            dao.clearSyncLogs()
            dao.upsertSyncLogs(mergedSyncLogs.map { it.toEntity() })
        }
    }

    override suspend fun buildSyncPayload(): SyncPayload = exportPayload()

    private suspend fun appendHistoryInternal(description: String, operationType: HistoryOperationType) {
        val history = loadHistory() + ChangeHistoryEntry(
            operationType = operationType,
            actorName = "Android user",
            description = description,
            date = Date(),
            recordVersion = loadHistory().size + 1
        )
        dao.clearHistory()
        dao.upsertHistory(history.map { it.toEntity() })
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
        return merged.values.map { enrichProjectAssociations(it, mergedExpenses) }.sortedByDescending { it.updatedAt.time }
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
        status = runCatching { ProjectStatus.valueOf(status) }.getOrDefault(ProjectStatus.ACTIVE),
        recordVersion = recordVersion,
        updatedAt = Date(updatedAt),
        createdAt = Date(createdAt),
        participantIds = gson.fromJson(participantIdsJson, Array<String>::class.java)?.map(UUID::fromString) ?: emptyList(),
        expenseIds = gson.fromJson(expenseIdsJson, Array<String>::class.java)?.map(UUID::fromString) ?: emptyList()
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
        contributionAmount = contributionAmount,
        expenseAmount = expenseAmount,
        balanceAmount = balanceAmount,
        comment = comment,
        recordVersion = recordVersion,
        updatedAt = Date(updatedAt),
        createdAt = Date(createdAt)
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
        categoryId = UUID.fromString(categoryId),
        amount = amount,
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
        operationType = runCatching { HistoryOperationType.valueOf(operationType) }.getOrDefault(HistoryOperationType.UPDATE),
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
        result = runCatching { SyncResultType.valueOf(result) }.getOrDefault(SyncResultType.SUCCESS),
        changedRecordsCount = changedRecordsCount
    )

    private companion object {
        const val DB_NAME = "shared_finance.db"
    }
}
