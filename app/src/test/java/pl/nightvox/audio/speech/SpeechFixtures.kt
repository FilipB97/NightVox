package pl.nightvox.audio.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Syntetyczne sygnały trzech rzeczy, które nocą przekraczają próg bramki.
 *
 * Uczciwe zastrzeżenie: to **nie** jest nagranie prawdziwej sypialni i żaden test na tym
 * zbudowany nie dowodzi, że detektor działa na prawdziwej mowie. Te sygnały mają natomiast
 * dokładnie te własności, na których detektor opiera decyzję — oddech jest szumem bez
 * okresowości, chrapanie buczy poniżej 90 Hz z energią skupioną nisko, mowa ma ton
 * krtaniowy w zakresie głosu, ruchome formanty i rytm sylab. Jeśli detektor nie rozdziela
 * *tych* sygnałów, to znaczy, że nie liczy tego, co deklaruje, i nie ma po co iść dalej.
 */
object SpeechFixtures {

    const val SAMPLE_RATE = 16_000

    /**
     * Oddech: szum przepuszczony przez filtr dolnoprzepustowy, z powolnym wznoszeniem
     * i opadaniem (0,25 Hz — wdech/wydech). Bez okresowości, bez formantów.
     */
    fun breathing(durationMs: Long, amplitude: Double = 3_000.0, seed: Int = 7): ShortArray {
        val n = samples(durationMs)
        val random = Random(seed)
        var lowpass = 0.0
        return ShortArray(n) { i ->
            val white = random.nextDouble() * 2.0 - 1.0
            // Jednobiegunowy filtr ≈ 2 kHz — turbulencja w nosie nie jest białym szumem.
            lowpass += 0.55 * (white - lowpass)
            val envelope = 0.35 + 0.65 * (0.5 - 0.5 * cos(2 * PI * 0.25 * i / SAMPLE_RATE))
            clip(lowpass * amplitude * envelope * 2.2)
        }
    }

    /**
     * Chrapanie: ton podstawowy 55 Hz z harmonicznymi opadającymi jak 1/n (czyli energia
     * skupiona poniżej ~600 Hz), w paczkach 1,6 s co 3,6 s — rytm oddechu, nie sylab.
     */
    fun snoring(durationMs: Long, amplitude: Double = 5_000.0, f0: Double = 55.0, seed: Int = 11): ShortArray {
        val n = samples(durationMs)
        val random = Random(seed)
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val phase = t % 3.6
            val envelope = when {
                phase > 1.6 -> 0.0
                else -> 0.5 - 0.5 * cos(2 * PI * phase / 1.6)
            }
            if (envelope <= 0.0) return@ShortArray 0
            var value = 0.0
            for (h in 1..12) {
                value += sin(2 * PI * f0 * h * t) / h
            }
            // Trochę szumu przepływowego, żeby to nie był idealny sygnał harmoniczny.
            value += 0.08 * (random.nextDouble() * 2 - 1)
            clip(value * amplitude * envelope)
        }
    }

    /**
     * Mowa: sylaby 4,5/s, ton krtaniowy pełzający 110–140 Hz, formanty przeskakujące między
     * trzema samogłoskami, na starcie każdej sylaby krótka głoska szczelinowa.
     */
    fun speech(durationMs: Long, amplitude: Double = 4_000.0, seed: Int = 13): ShortArray {
        val n = samples(durationMs)
        val random = Random(seed)
        val syllableSamples = (SAMPLE_RATE / 4.5).toInt()
        val vowels = listOf(
            doubleArrayOf(730.0, 1090.0, 2440.0),
            doubleArrayOf(270.0, 2290.0, 3010.0),
            doubleArrayOf(300.0, 870.0, 2240.0),
            doubleArrayOf(530.0, 1840.0, 2480.0),
        )
        val out = ShortArray(n)
        var index = 0
        var syllable = 0
        var phase = 0.0
        while (index < n) {
            val vowel = vowels[syllable % vowels.size]
            val f0 = 110.0 + 30.0 * ((syllable % 3) / 2.0)
            val voicedLength = (syllableSamples * 0.72).toInt()
            val fricativeLength = (syllableSamples * 0.12).toInt()

            for (i in 0 until fricativeLength) {
                if (index >= n) break
                // Szczelinowa: szum górnopasmowy (różnica sąsiednich próbek podbija górę).
                val noise = random.nextDouble() * 2 - 1
                val envelope = sin(PI * i / fricativeLength)
                out[index] = clip(noise * amplitude * 0.35 * envelope)
                index++
            }
            for (i in 0 until voicedLength) {
                if (index >= n) break
                phase += f0 / SAMPLE_RATE
                var value = 0.0
                var h = 1
                while (h * f0 < 5_000.0) {
                    val f = h * f0
                    value += formantGain(f, vowel) * sin(2 * PI * h * phase) / h
                    h++
                }
                val envelope = sin(PI * i / voicedLength).let { it * it }
                out[index] = clip(value * amplitude * envelope)
                index++
            }
            // Krótka pauza między sylabami — to ona daje rytm 4,5 Hz.
            val gap = syllableSamples - voicedLength - fricativeLength
            index += maxOf(0, gap)
            syllable++
        }
        return out
    }

    /** Wzmocnienie trzech rezonansów dwubiegunowych — kształt widma samogłoski. */
    private fun formantGain(f: Double, formants: DoubleArray): Double {
        var gain = 0.0
        for ((k, center) in formants.withIndex()) {
            val bandwidth = 90.0 + 30.0 * k
            val numerator = center * bandwidth
            val denominator = sqrt(
                (center * center - f * f) * (center * center - f * f) + (f * bandwidth) * (f * bandwidth),
            )
            gain += numerator / maxOf(denominator, 1e-9) / (k + 1)
        }
        return gain
    }

    fun samples(durationMs: Long): Int = (durationMs * SAMPLE_RATE / 1000).toInt()

    private fun clip(value: Double): Short = value.toInt().coerceIn(-32768, 32767).toShort()
}
