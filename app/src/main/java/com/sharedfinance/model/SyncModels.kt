package com.sharedfinance.model

import java.util.Date
import java.util.UUID

data class SyncPayload(
    val databaseVersion: String = "android-kotlin-v1",
    val projects: List<Project>,
    val participants: List<Participant>,
    val expenses: List<Expense>,
    val history: List<ChangeHistoryEntry>,
    val syncLogs: List<SyncLogEntry> = emptyList()
)

data class SyncConflict(
    val id: UUID = UUID.randomUUID(),
    val entityName: String,
    val entityId: UUID,
    val localValue: String,
    val remoteValue: String,
    val localRecordVersion: Int,
    val remoteRecordVersion: Int,
    val localUpdatedAt: Date,
    val remoteUpdatedAt: Date
)

data class ResolvedSyncDecision(
    val conflictId: UUID,
    val acceptRemote: Boolean,
    val source: ConflictDecisionSource
)

data class SyncDelta(
    val upsertProjects: List<Project> = emptyList(),
    val upsertParticipants: List<Participant> = emptyList(),
    val upsertExpenses: List<Expense> = emptyList(),
    val upsertHistory: List<ChangeHistoryEntry> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList()
)
