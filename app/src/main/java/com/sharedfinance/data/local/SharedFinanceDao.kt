package com.sharedfinance.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedFinanceDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects")
    suspend fun getProjectsSnapshot(): List<ProjectEntity>

    @Query("SELECT * FROM participants ORDER BY updatedAt DESC")
    fun observeParticipants(): Flow<List<ParticipantEntity>>

    @Query("SELECT * FROM participants")
    suspend fun getParticipantsSnapshot(): List<ParticipantEntity>

    @Query("SELECT * FROM expenses ORDER BY updatedAt DESC")
    fun observeExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses")
    suspend fun getExpensesSnapshot(): List<ExpenseEntity>

    @Query("SELECT * FROM history ORDER BY date DESC")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history")
    suspend fun getHistorySnapshot(): List<HistoryEntity>

    @Query("SELECT * FROM sync_logs ORDER BY date DESC")
    fun observeSyncLogs(): Flow<List<SyncLogEntity>>

    @Query("SELECT * FROM sync_logs")
    suspend fun getSyncLogsSnapshot(): List<SyncLogEntity>

    @Query("DELETE FROM projects")
    suspend fun clearProjects()

    @Query("DELETE FROM participants")
    suspend fun clearParticipants()

    @Query("DELETE FROM expenses")
    suspend fun clearExpenses()

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("DELETE FROM sync_logs")
    suspend fun clearSyncLogs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjects(items: List<ProjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipants(items: List<ParticipantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExpenses(items: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(items: List<HistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncLogs(items: List<SyncLogEntity>)
}
