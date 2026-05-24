package com.sharedfinance.sync

import com.sharedfinance.model.Expense
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
import com.sharedfinance.model.SyncConflict
import com.sharedfinance.model.SyncDelta
import com.sharedfinance.model.SyncPayload

class SyncEngine {
    fun calculateDelta(local: SyncPayload, remote: SyncPayload): SyncDelta {
        val localProjects = local.projects.associateBy { it.id }
        val localParticipants = local.participants.associateBy { it.id }
        val localExpenses = local.expenses.associateBy { it.id }

        val conflicts = mutableListOf<SyncConflict>()

        val upsertProjects = remote.projects.filter { remoteProject ->
            val localProject = localProjects[remoteProject.id]
            if (localProject == null) {
                return@filter true
            }
            if (remoteProject.recordVersion == localProject.recordVersion) {
                return@filter false
            }
            if (remoteProject.updatedAt != localProject.updatedAt) {
                conflicts += SyncConflict(
                    entityName = "Project",
                    entityId = remoteProject.id,
                    localValue = "${localProject.title}:${localProject.details}",
                    remoteValue = "${remoteProject.title}:${remoteProject.details}",
                    localRecordVersion = localProject.recordVersion,
                    remoteRecordVersion = remoteProject.recordVersion,
                    localUpdatedAt = localProject.updatedAt,
                    remoteUpdatedAt = remoteProject.updatedAt
                )
                return@filter false
            }
            remoteProject.recordVersion > localProject.recordVersion
        }

        val upsertParticipants = findUpserts(
            localById = localParticipants,
            remoteItems = remote.participants,
            versionSelector = { it.recordVersion }
        )
        val upsertExpenses = findUpserts(
            localById = localExpenses,
            remoteItems = remote.expenses,
            versionSelector = { it.recordVersion }
        )

        return SyncDelta(
            upsertProjects = upsertProjects,
            upsertParticipants = upsertParticipants,
            upsertExpenses = upsertExpenses,
            upsertHistory = remote.history,
            conflicts = conflicts
        )
    }

    private fun <T, K> findUpserts(
        localById: Map<K, T>,
        remoteItems: List<T>,
        versionSelector: (T) -> Int,
        idSelector: (T) -> K = { item ->
            @Suppress("UNCHECKED_CAST")
            when (item) {
                is Project -> item.id as K
                is Participant -> item.id as K
                is Expense -> item.id as K
                else -> throw IllegalArgumentException("Unsupported sync type")
            }
        }
    ): List<T> {
        return remoteItems.filter { remoteItem ->
            val local = localById[idSelector(remoteItem)]
            local == null || versionSelector(remoteItem) > versionSelector(local)
        }
    }
}
