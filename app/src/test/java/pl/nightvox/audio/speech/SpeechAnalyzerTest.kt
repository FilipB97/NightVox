package pl.nightvox.audio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.nightvox.data.NightVoxSettings

/**
 * To jest test, dla którego powstał cały pakiet: bramka RMS reaguje na głośność, a nocą
 * głośniejsze od tła są trzy różne rzeczy. Po nocy z samą bramką powstały 102 klipy, na
 * żadnym nie było mowy — sam oddech i chrapanie. Analizator ma je rozdzielić.
 *
 * Sygnały są syntetyczne ([SpeechFixtures]), więc test dowodzi, że detektor mierzy to, co
 * deklaruje — nie że sprawdzi się na prawdziwej sypialni. Na to jest kosz „Odrzucone”
 * i ocena zapisywana przy każdym klipie.
 */
class SpeechAnalyzerTest {

    private val threshold = NightVoxSettings.DEFAULTS.speechFilterThreshold

    @Test
    fun mowa_dostaje_wysoka_ocene_a_oddech_i_chrapanie_niska() {
        val speech = analyze(SpeechFixtures.speech(8_000))
        val snoring = analyze(SpeechFixtures.snoring(8_000))
        val breathing = analyze(SpeechFixtures.breathing(8_000))

        assertTrue("mowa poniżej progu: ${speech.describe()}", speech.score > threshold)
        assertTrue("chrapanie nad progiem: ${snoring.describe()}", snoring.score < threshold)
        assertTrue("oddech nad progiem: ${breathing.describe()}", breathing.score < threshold)
        assertTrue(
            "margines za mały: mowa=${speech.score}, chrapanie=${snoring.score}, oddech=${breathing.score}",
            speech.score > 2f * maxOf(snoring.score, breathing.score),
        )
    }

    /**
     * Mamrotanie przez sen jest ciche. Gdyby ocena zależała od poziomu, filtr wycinałby
     * dokładnie to, po co ta aplikacja istnieje — więc cechy są z założenia bezwymiarowe.
     */
    @Test
    fun ocena_nie_zalezy_od_glosnosci() {
        val loud = analyze(SpeechFixtures.speech(6_000, amplitude = 8_000.0))
        val quiet = analyze(SpeechFixtures.speech(6_000, amplitude = 300.0))
        assertEquals(loud.score, quiet.score, 0.06f)
        assertTrue("ciche mamrotanie odrzucone: ${quiet.describe()}", quiet.score > threshold)
    }

    /**
     * Przypadek graniczny: chrapanie z tonem 100 Hz wchodzi w zakres głosu, więc sama
     * okresowość go nie wyklucza. Wyklucza go rozkład energii — chrapanie nie ma formantów.
     */
    @Test
    fun wysokie_chrapanie_nadal_nie_jest_mowa() {
        val snoring = analyze(SpeechFixtures.snoring(8_000, f0 = 100.0))
        assertTrue("chrapanie 100 Hz uznane za mowę: ${snoring.describe()}", snoring.score < threshold)
        assertTrue("energia chrapania nie jest niska: ${snoring.describe()}", snoring.meanHiRatio < 0.25f)
    }

    @Test
    fun cisza_dostaje_zero() {
        val silence = analyze(ShortArray(SpeechFixtures.samples(4_000)))
        assertEquals(0f, silence.score, 0.01f)
        assertEquals(0L, silence.speechMs)
    }

    /** Krótka wypowiedź nie może przegrać przez to, że nie starczyło jej na ocenę rytmu. */
    @Test
    fun krotka_wypowiedz_przechodzi() {
        val short = analyze(SpeechFixtures.speech(700))
        assertTrue("krótka mowa odrzucona: ${short.describe()}", short.score > threshold)
    }

    @Test
    fun klip_krotszy_niz_okno_nie_daje_oceny() {
        val analyzer = SpeechAnalyzer(sampleRate = SpeechFixtures.SAMPLE_RATE)
        analyzer.reset()
        analyzer.feed(ShortArray(320))
        assertNull(analyzer.finish())
    }

    /** Reset musi czyścić kontekst — inaczej ocena klipu zależałaby od poprzedniego. */
    @Test
    fun reset_czysci_kontekst() {
        val analyzer = SpeechAnalyzer(sampleRate = SpeechFixtures.SAMPLE_RATE)
        analyzer.reset()
        feedAll(analyzer, SpeechFixtures.speech(4_000))
        val first = analyzer.finish()!!

        analyzer.reset()
        feedAll(analyzer, SpeechFixtures.snoring(4_000))
        val second = analyzer.finish()!!

        assertTrue("chrapanie odziedziczyło ocenę po mowie", second.score < first.score / 2f)
    }

    private fun analyze(samples: ShortArray): SpeechScore {
        val analyzer = SpeechAnalyzer(sampleRate = SpeechFixtures.SAMPLE_RATE)
        analyzer.reset()
        feedAll(analyzer, samples)
        return analyzer.finish()!!
    }

    /** Karmimy porcjami po 320 próbek — tak jak robi to pipeline (ramka 20 ms). */
    private fun feedAll(analyzer: SpeechAnalyzer, samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(320, samples.size - offset)
            analyzer.feed(samples.copyOfRange(offset, offset + count), count)
            offset += count
        }
    }
}
