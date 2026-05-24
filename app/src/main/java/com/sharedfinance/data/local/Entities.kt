package com.sharedfinance.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val details: String,
    val status: String,
    val recordVersion: Int,
    val updatedAt: Long,
    val createdAt: Long,
    val participantIdsJson: String,
    val expenseIdsJson: String
)

@Entity(tableName = "participants")
data class ParticipantEntity(
    @PrimaryKey val id: String,
    val name: String,
    val contributionAmount: Double,
    val expenseAmount: Double,
    val balanceAmount: Double,
    val comment: String,
    val recordVersion: Int,
    val updatedAt: Long,
    val createdAt: Long
)

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val participantId: String,
    val categoryId: String,
    val amount: Double,
    val title: String,
    val comment: String,
    val date: Long,
    val recordVersion: Int,
    val updatedAt: Long
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val id: String,
    val operationType: String,
    val actorName: String,
    val date: Long,
    val description: String,
    val recordVersion: Int
)

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey val id: String,
    val date: Long,
    val deviceName: String,
    val result: String,
    val changedRecordsCount: Int
)

@Entity(tableName = "conflict_resolution_logs")
data class ConflictResolutionLogEntity(
    @PrimaryKey val id: String,
    val date: Long,
    val entityName: String,
    val entityId: String,
    val localValue: String,
    val remoteValue: String,
    val decision: String,
    val decisionSource: String,
    val isApplied: Boolean
)
