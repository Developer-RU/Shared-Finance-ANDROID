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
