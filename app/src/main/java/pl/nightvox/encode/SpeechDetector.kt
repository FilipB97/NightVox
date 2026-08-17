package pl.nightvox.encode

import pl.nightvox.audio.vad.SileroVad
import pl.nightvox.audio.vad.VadAggregator
import pl.nightvox.audio.vad.VadChunker
import pl.nightvox.audio.vad.VadResult

/**
 * Drugi stopień detekcji: tani gate RMS wybudza VAD, VAD potwierdza mowę (§9 planu).
 *
 * Interfejs, a nie konkretna klasa, żeby [ClipWriter] dało się testować bez ONNX Runtime
 * i żeby awaria modelu oznaczała po prostu brak oceny, a nie brak nagrania.
 */
interface SpeechDetector : AutoCloseable {
    fun start()
    fun feed(samples: ShortArray, count: Int = samples.size)
    fun finish(): VadResult?
}

/**
 * Karmi Silero VAD tym samym PCM, które idzie do enkodera.
 *
 * Analiza kosztuje tylko w trakcie nagrywania klipu, czyli przez ułamek nocy — bramka RMS
 * chodzi dalej na każdej ramce, ale sieć uruchamia się wyłącznie na tym, co i tak
 * zapisujemy.
 */
class SileroSpeechDetector(
    private val vad: SileroVad,
    sampleRate: Int = 16_000,
    private val speechThreshold: Float = VadAggregator.DEFAULT_THRESHOLD,
) : SpeechDetector {

    private val chunker = VadChunker()
    private val chunkMs = chunker.chunkSamples * 1000L / sampleRate
    private var aggregator = VadAggregator(chunkMs, speechThreshold)

    override fun start() {
        chunker.reset()
        vad.reset()
        aggregator = VadAggregator(chunkMs, speechThreshold)
    }

    override fun feed(samples: ShortArray, count: Int) {
        chunker.feed(samples, count) { input ->
            aggregator.add(vad.probability(input))
        }
    }

    override fun finish(): VadResult? = if (aggregator.isEmpty) null else aggregator.result()

    override fun close() {
        vad.close()
    }
}
