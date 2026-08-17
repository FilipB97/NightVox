package pl.nightvox.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministyczny syntetyczny PCM do testów bramki.
 *
 * Cały sygnał sesji generujemy z góry jako jedną tablicę, a potem karmimy nim bramkę
 * ramka po ramce. Dzięki temu test może porównać zawartość klipu z **dokładnym** wycinkiem
 * sygnału — czyli sprawdzić, że pre-roll naprawdę zawiera to, co trzeba, a nie tylko że
 * plik jest odpowiednio długi.
 */
object SignalFixtures {

    const val SAMPLE_RATE = GateConfig.DEFAULT_SAMPLE_RATE
    const val FRAME = GateConfig.DEFAULT_FRAME_SAMPLES

    fun msToSamples(ms: Long): Int = (ms * SAMPLE_RATE / 1000).toInt()

    /** Amplituda liniowa odpowiadająca zadanemu poziomowi dBFS. */
    fun amplitudeFor(dbfs: Float): Double = 32768.0 * Math.pow(10.0, dbfs / 20.0)

    /** Szum o zadanym RMS w dBFS, deterministyczny dla danego ziarna. */
    fun noise(lengthSamples: Int, dbfs: Float, seed: Int = 42): ShortArray {
        val random = Random(seed)
        val amplitude = amplitudeFor(dbfs)
        return ShortArray(lengthSamples) {
            // Suma dwóch rozkładów jednostajnych ≈ trójkątny; RMS = amplituda * 1/√6 * 2.
            val u = (random.nextDouble() - 0.5) + (random.nextDouble() - 0.5)
            (u * amplitude * SQRT_6).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Ton 300 Hz o zadanym RMS w dBFS — proxy dla mamrotania przez sen. */
    fun tone(lengthSamples: Int, dbfs: Float, frequency: Double = 300.0, phase: Double = 0.0): ShortArray {
        val peak = amplitudeFor(dbfs) * Math.sqrt(2.0)
        return ShortArray(lengthSamples) { i ->
            (peak * sin(2 * PI * frequency * i / SAMPLE_RATE + phase)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Wkleja [insert] do [base] od podanej próbki, sumując (a nie zastępując) sygnały. */
    fun mixInto(base: ShortArray, insert: ShortArray, atSample: Int) {
        for (i in insert.indices) {
            val index = atSample + i
            if (index !in base.indices) continue
            base[index] = (base[index] + insert[i]).coerceIn(-32768, 32767).toShort()
        }
    }

    private val SQRT_6 = Math.sqrt(6.0)
}
