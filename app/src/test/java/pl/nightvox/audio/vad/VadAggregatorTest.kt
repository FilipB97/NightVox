package pl.nightvox.audio.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadAggregatorTest {

    private fun aggregator(threshold: Float = 0.5f) = VadAggregator(chunkMs = 32, speechThreshold = threshold)

    @Test
    fun `pusty agregat nie ma czego raportowac`() {
        assertTrue(aggregator().isEmpty)
    }

    /**
     * Najważniejsza decyzja: `vadScore` to **maksimum**, nie średnia. Mamrotanie przez sen
     * to zwykle dwa słowa w kilkusekundowym klipie — średnia rozmyłaby je poniżej progu i
     * wyrzuciła dokładnie to, po co ta apka istnieje.
     */
    @Test
    fun `krotka mowa w dlugiej ciszy przezywa jako maksimum`() {
        val aggregator = aggregator()
        repeat(60) { aggregator.add(0.01f) }
        repeat(4) { aggregator.add(0.93f) }
        repeat(60) { aggregator.add(0.01f) }

        val result = aggregator.result()
        assertEquals(0.93f, result.maxProbability, 1e-6f)
        assertTrue(
            "Średnia (${result.meanProbability}) sama nie przeszłaby progu — dlatego liczy się maksimum",
            result.meanProbability < 0.5f,
        )
        assertEquals(4 * 32L, result.speechMs)
        assertEquals(124 * 32L, result.analyzedMs)
    }

    @Test
    fun `cisza daje zerowy wynik`() {
        val aggregator = aggregator()
        repeat(20) { aggregator.add(0.002f) }

        val result = aggregator.result()
        assertTrue(result.maxProbability < 0.01f)
        assertEquals(0L, result.speechMs)
        assertEquals(0f, result.speechRatio, 1e-6f)
    }

    @Test
    fun `prog decyduje ile chunkow liczy sie jako mowa`() {
        fun speechMsFor(threshold: Float): Long {
            val aggregator = aggregator(threshold)
            listOf(0.2f, 0.45f, 0.6f, 0.8f).forEach(aggregator::add)
            return aggregator.result().speechMs
        }
        assertEquals(2 * 32L, speechMsFor(0.5f))
        assertEquals(3 * 32L, speechMsFor(0.3f))
        assertEquals(1 * 32L, speechMsFor(0.7f))
    }

    @Test
    fun `speechRatio jest udzialem mowy w przeanalizowanym audio`() {
        val aggregator = aggregator()
        repeat(3) { aggregator.add(0.9f) }
        repeat(1) { aggregator.add(0.1f) }

        assertEquals(0.75f, aggregator.result().speechRatio, 1e-6f)
    }

    @Test
    fun `reset zaczyna liczenie od nowa`() {
        val aggregator = aggregator()
        aggregator.add(0.99f)
        aggregator.reset()

        assertTrue(aggregator.isEmpty)
        aggregator.add(0.1f)
        assertEquals(0.1f, aggregator.result().maxProbability, 1e-6f)
    }

    @Test
    fun `wynik niesie oba czasy`() {
        val aggregator = aggregator()
        repeat(10) { aggregator.add(0.6f) }
        val result = aggregator.result()
        assertNotNull(result)
        assertEquals(320L, result.speechMs)
        assertEquals(320L, result.analyzedMs)
    }
}
