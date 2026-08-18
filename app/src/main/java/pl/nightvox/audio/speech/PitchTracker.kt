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

    /** [count] próbek z [samples]; wartości w dowolnej skali — wynik jest znormalizowany. */
    fun estimate(samples: FloatArray, count: Int): PitchEstimate {
        val n = decimate(samples, count)
        if (n <= minLag + MIN_OVERLAP || maxLag <= minLag) return PitchEstimate.NONE

        var mean = 0.0
        for (i in 0 until n) mean += decimated[i]
        mean /= n
        for (i in 0 until n) decimated[i] = (decimated[i] - mean).toFloat()

        var energy = 0.0
        for (i in 0 until n) energy += decimated[i].toDouble() * decimated[i]
        if (energy <= 0.0) return PitchEstimate.NONE

        var bestLag = 0
        var bestValue = 0.0
        val lastLag = minOf(maxLag, n - MIN_OVERLAP)
        for (lag in minLag..lastLag) {
            val len = n - lag
            var dot = 0.0
            var energyA = 0.0
            var energyB = 0.0
            for (i in 0 until len) {
                val a = decimated[i].toDouble()
                val b = decimated[i + lag].toDouble()
                dot += a * b
                energyA += a * a
                energyB += b * b
            }
            val denominator = sqrt(energyA * energyB)
            if (denominator <= 0.0) continue
            val value = dot / denominator
            if (value > bestValue) {
                bestValue = value
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
