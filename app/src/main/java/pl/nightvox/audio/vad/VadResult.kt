package pl.nightvox.audio.vad

/** Ocena klipu przez VAD. */
data class VadResult(
    /** Najwyższe prawdopodobieństwo mowy w klipie — to jest zapisywane jako `Clip.vadScore`. */
    val maxProbability: Float,
    val meanProbability: Float,
    /** Ile audio model uznał za mowę. */
    val speechMs: Long,
    val analyzedMs: Long,
) {
    val speechRatio: Float get() = if (analyzedMs <= 0) 0f else speechMs.toFloat() / analyzedMs
}

/**
 * Składa prawdopodobieństwa z kolejnych chunków w ocenę całego klipu.
 *
 * Jako `vadScore` bierzemy **maksimum**, nie średnią: mamrotanie przez sen to zwykle dwa
 * słowa w kilkusekundowym klipie, więc średnia rozmyłaby je do zera i wyrzuciła dokładnie
 * to, po co ta apka istnieje.
 */
class VadAggregator(
    private val chunkMs: Long,
    private val speechThreshold: Float = DEFAULT_THRESHOLD,
) {
    private var chunks = 0
    private var speechChunks = 0
    private var sum = 0.0
    private var max = 0f

    val isEmpty: Boolean get() = chunks == 0

    fun add(probability: Float) {
        chunks++
        sum += probability
        if (probability > max) max = probability
        if (probability >= speechThreshold) speechChunks++
    }

    fun result(): VadResult = VadResult(
        maxProbability = max,
        meanProbability = if (chunks == 0) 0f else (sum / chunks).toFloat(),
        speechMs = speechChunks * chunkMs,
        analyzedMs = chunks * chunkMs,
    )

    fun reset() {
        chunks = 0
        speechChunks = 0
        sum = 0.0
        max = 0f
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
    }
}
