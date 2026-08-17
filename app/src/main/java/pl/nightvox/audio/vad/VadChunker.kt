package pl.nightvox.audio.vad

/**
 * Tnie strumień PCM16 na wejścia dla Silero VAD.
 *
 * Model v5 **nie** przyjmuje samego chunka 512 próbek: oczekuje `kontekst (64) + chunk (512)`
 * = 576 próbek, gdzie kontekst to ogon poprzedniego chunka. Wołany bez kontekstu zwraca
 * ~0.001 na wszystko — ciszę, mowę, cokolwiek — czyli psuje się cicho i nic nigdy nie
 * zostaje uznane za mowę. Dlatego to jest osobna, czysta klasa z własnymi testami, a nie
 * kilka linijek schowanych w wrapperze ONNX.
 *
 * Wyjście jest znormalizowane do zakresu [-1, 1], którego oczekuje model.
 */
class VadChunker(
    val chunkSamples: Int = DEFAULT_CHUNK_SAMPLES,
    val contextSamples: Int = DEFAULT_CONTEXT_SAMPLES,
) {
    init {
        require(chunkSamples > 0) { "chunkSamples musi być > 0" }
        require(contextSamples in 0..chunkSamples) { "kontekst musi mieścić się w chunku" }
    }

    /** Długość tablicy podawanej modelowi. */
    val inputSamples: Int get() = contextSamples + chunkSamples

    private val context = FloatArray(contextSamples)
    private val pending = FloatArray(chunkSamples)
    private var pendingCount = 0

    /**
     * Karmi chunker kolejnym kawałkiem PCM. Dla każdego pełnego chunka woła [onInput]
     * z tablicą o długości [inputSamples]. Tablica jest **współdzielona** między
     * wywołaniami — odbiorca ma ją zużyć od razu, nie odkładać.
     */
    inline fun feed(samples: ShortArray, count: Int = samples.size, onInput: (FloatArray) -> Unit) {
        var offset = 0
        while (offset < count) {
            val taken = takeInto(samples, offset, count)
            offset += taken
            if (isChunkReady()) onInput(buildInput())
        }
    }

    /** Nowy klip = nowy strumień: kontekst z poprzedniego nagrania nie ma tu nic do rzeczy. */
    fun reset() {
        context.fill(0f)
        pendingCount = 0
    }

    // --- wnętrze; publiczne, bo `feed` jest inline ---

    @PublishedApi
    internal fun takeInto(samples: ShortArray, offset: Int, count: Int): Int {
        val taken = minOf(chunkSamples - pendingCount, count - offset)
        for (i in 0 until taken) {
            pending[pendingCount + i] = samples[offset + i] / SCALE
        }
        pendingCount += taken
        return taken
    }

    @PublishedApi
    internal fun isChunkReady(): Boolean = pendingCount == chunkSamples

    @PublishedApi
    internal fun buildInput(): FloatArray {
        val input = FloatArray(inputSamples)
        System.arraycopy(context, 0, input, 0, contextSamples)
        System.arraycopy(pending, 0, input, contextSamples, chunkSamples)
        // Kontekst następnego wywołania to ogon bieżącego chunka.
        System.arraycopy(pending, chunkSamples - contextSamples, context, 0, contextSamples)
        pendingCount = 0
        return input
    }

    companion object {
        /** 512 próbek @ 16 kHz = 32 ms, zgodnie z wymaganiem modelu. */
        const val DEFAULT_CHUNK_SAMPLES = 512
        const val DEFAULT_CONTEXT_SAMPLES = 64
        private const val SCALE = 32768f
    }
}
