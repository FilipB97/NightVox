package pl.nightvox.audio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ton podstawowy to jedyna cecha, która **rozdziela chrapanie od mowy**, a nie tylko
 * odróżnia dźwięk okresowy od szumu. Dlatego test pilnuje samej częstotliwości, a nie
 * wyłącznie tego, że „coś jest okresowe”.
 */
class PitchTrackerTest {

    private val sampleRate = 16_000
    private val windowSamples = 1024

    private fun tracker() = PitchTracker(sampleRate, windowSamples)

    private fun harmonic(f0: Double, harmonics: Int = 6): FloatArray =
        FloatArray(windowSamples) { i ->
            var v = 0.0
            for (h in 1..harmonics) v += sin(2 * PI * f0 * h * i / sampleRate) / h
            v.toFloat()
        }

    @Test
    fun znajduje_ton_glosu() {
        val estimate = tracker().estimate(harmonic(125.0), windowSamples)
        assertEquals(125f, estimate.hz, 6f)
        assertTrue("słaba okresowość: ${estimate.strength}", estimate.strength > 0.7f)
    }

    @Test
    fun znajduje_niski_ton_chrapania() {
        val estimate = tracker().estimate(harmonic(55.0, harmonics = 12), windowSamples)
        assertEquals(55f, estimate.hz, 4f)
        assertTrue(estimate.strength > 0.7f)
        assertTrue("55 Hz nie jest tonem głosu", SpeechScorer.pitchWeight(estimate.hz) == 0f)
    }

    @Test
    fun szum_nie_jest_okresowy() {
        val random = Random(3)
        val noise = FloatArray(windowSamples) { (random.nextDouble() * 2 - 1).toFloat() }
        val estimate = tracker().estimate(noise, windowSamples)
        assertTrue("szum uznany za okresowy: ${estimate.strength}", estimate.strength < 0.35f)
    }

    /**
     * Regresja z prawdziwej nocy. Tło w sypialni ma 75–96% energii **poniżej 200 Hz** — to
     * dudnienie budynku i szum własny mikrofonu, a nie dźwięk. Autokorelacja takiego sygnału
     * opada bardzo wolno (0,97 przy lagu 1, wciąż 0,70 przy lagu 30), więc szukanie szczytu od
     * najmniejszego dozwolonego lagu zawsze trafiało w ten stok i meldowało „ton 400 Hz,
     * okresowość 0,77". Dla **mediany** okna, nie dla wyjątku — cały oddech dostawał za to
     * premię za dźwięczność.
     *
     * Sygnał, który nie ma ani jednej składowej powyżej 80 Hz, nie może mieć tonu
     * podstawowego 400 Hz. To jest cała treść tego testu.
     */
    @Test
    fun szum_o_energii_ponizej_80_hz_nie_jest_tonem_400_hz() {
        val random = Random(11)
        val noise = FloatArray(windowSamples)
        val binHz = sampleRate.toDouble() / windowSamples
        var f = binHz
        while (f <= 80.0) {
            val phase = random.nextDouble() * 2 * PI
            for (i in noise.indices) {
                noise[i] += sin(2 * PI * f * i / sampleRate + phase).toFloat()
            }
            f += binHz
        }

        val estimate = tracker().estimate(noise, windowSamples)
        assertTrue(
            "sygnał bez energii nad 80 Hz zgłoszony jako ton ${estimate.hz} Hz " +
                "o sile ${estimate.strength}",
            estimate.hz == 0f || estimate.hz < 150f,
        )
        assertEquals(0f, SpeechScorer.pitchWeight(estimate.hz), 1e-6f)
    }

    @Test
    fun cisza_nie_daje_tonu() {
        val estimate = tracker().estimate(FloatArray(windowSamples), windowSamples)
        assertEquals(PitchEstimate.NONE, estimate)
    }

    @Test
    fun poziom_sygnalu_nie_wplywa_na_ocene() {
        val loud = tracker().estimate(harmonic(150.0).map { it * 100f }.toFloatArray(), windowSamples)
        val quiet = tracker().estimate(harmonic(150.0).map { it * 0.01f }.toFloatArray(), windowSamples)
        assertEquals(loud.hz, quiet.hz, 1f)
        assertEquals(loud.strength, quiet.strength, 0.02f)
    }
}
