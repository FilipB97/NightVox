package pl.nightvox.audio.speech

import kotlin.math.sqrt

/** Wynik szukania okresowości w oknie. [hz] = 0, gdy nic nie znaleziono. */
data class PitchEstimate(val hz: Float, val strength: Float) {
    companion object {
        val NONE = PitchEstimate(0f, 0f)
    }
}

/**
 * Znormalizowana autokorelacja — odpowiada na pytanie „czy ten dźwięk ma ton krtaniowy,
 * a jeśli tak, to jak wysoki”.
 *
 * To jest jedyna cecha, która **rozdziela chrapanie od mowy**, a nie tylko wykrywa, że coś
 * jest okresowe: chrapanie ma ton podstawowy zwykle 25–90 Hz (drgają tkanki miękkie),
 * mowa dorosłego 85–255 Hz (drgają fałdy głosowe). Dlatego szukamy w szerokim zakresie
 * [MIN_HZ]..[MAX_HZ] i zwracamy **częstotliwość**, a nie samo „okresowe / nieokresowe” —
 * decyzję, co z tym zrobić, podejmuje [SpeechScorer].
 *
 * Szczytu szukamy **za pierwszym przejściem autokorelacji przez zero**, nie od razu od
 * najmniejszego dozwolonego lagu. Bez tego dla szumu dolnoprzepustowego — a takim jest
 * oddech po torze mikrofon + AAC — korelacja przy małych lagach jest wysoka po prostu
 * dlatego, że sąsiednie próbki są skorelowane. Na prawdziwej nocy dawało to medianę okna
 * „ton 400 Hz, okresowość 0,7”, czyli detektor uznawał **oddech za dźwięk dźwięczny** i
 * doliczał mu za to premię. Po poprawce ta sama mediana to okresowość 0,01.
 *
 * Sygnał decymujemy 2× (z filtrem [0,25; 0,5; 0,25] przeciw aliasingowi), bo ton krtaniowy
 * leży grubo poniżej 4 kHz, a decymacja czterokrotnie skraca pętlę korelacji.
 */
class PitchTracker(
    private val sampleRate: Int,
    windowSamples: Int,
    private val minHz: Float = MIN_HZ,
    private val maxHz: Float = MAX_HZ,
) {
    private val decimatedRate = sampleRate / DECIMATION
    private val decimated = FloatArray(windowSamples / DECIMATION)

    private val minLag = maxOf(2, (decimatedRate / maxHz).toInt())
    private val maxLag = minOf(decimated.size - MIN_OVERLAP, (decimatedRate / minHz).toInt())
    private val acf = DoubleArray(maxOf(maxLag, minLag) + 1)
    private val prefix = DoubleArray(decimated.size + 1)

    /** [count] próbek z [samples]; wartości w dowolnej skali — wynik jest znormalizowany. */
    fun estimate(samples: FloatArray, count: Int): PitchEstimate {
        val n = decimate(samples, count)
        val lastLag = minOf(maxLag, n - MIN_OVERLAP)
        if (n <= minLag + MIN_OVERLAP || lastLag <= minLag) return PitchEstimate.NONE

        var mean = 0.0
        for (i in 0 until n) mean += decimated[i]
        mean /= n
        for (i in 0 until n) decimated[i] = (decimated[i] - mean).toFloat()

        // Sumy prefiksowe kwadratów: energia obu okien korelacji bez liczenia jej co lag.
        prefix[0] = 0.0
        for (i in 0 until n) prefix[i + 1] = prefix[i] + decimated[i].toDouble() * decimated[i]
        val total = prefix[n]
        if (total <= 0.0) return PitchEstimate.NONE

        for (lag in 1..lastLag) {
            val len = n - lag
            var dot = 0.0
            for (i in 0 until len) dot += decimated[i].toDouble() * decimated[i + lag]
            val energyA = prefix[len]
            val energyB = total - prefix[lag]
            val denominator = sqrt(energyA * energyB)
            acf[lag] = if (denominator > 0.0) dot / denominator else 0.0
        }

        // Pierwsze przejście przez zero kończy „ramię” przy lagu 0. Szczyt szukany przed nim
        // jest artefaktem dolnoprzepustowości sygnału, nie okresem.
        var zeroCrossing = 0
        for (lag in 1..lastLag) {
            if (acf[lag] <= 0.0) {
                zeroCrossing = lag
                break
            }
        }
        // Brak przejścia przez zero = w oknie nie ma okresu krótszego niż okno; nie zgadujemy.
        if (zeroCrossing == 0) return PitchEstimate.NONE

        val searchFrom = maxOf(minLag, zeroCrossing)
        if (searchFrom > lastLag) return PitchEstimate.NONE

        var bestLag = 0
        var bestValue = 0.0
        for (lag in searchFrom..lastLag) {
            if (acf[lag] > bestValue) {
                bestValue = acf[lag]
                bestLag = lag
            }
        }

        if (bestLag == 0 || bestValue <= 0.0) return PitchEstimate.NONE
        return PitchEstimate(
            hz = decimatedRate.toFloat() / bestLag,
            strength = bestValue.toFloat().coerceIn(0f, 1f),
        )
    }

    private fun decimate(samples: FloatArray, count: Int): Int {
        var out = 0
        var i = 1
        val limit = minOf(count, decimated.size * DECIMATION) - 1
        while (i < limit && out < decimated.size) {
            decimated[out++] = 0.25f * samples[i - 1] + 0.5f * samples[i] + 0.25f * samples[i + 1]
            i += DECIMATION
        }
        return out
    }

    companion object {
        private const val DECIMATION = 2

        /** Poniżej tego nie ma już ani chrapania, ani mowy — jest szum sieciowy i wentylatory. */
        const val MIN_HZ = 45f

        /** Górna granica tonu krtaniowego dorosłego z zapasem. */
        const val MAX_HZ = 400f

        /** Poniżej granicy tonu krtaniowego dorosłego — tak nisko buczy tylko chrapanie. */
        const val SNORE_MAX_HZ = 90f

        /** Ile próbek musi się pokrywać, żeby korelacja przy największym lagu coś znaczyła. */
        private const val MIN_OVERLAP = 96
    }
}
