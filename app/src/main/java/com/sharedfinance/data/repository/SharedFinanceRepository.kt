package com.sharedfinance.data.repository

import com.sharedfinance.model.ChangeHistoryEntry
import com.sharedfinance.model.ConflictResolutionLogEntry
import com.sharedfinance.model.Expense
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
import com.sharedfinance.model.ResolvedSyncDecision
import com.sharedfinance.model.SyncConflict
import com.sharedfinance.model.SyncPayload
import com.sharedfinance.model.SyncLogEntry
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface SharedFinanceRepository {
    fun observeProjects(): Flow<List<Project>>
    fun observeParticipants(): Flow<List<Participant>>
    fun observeExpenses(): Flow<List<Expense>>
    fun observeHistory(): Flow<List<ChangeHistoryEntry>>
    fun observeSyncLogs(): Flow<List<SyncLogEntry>>
    fun observeConflictResolutionLogs(): Flow<List<ConflictResolutionLogEntry>>

    fun appendSyncLog(entry: SyncLogEntry)
    fun appendConflictResolutionLog(entry: ConflictResolutionLogEntry)

    suspend fun createProject(title: String, details: String)
    suspend fun updateProject(project: Project)
    suspend fun archiveProject(projectId: UUID)
    suspend fun deleteProject(projectId: UUID)

    suspend fun createParticipant(name: String, projectId: UUID? = null, contributionAmount: Double = 0.0)
    suspend fun deleteParticipant(participantId: UUID, projectId: UUID? = null)
    suspend fun createExpense(expense: Expense)
    suspend fun deleteExpense(expenseId: UUID)

    suspend fun exportPayload(): SyncPayload
    suspend fun importPayload(payload: SyncPayload)

    suspend fun buildSyncPayload(): SyncPayload
    suspend fun detectConflicts(remote: SyncPayload): List<SyncConflict>
    suspend fun applySync(remote: SyncPayload, decisions: List<ResolvedSyncDecision>)
}
