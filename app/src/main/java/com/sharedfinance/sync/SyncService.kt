package com.sharedfinance.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sharedfinance.ble.BleManager
import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.ConflictDecisionSource
import com.sharedfinance.model.Expense
import com.sharedfinance.model.Participant
import com.sharedfinance.model.Project
import com.sharedfinance.model.ResolvedSyncDecision
import com.sharedfinance.model.SyncConflict
import com.sharedfinance.model.SyncPayload
import java.util.UUID

class SyncService(
    private val repository: SharedFinanceRepository,
    private val syncEngine: SyncEngine,
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .create()
) {
    suspend fun buildLocalPayload(): SyncPayload {
        return repository.buildSyncPayload()
    }

    suspend fun exchangePayloadOverBle(bleManager: BleManager, localPayload: SyncPayload): SyncPayload? {
        val outbound = gson.toJson(localPayload).toByteArray(Charsets.UTF_8)
        val inbound = bleManager.transfer(outbound) ?: return null
        return runCatching {
            gson.fromJson(inbound.toString(Charsets.UTF_8), SyncPayload::class.java)
        }.getOrNull()
    }

    suspend fun previewConflicts(localPayload: SyncPayload, remotePayload: SyncPayload): List<SyncConflict> {
        return syncEngine.calculateDelta(localPayload, remotePayload).conflicts
    }

    suspend fun apply(
        localPayload: SyncPayload,
        remotePayload: SyncPayload,
        decisions: Map<UUID, Boolean>,
        defaultSource: ConflictDecisionSource = ConflictDecisionSource.MANUAL
    ) {
        val conflicts = previewConflicts(localPayload, remotePayload)
        val resolvedDecisions = conflicts.map { conflict ->
            ResolvedSyncDecision(
                conflictId = conflict.id,
                acceptRemote = decisions[conflict.id] ?: false,
                source = defaultSource
            )
        }
        repository.applySync(remotePayload, resolvedDecisions)
    }
}
