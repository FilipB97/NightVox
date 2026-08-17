package pl.nightvox.audio.vad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Silero VAD na prawdziwym ONNX Runtime.
 *
 * Ograniczenie, które trzeba znać czytając te asercje: model jest trenowany na **prawdziwej
 * mowie**, a tu mamy tylko sygnał syntetyczny. Model źródło-filtr (pobudzenie krtaniowe +
 * rezonatory formantowe) wystarcza, żeby wykryć, czy wrapper jest podpięty poprawnie —
 * zwłaszcza czy chunki dostają wymagany kontekst — ale **nie** mierzy skuteczności VAD na
 * mamrotaniu przez sen. To da się ocenić dopiero na nagraniach z kosza „Odrzucone”.
 */
@RunWith(AndroidJUnit4::class)
class SileroVadInstrumentedTest {

    private val sampleRate = 16_000
    private lateinit var vad: SileroVad

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        vad = SileroVad.create(context, sampleRate)
            ?: throw AssertionError("Model VAD nie załadował się z assetów")
    }

    @After
    fun tearDown() {
        if (this::vad.isInitialized) vad.close()
    }

    @Test
    fun model_laduje_sie_z_assetow() {
        assertNotNull(vad)
    }

    @Test
    fun synteza_formantowa_jest_uznana_za_mowe() {
        val result = analyze(speechLike(seconds = 2.0, amplitude = dbfs(-10f)))
        assertTrue(
            "Sygnał mowo-podobny dostał tylko ${result.maxProbability} — sprawdź kontekst chunków",
            result.maxProbability > 0.5f,
        )
        assertTrue("Żaden chunk nie został uznany za mowę", result.speechMs > 0)
    }

    @Test
    fun cisza_ton_i_chrapanie_nie_sa_mowa() {
        val silence = ShortArray(sampleRate * 2) { (Random(7).nextInt(-32, 32)).toShort() }
        val tone = ShortArray(sampleRate * 2) { i ->
            (dbfs(-30f) * 32768 * sin(2 * PI * 300 * i / sampleRate)).toInt().toShort()
        }
        val snoreLike = ShortArray(sampleRate * 2) { i ->
            val t = i.toDouble() / sampleRate
            (dbfs(-25f) * 32768 * sin(2 * PI * 100 * t) * (0.5 + 0.5 * sin(2 * PI * 0.5 * t)))
                .toInt().toShort()
        }

        for ((name, signal) in listOf("cisza" to silence, "ton" to tone, "chrapanie" to snoreLike)) {
            val result = analyze(signal)
            assertTrue(
                "$name zostało uznane za mowę (${result.maxProbability})",
                result.maxProbability < 0.5f,
            )
        }
    }

    /** Stan modelu jest rekurencyjny — bez resetu poprzedni klip zanieczyszcza następny. */
    @Test
    fun reset_zeruje_stan_miedzy_klipami() {
        val speech = speechLike(seconds = 2.0, amplitude = dbfs(-10f))
        val first = analyze(speech)
        val second = analyze(speech)

        assertTrue(
            "Ten sam sygnał dał ${first.maxProbability} i ${second.maxProbability} — stan nie został wyzerowany",
            abs(first.maxProbability - second.maxProbability) < 0.05f,
        )
    }

    // --- pomocnicze ---

    private fun analyze(samples: ShortArray): VadResult {
        vad.reset()
        val chunker = VadChunker()
        val aggregator = VadAggregator(chunkMs = chunker.chunkSamples * 1000L / sampleRate)
        chunker.feed(samples) { input -> aggregator.add(vad.probability(input)) }
        return aggregator.result()
    }

    private fun dbfs(value: Float): Double = Math.pow(10.0, value / 20.0)

    /**
     * Model źródło-filtr: pobudzenie krtaniowe f0 przepuszczone przez trzy rezonatory
     * formantowe, z obwiednią sylabiczną. Grube przybliżenie mowy, ale deterministyczne.
     */
    private fun speechLike(seconds: Double, amplitude: Double): ShortArray {
        val n = (sampleRate * seconds).toInt()
        val excitation = DoubleArray(n)
        val period = (sampleRate / 120.0).toInt()
        var i = 0
        while (i < n) {
            excitation[i] = 1.0
            i += period
        }

        val out = DoubleArray(n)
        for ((frequency, bandwidth) in listOf(700.0 to 80.0, 1220.0 to 90.0, 2600.0 to 120.0)) {
            val r = exp(-PI * bandwidth / sampleRate)
            val theta = 2 * PI * frequency / sampleRate
            val a1 = -2 * r * cos(theta)
            val a2 = r * r
            val y = DoubleArray(n)
            for (k in 2 until n) {
                y[k] = excitation[k] - a1 * y[k - 1] - a2 * y[k - 2]
            }
            for (k in 0 until n) out[k] += y[k]
        }

        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(n) { k ->
            val envelope = 0.5 + 0.5 * sin(2 * PI * 3 * k / sampleRate)
            ((out[k] / peak) * envelope * amplitude * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
