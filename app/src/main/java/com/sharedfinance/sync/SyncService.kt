package com.sharedfinance.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sharedfinance.ble.BleManager
import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.SyncPayload
import com.sharedfinance.model.SyncResultType
import java.util.Date
import java.util.UUID

class SyncService(
    private val repository: SharedFinanceRepository,
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .create()
) {
    suspend fun buildLocalPayload(): SyncPayload = repository.buildSyncPayload()

    suspend fun syncNow(bleManager: BleManager, selectedProjectIds: Set<UUID>): String {
        val localPayload = filterPayloadByProjects(repository.buildSyncPayload(), selectedProjectIds)
        val remotePayload = exchangePayloadOverBle(bleManager, localPayload)
            ?: return "sync_state_transfer_failed"

        val filteredRemotePayload = filterPayloadByProjects(remotePayload, selectedProjectIds)
        repository.importPayload(filteredRemotePayload)
        repository.appendSyncLog(
            com.sharedfinance.model.SyncLogEntry(
                date = Date(),
                deviceName = bleManager.connectedDeviceName.value ?: "SharedFinance Peer",
                result = SyncResultType.SUCCESS,
                changedRecordsCount =
                    filteredRemotePayload.projects.size +
                        filteredRemotePayload.participants.size +
                        filteredRemotePayload.expenses.size +
                        filteredRemotePayload.history.size
            )
        )
        return "sync_state_completed"
    }

    private suspend fun exchangePayloadOverBle(bleManager: BleManager, localPayload: SyncPayload): SyncPayload? {
        val outbound = gson.toJson(localPayload).toByteArray(Charsets.UTF_8)
        val inbound = bleManager.transfer(outbound) ?: return null
        return runCatching {
            gson.fromJson(inbound.toString(Charsets.UTF_8), SyncPayload::class.java)
        }.getOrNull()
    }

    private fun filterPayloadByProjects(payload: SyncPayload, selectedProjectIds: Set<UUID>): SyncPayload {
        if (selectedProjectIds.isEmpty()) {
            return payload.copy(
                projects = emptyList(),
                participants = emptyList(),
                expenses = emptyList()
            )
        }

        val selectedProjects = payload.projects.filter { it.id in selectedProjectIds }
        val selectedProjectIdSet = selectedProjects.mapTo(mutableSetOf()) { it.id }
        val selectedExpenseIds = selectedProjects.flatMapTo(mutableSetOf()) { it.expenseIds }
        val selectedExpenses = payload.expenses.filter { expense ->
            expense.id in selectedExpenseIds || expense.projectId in selectedProjectIdSet
        }
        val selectedParticipantIds = buildSet {
            selectedProjects.forEach { addAll(it.participantIds) }
            selectedExpenses.forEach { add(it.participantId) }
        }
        val selectedParticipants = payload.participants.filter { it.id in selectedParticipantIds }

        return payload.copy(
            projects = selectedProjects,
            participants = selectedParticipants,
            expenses = selectedExpenses
        )
    }
}
