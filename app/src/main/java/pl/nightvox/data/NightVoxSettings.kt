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
    /**
     * Bezwzględna podłoga progu wyzwolenia w dBFS — próg względny nie zejdzie niżej.
     * Chroni przed tym, że w bardzo cichym pokoju „tło + delta" wypada na poziomie, na którym
     * nie ma już nic słyszalnego.
     */
    val minTriggerDb: Float = -50f,
    val attackFrames: Int = 3,
    val preRollMs: Long = 3_000,
    val hangoverMs: Long = 4_000,
    val mergeGapMs: Long = 2_000,
    val minVoicedMs: Long = 400,
    val maxClipMs: Long = 120_000,
    /**
     * Filtr mowy: bramka RMS przepuszcza wszystko głośniejsze od tła, czyli nocą także
     * oddech i chrapanie. Analiza widmowa ([pl.nightvox.audio.speech.SpeechAnalyzer])
     * odsiewa je od mowy. Nic nie kasuje — klipy pod progiem lądują w koszu.
     */
    val speechFilterEnabled: Boolean = true,
    /** Próg oceny mowy 0..1. Wyżej = mniej klipów, większe ryzyko wycięcia cichego mamrotania. */
    val speechFilterThreshold: Float = 0.50f,
    /** Godzina auto-stopu (0..23), `null` = wyłączony. */
    val autoStopHour: Int? = 9,
    val autoStopMinute: Int = 0,
    val maxSessionHours: Int = 10,
    val retentionDays: Int = 30,
    /**
     * Zachowuj nagrania odrzucone przez bramkę zamiast je kasować. Domyślnie włączone:
     * przy nieustrojonych progach ważniejsze jest zobaczyć, co filtr wyrzuca, niż oszczędzić
     * kilka megabajtów.
     */
    val keepDiscardedClips: Boolean = true,
    /** Kosz ma służyć do strojenia, nie rosnąć bez końca. */
    val discardedRetentionDays: Int = 7,
    val keepScreenOn: Boolean = false,
    /** Dump całej sesji do WAV — do debugowania progów, zjada ok. 115 MB/h. */
    val debugWavDump: Boolean = false,
    val diagnosticsEnabled: Boolean = true,
    /** Czy pierwsze uruchomienie zostało przeprowadzone do końca. */
    val onboardingCompleted: Boolean = false,
) {
    fun toGateConfig(): GateConfig = GateConfig(
        triggerDeltaDb = triggerDeltaDb,
        minTriggerDb = minTriggerDb,
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
        val MIN_TRIGGER_RANGE_DB = -70f..-30f
        val ATTACK_FRAMES_RANGE = 1..10
        val PRE_ROLL_RANGE_MS = 1_000L..6_000L
        val HANGOVER_RANGE_MS = 1_000L..10_000L
        val MERGE_GAP_RANGE_MS = 0L..5_000L
        val MIN_VOICED_RANGE_MS = 100L..2_000L
        val MAX_CLIP_RANGE_MS = 30_000L..600_000L
        val SPEECH_THRESHOLD_RANGE = 0.15f..0.80f
        val RETENTION_RANGE_DAYS = 1..365
        val DISCARDED_RETENTION_RANGE_DAYS = 1..30

        /** Wartość `retentionDays` oznaczająca „nigdy nie kasuj”. */
        const val RETENTION_NEVER = 0
    }
}
