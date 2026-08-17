package pl.nightvox.audio

/** Klip odtworzony z akcji bramki: PCM, który trafiłby do enkodera, plus statystyki. */
class RecordedClip(
    val startedAtMs: Long,
    val samples: ShortArray,
    val stats: ClipStats,
)

/**
 * Zbiera akcje [Gate] i składa z nich to, co realnie wylądowałoby w plikach.
 *
 * Bramka jest czystym Kotlinem, więc cały ten test działa na JVM w milisekundach —
 * bez emulatora, bez `MediaCodec`, bez mikrofonu.
 */
class GateHarness(
    val config: GateConfig = GateConfig(),
    private val startTimeMs: Long = 1_700_000_000_000L,
) {
    val gate = Gate(config)

    val clips = mutableListOf<RecordedClip>()
    val discarded = mutableListOf<Pair<DiscardReason, ClipStats>>()

    private var openStartedAtMs = 0L
    private var buffer: MutableList<ShortArray>? = null

    /** Karmi bramkę całym sygnałem, dzieląc go na ramki po [GateConfig.frameSamples]. */
    fun feed(signal: ShortArray) {
        var index = 0
        var frameIndex = frameCount
        while (index + config.frameSamples <= signal.size) {
            val chunk = signal.copyOfRange(index, index + config.frameSamples)
            // Znacznik czasu ramki = jej **koniec**, tak jak w AudioCapture (timestamp
            // brany po powrocie z read()). Test odwzorowuje to wiernie, inaczej pre-roll
            // wypadałby o ramkę obok.
            val timestamp = startTimeMs + (frameIndex + 1) * config.frameMs
            apply(gate.process(Frame(chunk, timestamp)))
            index += config.frameSamples
            frameIndex++
        }
        frameCount = frameIndex
    }

    fun finish() {
        apply(gate.flush())
    }

    var frameCount = 0
        private set

    /** Przelicza znacznik czasu klipu na offset w oryginalnym sygnale. */
    fun sampleOffsetOf(clip: RecordedClip): Int =
        ((clip.startedAtMs - startTimeMs) * config.sampleRate / 1000L).toInt()

    private fun apply(actions: List<GateAction>) {
        for (action in actions) {
            when (action) {
                is GateAction.OpenClip -> {
                    openStartedAtMs = action.startedAtMs
                    buffer = mutableListOf()
                }
                is GateAction.Write -> buffer?.add(action.samples)
                is GateAction.CloseClip -> {
                    clips += RecordedClip(openStartedAtMs, flatten(), action.stats)
                    buffer = null
                }
                is GateAction.DiscardClip -> {
                    discarded += action.reason to action.stats
                    buffer = null
                }
            }
        }
    }

    private fun flatten(): ShortArray {
        val chunks = buffer ?: return ShortArray(0)
        val total = chunks.sumOf { it.size }
        val out = ShortArray(total)
        var at = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, out, at, chunk.size)
            at += chunk.size
        }
        return out
    }
}
