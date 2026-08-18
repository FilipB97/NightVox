package pl.nightvox.audio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wagi scoringu w jednym miejscu i z jawnymi przypadkami brzegowymi — żeby zmiana progu
 * była decyzją, a nie przypadkiem.
 */
class SpeechScorerTest {

    private fun features(
        pitchHz: Float = 0f,
        pitchStrength: Float = 0f,
        hiRatio: Float = 0f,
        flatness: Float = 1f,
        flux: Float = 0f,
    ) = SpeechFeatures(
        energyDb = -30f,
        pitchHz = pitchHz,
        pitchStrength = pitchStrength,
        hiRatio = hiRatio,
        flatness = flatness,
        flux = flux,
    )

    private val voicedSpeech = features(pitchHz = 130f, pitchStrength = 0.8f, hiRatio = 0.6f, flatness = 0.3f, flux = 0.3f)
    private val snore = features(pitchHz = 55f, pitchStrength = 0.85f, hiRatio = 0.05f, flatness = 0.5f, flux = 0.02f)
    private val breath = features(pitchHz = 0f, pitchStrength = 0.1f, hiRatio = 0.75f, flatness = 0.85f, flux = 0.05f)

    @Test
    fun mowa_bije_chrapanie_i_oddech() {
        val speech = SpeechScorer.score(voicedSpeech)
        assertTrue("mowa $speech", speech > 0.6f)
        assertTrue("chrapanie ${SpeechScorer.score(snore)}", SpeechScorer.score(snore) < 0.15f)
        assertTrue("oddech ${SpeechScorer.score(breath)}", SpeechScorer.score(breath) < 0.3f)
    }

    /**
     * Chrapanie i mowa mogą mieć identyczną, silną okresowość. Różni je wysokość tonu i to,
     * czy nad 300 Hz cokolwiek zostało — i tylko to ma decydować.
     */
    @Test
    fun ton_ponizej_90_hz_jest_karany_a_nie_nagradzany() {
        val low = features(pitchHz = 60f, pitchStrength = 0.9f, hiRatio = 0.6f, flatness = 0.3f, flux = 0.3f)
        val high = features(pitchHz = 160f, pitchStrength = 0.9f, hiRatio = 0.6f, flatness = 0.3f, flux = 0.3f)
        assertTrue(SpeechScorer.score(low) < SpeechScorer.score(high) / 3f)
    }

    /** Szept nie ma tonu krtaniowego, a nadal jest mową — nie może dostać zera. */
    @Test
    fun szept_traci_czesc_oceny_ale_nie_wszystko() {
        val whisper = features(pitchHz = 0f, pitchStrength = 0.05f, hiRatio = 0.6f, flatness = 0.3f, flux = 0.3f)
        val voiced = SpeechScorer.score(voicedSpeech)
        val score = SpeechScorer.score(whisper)
        assertTrue("szept $score vs dźwięczne $voiced", score in (voiced * 0.5f)..(voiced * 0.8f))
    }

    @Test
    fun waga_tonu_odcina_chrapanie_i_przepuszcza_glos() {
        assertEquals(0f, SpeechScorer.pitchWeight(0f), 1e-6f)
        assertEquals(0f, SpeechScorer.pitchWeight(70f), 1e-6f)
        assertEquals(0f, SpeechScorer.pitchWeight(80f), 1e-6f)
        assertEquals(1f, SpeechScorer.pitchWeight(110f), 1e-6f)
        assertEquals(1f, SpeechScorer.pitchWeight(250f), 1e-6f)
        assertTrue(SpeechScorer.pitchWeight(95f) in 0.4f..0.6f)
    }

    @Test
    fun cisza_i_szum_bez_ksztaltu_daja_zero() {
        assertEquals(0f, SpeechScorer.score(features()), 1e-6f)
    }
}
