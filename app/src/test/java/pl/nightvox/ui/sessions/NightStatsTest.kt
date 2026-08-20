package pl.nightvox.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.nightvox.data.db.ClipEntity

class NightStatsTest {

    private var next = 0

    private fun clip(score: Float?, discarded: Boolean = false, startedAt: Long = (next++) * 1000L) =
        ClipEntity(
            id = "id-$startedAt-$score-$discarded",
            sessionId = "s",
            startedAt = startedAt,
            durationMs = 1_000,
            filePath = "/dev/null",
            peakDb = -20f,
            meanDb = -30f,
            voicedMs = 500,
            vadScore = score,
            isDiscarded = discarded,
            discardReason = if (discarded) "NOT_SPEECH" else null,
        )

    @Test
    fun noc_sprzed_filtru_nie_ma_histogramu() {
        assertTrue(NightStats.histogram(listOf(clip(null), clip(null))).isEmpty())
        assertNull(NightStats.medianScore(listOf(clip(null))))
    }

    @Test
    fun histogram_rozdziela_zapisane_od_odrzuconych() {
        val clips = listOf(
            clip(0.05f, discarded = true),
            clip(0.12f, discarded = true),
            clip(0.15f, discarded = true),
            clip(0.72f),
            clip(0.78f),
        )
        val histogram = NightStats.histogram(clips)
        assertEquals(NightStats.BUCKETS, histogram.size)
        assertEquals(1, histogram[0].discarded)
        assertEquals(2, histogram[1].discarded)
        assertEquals(2, histogram[7].kept)
        assertEquals(5, histogram.sumOf { it.total })
        assertEquals(0, histogram[7].discarded)
    }

    /** Ocena 1.0 nie może wypaść poza tablicę — to jest cały powód, dla którego to jest funkcja. */
    @Test
    fun ocena_na_krancach_trafia_do_skrajnych_kubelkow() {
        assertEquals(0, NightStats.bucketOf(0f))
        assertEquals(NightStats.BUCKETS - 1, NightStats.bucketOf(1f))
        assertEquals(NightStats.BUCKETS - 1, NightStats.bucketOf(1.5f))
        assertEquals(0, NightStats.bucketOf(-0.2f))
    }

    @Test
    fun mediana_bierze_srodkowa_ocene() {
        val clips = listOf(clip(0.9f), clip(0.1f), clip(0.5f))
        assertEquals(0.5f, NightStats.medianScore(clips)!!, 1e-6f)
    }

    @Test
    fun najdluzsza_cisza_liczy_takze_ogon_nocy() {
        val clips = listOf(clip(null, startedAt = 10_000), clip(null, startedAt = 20_000))
        // Ogon 20 s -> 100 s to 80 s ciszy; przerwy przed nimi są krótsze.
        assertEquals(80_000L, NightStats.longestQuietGapMs(clips, 0, 100_000))
    }

    @Test
    fun noc_bez_zdarzen_to_jedna_wielka_cisza() {
        assertEquals(3_600_000L, NightStats.longestQuietGapMs(emptyList(), 0, 3_600_000))
    }
}
