package pl.nightvox.encode

import pl.nightvox.audio.speech.SpeechAnalyzer
import pl.nightvox.audio.speech.SpeechScore

/**
 * Drugi stopień detekcji: tani gate RMS wybudza analizę, analiza mówi, czy to była mowa
 * (§9 planu).
 *
 * Interfejs, a nie konkretna klasa, żeby [ClipWriter] dało się testować bez analizatora
 * i żeby ewentualna awaria detektora oznaczała brak oceny, a nie brak nagrania.
 */
interface SpeechDetector : AutoCloseable {
    /** [levelFloorDb] to próg wyzwolenia klipu — poniżej niego nie ma czego analizować. */
    fun start(levelFloorDb: Float)
    fun feed(samples: ShortArray, count: Int = samples.size)
    fun finish(): SpeechScore?
    override fun close() = Unit
}

/** Detektor oparty na [SpeechAnalyzer] — czysty Kotlin, zero kodu natywnego. */
class HeuristicSpeechDetector(
    private val analyzer: SpeechAnalyzer,
) : SpeechDetector {

    override fun start(levelFloorDb: Float) = analyzer.reset(levelFloorDb)

    override fun feed(samples: ShortArray, count: Int) = analyzer.feed(samples, count)

    override fun finish(): SpeechScore? = analyzer.finish()
}
