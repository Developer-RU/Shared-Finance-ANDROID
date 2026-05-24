package com.sharedfinance.viewmodel

import android.content.Context
import com.google.gson.GsonBuilder
import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.SyncPayload
import java.security.MessageDigest
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
    val appLockEnabled: Boolean = false,
    val faceIdEnabled: Boolean = false,
    val pinEnabled: Boolean = false,
    val patternEnabled: Boolean = false,
    val backupStatusKey: String = ""
)

class SettingsViewModel(
    context: Context,
    private val repository: SharedFinanceRepository
) {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        _state.value = _state.value.copy(
            appLockEnabled = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false),
            faceIdEnabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false),
            pinEnabled = prefs.getString(KEY_PIN_HASH, null)?.isNotBlank() == true,
            patternEnabled = prefs.getString(KEY_PATTERN_HASH, null)?.isNotBlank() == true
        )
    }

    fun setTheme(theme: AppThemeOption) {
        _state.value = _state.value.copy(selectedTheme = theme)
    }

    fun setLanguage(code: String) {
        _state.value = _state.value.copy(languageCode = code)
    }

    fun setAppLockEnabled(enabled: Boolean) {
        val shouldEnableBiometricByDefault = enabled && !_state.value.faceIdEnabled && !_state.value.pinEnabled && !_state.value.patternEnabled
        val updated = _state.value.copy(
            appLockEnabled = enabled,
            faceIdEnabled = if (shouldEnableBiometricByDefault) true else _state.value.faceIdEnabled
        )
        _state.value = updated
        prefs.edit()
            .putBoolean(KEY_APP_LOCK_ENABLED, updated.appLockEnabled)
            .putBoolean(KEY_BIOMETRIC_ENABLED, updated.faceIdEnabled)
            .apply()
    }

    fun setFaceIdEnabled(enabled: Boolean) {
        val updated = _state.value.copy(faceIdEnabled = enabled)
        _state.value = updated
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
            .putBoolean(KEY_APP_LOCK_ENABLED, shouldKeepAppLockEnabled(updated))
            .apply()
        _state.value = updated.copy(appLockEnabled = shouldKeepAppLockEnabled(updated))
    }

    fun setPinCode(pin: String) {
        val hash = hashPin(pin)
        prefs.edit().putString(KEY_PIN_HASH, hash).apply()
        val updated = _state.value.copy(appLockEnabled = true, pinEnabled = true)
        _state.value = updated
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, true).apply()
    }

    fun clearPinCode() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
        val updated = _state.value.copy(pinEnabled = false)
        _state.value = updated.copy(appLockEnabled = shouldKeepAppLockEnabled(updated))
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, shouldKeepAppLockEnabled(updated)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return storedHash == hashPin(pin)
    }

    fun setPatternCode(pattern: List<Int>) {
        val normalizedPattern = normalizePattern(pattern)
        val hash = hashPattern(normalizedPattern)
        prefs.edit().putString(KEY_PATTERN_HASH, hash).apply()
        val updated = _state.value.copy(appLockEnabled = true, patternEnabled = true)
        _state.value = updated
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, true).apply()
    }

    fun clearPatternCode() {
        prefs.edit().remove(KEY_PATTERN_HASH).apply()
        val updated = _state.value.copy(patternEnabled = false)
        _state.value = updated.copy(appLockEnabled = shouldKeepAppLockEnabled(updated))
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, shouldKeepAppLockEnabled(updated)).apply()
    }

    fun verifyPattern(pattern: List<Int>): Boolean {
        val storedHash = prefs.getString(KEY_PATTERN_HASH, null) ?: return false
        return storedHash == hashPattern(normalizePattern(pattern))
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

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun hashPattern(pattern: List<Int>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val serialized = pattern.joinToString(separator = "-")
        val bytes = digest.digest(serialized.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun normalizePattern(pattern: List<Int>): List<Int> {
        return pattern.filter { it in 0..8 }.distinct()
    }

    private fun shouldKeepAppLockEnabled(state: SettingsUiState): Boolean {
        if (!state.appLockEnabled) return false
        return state.faceIdEnabled || state.pinEnabled || state.patternEnabled
    }

    private companion object {
        const val PREFS_NAME = "shared_finance_settings"
        const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PATTERN_HASH = "pattern_hash"
    }
}
