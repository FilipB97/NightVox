package pl.nightvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nightvox_settings")

/** DataStore Preferences dla [NightVoxSettings]. */
class SettingsStore(private val context: Context) {

    val settings: Flow<NightVoxSettings> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { it.toSettings() }

    suspend fun current(): NightVoxSettings = settings.first()

    suspend fun update(transform: (NightVoxSettings) -> NightVoxSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[Keys.TRIGGER_DELTA_DB] = updated.triggerDeltaDb
            prefs[Keys.ATTACK_FRAMES] = updated.attackFrames
            prefs[Keys.PRE_ROLL_MS] = updated.preRollMs
            prefs[Keys.HANGOVER_MS] = updated.hangoverMs
            prefs[Keys.MERGE_GAP_MS] = updated.mergeGapMs
            prefs[Keys.MIN_VOICED_MS] = updated.minVoicedMs
            prefs[Keys.MAX_CLIP_MS] = updated.maxClipMs
            prefs[Keys.AUTO_STOP_ENABLED] = updated.autoStopHour != null
            prefs[Keys.AUTO_STOP_HOUR] = updated.autoStopHour ?: NightVoxSettings.DEFAULTS.autoStopHour ?: 9
            prefs[Keys.AUTO_STOP_MINUTE] = updated.autoStopMinute
            prefs[Keys.MAX_SESSION_HOURS] = updated.maxSessionHours
            prefs[Keys.RETENTION_DAYS] = updated.retentionDays
            prefs[Keys.KEEP_DISCARDED] = updated.keepDiscardedClips
            prefs[Keys.DISCARDED_RETENTION_DAYS] = updated.discardedRetentionDays
            prefs[Keys.KEEP_SCREEN_ON] = updated.keepScreenOn
            prefs[Keys.DEBUG_WAV_DUMP] = updated.debugWavDump
            prefs[Keys.DIAGNOSTICS] = updated.diagnosticsEnabled
        }
    }

    suspend fun resetToDefaults() {
        update { NightVoxSettings.DEFAULTS }
    }

    private fun Preferences.toSettings(): NightVoxSettings {
        val d = NightVoxSettings.DEFAULTS
        val autoStopEnabled = this[Keys.AUTO_STOP_ENABLED] ?: (d.autoStopHour != null)
        return NightVoxSettings(
            triggerDeltaDb = this[Keys.TRIGGER_DELTA_DB] ?: d.triggerDeltaDb,
            attackFrames = this[Keys.ATTACK_FRAMES] ?: d.attackFrames,
            preRollMs = this[Keys.PRE_ROLL_MS] ?: d.preRollMs,
            hangoverMs = this[Keys.HANGOVER_MS] ?: d.hangoverMs,
            mergeGapMs = this[Keys.MERGE_GAP_MS] ?: d.mergeGapMs,
            minVoicedMs = this[Keys.MIN_VOICED_MS] ?: d.minVoicedMs,
            maxClipMs = this[Keys.MAX_CLIP_MS] ?: d.maxClipMs,
            autoStopHour = if (autoStopEnabled) this[Keys.AUTO_STOP_HOUR] ?: d.autoStopHour else null,
            autoStopMinute = this[Keys.AUTO_STOP_MINUTE] ?: d.autoStopMinute,
            maxSessionHours = this[Keys.MAX_SESSION_HOURS] ?: d.maxSessionHours,
            retentionDays = this[Keys.RETENTION_DAYS] ?: d.retentionDays,
            keepDiscardedClips = this[Keys.KEEP_DISCARDED] ?: d.keepDiscardedClips,
            discardedRetentionDays = this[Keys.DISCARDED_RETENTION_DAYS] ?: d.discardedRetentionDays,
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: d.keepScreenOn,
            debugWavDump = this[Keys.DEBUG_WAV_DUMP] ?: d.debugWavDump,
            diagnosticsEnabled = this[Keys.DIAGNOSTICS] ?: d.diagnosticsEnabled,
        )
    }

    private object Keys {
        val TRIGGER_DELTA_DB = floatPreferencesKey("trigger_delta_db")
        val ATTACK_FRAMES = intPreferencesKey("attack_frames")
        val PRE_ROLL_MS = longPreferencesKey("pre_roll_ms")
        val HANGOVER_MS = longPreferencesKey("hangover_ms")
        val MERGE_GAP_MS = longPreferencesKey("merge_gap_ms")
        val MIN_VOICED_MS = longPreferencesKey("min_voiced_ms")
        val MAX_CLIP_MS = longPreferencesKey("max_clip_ms")
        val AUTO_STOP_ENABLED = booleanPreferencesKey("auto_stop_enabled")
        val AUTO_STOP_HOUR = intPreferencesKey("auto_stop_hour")
        val AUTO_STOP_MINUTE = intPreferencesKey("auto_stop_minute")
        val MAX_SESSION_HOURS = intPreferencesKey("max_session_hours")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val KEEP_DISCARDED = booleanPreferencesKey("keep_discarded_clips")
        val DISCARDED_RETENTION_DAYS = intPreferencesKey("discarded_retention_days")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DEBUG_WAV_DUMP = booleanPreferencesKey("debug_wav_dump")
        val DIAGNOSTICS = booleanPreferencesKey("diagnostics_enabled")
    }
}
