package com.sharedfinance.model

import java.util.Date
import java.util.UUID

data class Participant(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val createdAt: Date = Date(),
    val contributionAmount: Double = 0.0,
    val expenseAmount: Double = 0.0,
    val balanceAmount: Double = 0.0,
    val comment: String = "",
    val recordVersion: Int = 1,
    val updatedAt: Date = Date()
)

data class Project(
    val id: UUID = UUID.randomUUID(),
    val title: String,
    val details: String,
    val createdAt: Date = Date(),
    val participantIds: List<UUID> = emptyList(),
    val expenseIds: List<UUID> = emptyList(),
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val recordVersion: Int = 1,
    val updatedAt: Date = Date()
)

enum class ProjectStatus {
    ACTIVE,
    ARCHIVED
}

data class Expense(
    val id: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val participantId: UUID,
    val amount: Double,
    val categoryId: UUID,
    val title: String,
    val comment: String = "",
    val date: Date = Date(),
    val recordVersion: Int = 1,
    val updatedAt: Date = Date()
)

data class Category(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val icon: String
)

data class ChangeHistoryEntry(
    val id: UUID = UUID.randomUUID(),
    val operationType: HistoryOperationType,
    val actorName: String,
    val date: Date = Date(),
    val description: String,
    val recordVersion: Int
)

enum class HistoryOperationType {
    CREATE,
    UPDATE,
    DELETE,
    SYNC
}

data class SyncLogEntry(
    val id: UUID = UUID.randomUUID(),
    val date: Date = Date(),
    val deviceName: String,
    val result: SyncResultType,
    val changedRecordsCount: Int
)

enum class SyncResultType {
    SUCCESS,
    CONFLICT,
    FAILED
}
