package com.sharedfinance.viewmodel

import com.google.gson.GsonBuilder
import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.SyncPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeOption {
    SYSTEM,
    LIGHT,
    DARK
}

data class SettingsUiState(
    val selectedTheme: AppThemeOption = AppThemeOption.SYSTEM,
    val languageCode: String = "ru",
    val faceIdEnabled: Boolean = false,
    val backupStatusKey: String = ""
)

class SettingsViewModel(
    private val repository: SharedFinanceRepository
) {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun setTheme(theme: AppThemeOption) {
        _state.value = _state.value.copy(selectedTheme = theme)
    }

    fun setLanguage(code: String) {
        _state.value = _state.value.copy(languageCode = code)
    }

    fun setFaceIdEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(faceIdEnabled = enabled)
    }

    suspend fun exportBackupData(): ByteArray? {
        return runCatching {
            val payload = repository.exportPayload()
            gson.toJson(payload).toByteArray()
        }.getOrNull()
    }

    suspend fun importBackupFromJson(bytes: ByteArray): Boolean {
        return runCatching {
            val payload = gson.fromJson(String(bytes), SyncPayload::class.java)
            repository.importPayload(payload)
            _state.value = _state.value.copy(
                backupStatusKey = "settings_import_done"
            )
            true
        }.getOrElse {
            _state.value = _state.value.copy(backupStatusKey = "settings_import_error")
            false
        }
    }

    fun markExportDone() {
        _state.value = _state.value.copy(backupStatusKey = "settings_export_done")
    }

    fun markExportError() {
        _state.value = _state.value.copy(backupStatusKey = "settings_export_error")
    }

    fun markImportError() {
        _state.value = _state.value.copy(backupStatusKey = "settings_import_error")
    }
}
