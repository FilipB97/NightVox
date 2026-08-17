package pl.nightvox.audio

/**
 * Parametry bramkowania (§5 planu). Czysty Kotlin — te same wartości siedzą w DataStore
 * i lądują w `Session.settingsSnapshot`, żeby po nocy było wiadomo, przy jakich progach
 * powstały klipy.
 */
data class GateConfig(
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val frameSamples: Int = DEFAULT_FRAME_SAMPLES,
    val warmupMs: Long = 10_000,
    val triggerDeltaDb: Float = 12f,
    val attackFrames: Int = 3,
    val preRollMs: Long = 3_000,
    val hangoverMs: Long = 4_000,
    val mergeGapMs: Long = 2_000,
    val minVoicedMs: Long = 400,
    val maxClipMs: Long = 120_000,
    /**
     * Histereza: zejście poniżej progu liczy się dopiero [releaseHysteresisDb] niżej niż
     * wyzwolenie. Bez tego ramka drgająca wokół progu przełącza RECORDING/HANGOVER 50×/s.
     */
    val releaseHysteresisDb: Float = 3f,
) {
    val frameMs: Long get() = frameSamples.toLong() * 1000L / sampleRate
    val warmupFrames: Int get() = msToFrames(warmupMs)
    val preRollSamples: Int get() = (preRollMs * sampleRate / 1000L).toInt()
    val hangoverFrames: Int get() = msToFrames(hangoverMs)
    val mergeGapFrames: Int get() = msToFrames(mergeGapMs)

    fun msToFrames(ms: Long): Int = if (frameMs <= 0) 0 else (ms / frameMs).toInt()

    init {
        require(sampleRate > 0) { "sampleRate musi być > 0" }
        require(frameSamples > 0) { "frameSamples musi być > 0" }
        require(attackFrames >= 1) { "attackFrames musi być >= 1" }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000
        /** 20 ms @ 16 kHz. */
        const val DEFAULT_FRAME_SAMPLES = 320
    }
}
