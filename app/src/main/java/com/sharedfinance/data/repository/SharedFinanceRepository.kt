package com.sharedfinance.data.repository

import com.sharedfinance.model.ChangeHistoryEntry
import com.sharedfinance.model.Expense
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
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

    fun appendSyncLog(entry: SyncLogEntry)

    suspend fun createProject(title: String, details: String)
    suspend fun updateProject(project: Project)
    suspend fun archiveProject(projectId: UUID)
    suspend fun deleteProject(projectId: UUID)

    suspend fun createParticipant(name: String, projectId: UUID? = null, contributionAmount: Double = 0.0)
    suspend fun updateParticipant(participant: Participant, projectId: UUID? = null)
    suspend fun deleteParticipant(participantId: UUID, projectId: UUID? = null): Boolean
    suspend fun createExpense(expense: Expense)
    suspend fun updateExpense(expense: Expense): Boolean
    suspend fun deleteExpense(expenseId: UUID)

    suspend fun exportPayload(): SyncPayload
    suspend fun importPayload(payload: SyncPayload)

    suspend fun buildSyncPayload(): SyncPayload
}
