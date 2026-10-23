package com.phoneagent.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phoneagent.perception.PerceptionMode
import com.phoneagent.util.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("phoneagent_settings")

/**
 * Reads and writes [AppSettings].
 *
 * The keys are strings rather than an enum ordinal, so reordering an enum in a later version
 * cannot silently reinterpret an existing file. A value that fails to parse falls back to its
 * default rather than throwing, because a corrupt settings file must never stop the app from
 * starting: the person would have no way to fix it.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun reset()
}

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.settingsStore.data
        // A read error is not a reason to be unable to open the app. The defaults are a
        // perfectly usable configuration.
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it.toSettings() }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val updated = transform(prefs.toSettings()).sanitized()
            prefs[Keys.MAX_STEPS] = updated.maxSteps
            prefs[Keys.MAX_MINUTES] = updated.maxTaskMinutes
            prefs[Keys.PERCEPTION] = updated.perceptionMode.name
            prefs[Keys.SPEECH] = updated.speechEnabled
            prefs[Keys.LANGUAGE] = updated.languageTag
            prefs[Keys.DENIED] = updated.deniedPackages.joinToString("\n")
            prefs[Keys.LOG_LEVEL] = updated.logLevel.name
            prefs[Keys.DRAFT_SKILLS] = updated.draftSkills
            prefs[Keys.CONFIRM_RISKY] = updated.confirmRiskyActions
            prefs[Keys.CHECK_UPDATES] = updated.checkForUpdates
        }
    }

    override suspend fun reset() {
        context.settingsStore.edit { it.clear() }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            maxSteps = this[Keys.MAX_STEPS] ?: defaults.maxSteps,
            maxTaskMinutes = this[Keys.MAX_MINUTES] ?: defaults.maxTaskMinutes,
            perceptionMode = this[Keys.PERCEPTION]
                ?.let { name -> PerceptionMode.entries.firstOrNull { it.name == name } }
                ?: defaults.perceptionMode,
            speechEnabled = this[Keys.SPEECH] ?: defaults.speechEnabled,
            languageTag = this[Keys.LANGUAGE] ?: defaults.languageTag,
            deniedPackages = this[Keys.DENIED]
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?: defaults.deniedPackages,
            logLevel = this[Keys.LOG_LEVEL]
                ?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
                ?: defaults.logLevel,
            draftSkills = this[Keys.DRAFT_SKILLS] ?: defaults.draftSkills,
            confirmRiskyActions = this[Keys.CONFIRM_RISKY] ?: defaults.confirmRiskyActions,
            checkForUpdates = this[Keys.CHECK_UPDATES] ?: defaults.checkForUpdates,
        ).sanitized()
    }

    private object Keys {
        val MAX_STEPS = intPreferencesKey("max_steps")
        val MAX_MINUTES = intPreferencesKey("max_task_minutes")
        val PERCEPTION = stringPreferencesKey("perception_mode")
        val SPEECH = booleanPreferencesKey("speech_enabled")
        val LANGUAGE = stringPreferencesKey("language_tag")
        val DENIED = stringPreferencesKey("denied_packages")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val DRAFT_SKILLS = booleanPreferencesKey("draft_skills")
        val CONFIRM_RISKY = booleanPreferencesKey("confirm_risky_actions")
        val CHECK_UPDATES = booleanPreferencesKey("check_for_updates")
    }
}

/** Settings held in memory. Used in tests and by previews. */
class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {

    private val state = kotlinx.coroutines.flow.MutableStateFlow(initial.sanitized())

    override val settings: Flow<AppSettings> = state

    override suspend fun current(): AppSettings = state.value

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value).sanitized()
    }

    override suspend fun reset() {
        state.value = AppSettings()
    }
}
