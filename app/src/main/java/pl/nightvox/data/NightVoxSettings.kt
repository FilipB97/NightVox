package pl.nightvox.data

import kotlinx.serialization.Serializable
import pl.nightvox.audio.GateConfig

/**
 * Wszystkie parametry z §5 planu w jednym miejscu. Serializowalne, bo migawka trafia do
 * `Session.settingsSnapshot` — po nocy trzeba wiedzieć, przy jakich progach powstały klipy.
 */
@Serializable
data class NightVoxSettings(
    val triggerDeltaDb: Float = 12f,
    val attackFrames: Int = 3,
    val preRollMs: Long = 3_000,
    val hangoverMs: Long = 4_000,
    val mergeGapMs: Long = 2_000,
    val minVoicedMs: Long = 400,
    val maxClipMs: Long = 120_000,
    /** Godzina auto-stopu (0..23), `null` = wyłączony. */
    val autoStopHour: Int? = 9,
    val autoStopMinute: Int = 0,
    val maxSessionHours: Int = 10,
    val retentionDays: Int = 30,
    val keepScreenOn: Boolean = false,
    /** Dump całej sesji do WAV — do debugowania progów, zjada ok. 115 MB/h. */
    val debugWavDump: Boolean = false,
    val diagnosticsEnabled: Boolean = true,
) {
    fun toGateConfig(): GateConfig = GateConfig(
        triggerDeltaDb = triggerDeltaDb,
        attackFrames = attackFrames,
        preRollMs = preRollMs,
        hangoverMs = hangoverMs,
        mergeGapMs = mergeGapMs,
        minVoicedMs = minVoicedMs,
        maxClipMs = maxClipMs,
    )

    companion object {
        val DEFAULTS = NightVoxSettings()

        val TRIGGER_DELTA_RANGE = 6f..24f
        val ATTACK_FRAMES_RANGE = 1..10
        val PRE_ROLL_RANGE_MS = 1_000L..6_000L
        val HANGOVER_RANGE_MS = 1_000L..10_000L
        val MERGE_GAP_RANGE_MS = 0L..5_000L
        val MIN_VOICED_RANGE_MS = 100L..2_000L
        val MAX_CLIP_RANGE_MS = 30_000L..600_000L
        val RETENTION_RANGE_DAYS = 1..365

        /** Wartość `retentionDays` oznaczająca „nigdy nie kasuj”. */
        const val RETENTION_NEVER = 0
    }
}
