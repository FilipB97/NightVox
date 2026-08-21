package pl.nightvox.audio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.nightvox.data.NightVoxSettings

/**
 * Dwie poprawki wymuszone przez pierwszą noc z filtrem: 315 zdarzeń, 27 przepuszczonych,
 * na żadnym nie słychać mowy.
 *
 * 1. Do każdego klipu bramka dokleja pre-roll (3 s) i hangover (4 s) — siedem sekund tła.
 *    Analizator oceniał je razem ze zdarzeniem.
 * 2. Oceną klipu było maksimum po wszystkich oknach. Klip z ośmiu sekund oddechu ma ich
 *    kilkaset, a maksimum z kilkuset zaszumionych ocen to statystyka wartości skrajnych,
 *    nie własność dźwięku: mediana okna 0,08, maksimum klipu 0,77.
 */
class SpeechAnalyzerLevelGateTest {

    private val threshold = NightVoxSettings.DEFAULTS.speechFilterThreshold
    private val backgroundDb = -75f
    private val levelFloor = -55f

    private fun analyze(samples: ShortArray, floorDb: Float): SpeechScore? {
        val analyzer = SpeechAnalyzer(sampleRate = SpeechFixtures.SAMPLE_RATE)
        analyzer.reset(floorDb)
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(320, samples.size - offset)
            analyzer.feed(samples.copyOfRange(offset, offset + count), count)
            offset += count
        }
        return analyzer.finish()
    }

    /** Samo tło nie jest zdarzeniem: poniżej progu nie ma czego oceniać. */
    @Test
    fun sam_pre_roll_i_hangover_nie_daja_oceny() {
        val background = SpeechFixtures.quietNoise(SpeechFixtures.samples(7_000), backgroundDb)
        assertEquals(null, analyze(background, levelFloor))
    }

    /** Doklejone siedem sekund tła nie może zmienić oceny zdarzenia. */
    @Test
    fun tlo_wokol_zdarzenia_nie_zmienia_oceny() {
        val event = SpeechFixtures.speech(3_000, amplitude = 2_000.0)
        val bare = analyze(event, levelFloor)!!
        val withBackground = analyze(
            SpeechFixtures.withPreRollAndHangover(event, backgroundDb),
            levelFloor,
        )!!
        assertEquals(bare.score, withBackground.score, 0.08f)
        assertTrue("mowa z pre-rollem odrzucona: ${withBackground.describe()}", withBackground.score > threshold)
    }

    /**
     * Bez bramki poziomu ten sam klip ocenia także tło — i to ono wyznacza maksimum, bo jest
     * go dziesięć razy więcej niż zdarzenia.
     */
    @Test
    fun bez_bramki_poziomu_tlo_dochodzi_do_glosu() {
        val background = SpeechFixtures.withPreRollAndHangover(
            SpeechFixtures.breathing(2_000, amplitude = 900.0),
            backgroundDb,
        )
        val gated = analyze(background, levelFloor)
        val ungated = analyze(background, SpeechAnalyzer.NO_FLOOR)!!
        val gatedScore = gated?.score ?: 0f
        assertTrue(
            "bramka poziomu nic nie zmieniła: z=${gatedScore}, bez=${ungated.score}",
            gatedScore <= ungated.score,
        )
    }

    /** Sylaba trwa ok. 200 ms. Pojedynczy trzask nie może przejść jako mowa. */
    @Test
    fun krotki_blysk_nie_wystarcza_zeby_uznac_klip_za_mowe() {
        val long = analyze(SpeechFixtures.speech(1_000, amplitude = 2_000.0), levelFloor)!!
        val blink = analyze(SpeechFixtures.speech(60, amplitude = 2_000.0), levelFloor)
        val blinkScore = blink?.score ?: 0f
        assertTrue("długa wypowiedź odrzucona: ${long.describe()}", long.score > threshold)
        assertTrue("60 ms uznane za mowę: $blinkScore", blinkScore < threshold)
    }
}
