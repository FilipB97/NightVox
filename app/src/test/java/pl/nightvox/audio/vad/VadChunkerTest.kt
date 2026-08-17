package pl.nightvox.audio.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Silero v5 wymaga `kontekst (64) + chunk (512)` = 576 próbek, gdzie kontekst to ogon
 * poprzedniego chunka. Karmiony samym chunkiem model zwraca ~0.001 na wszystko — mowę
 * też — czyli psuje się bezobjawowo. Te testy pilnują dokładnie tego układu.
 */
class VadChunkerTest {

    private fun ramp(count: Int, from: Int = 0): ShortArray =
        ShortArray(count) { ((from + it) % 30_000).toShort() }

    @Test
    fun `wejscie ma dlugosc kontekst plus chunk`() {
        val chunker = VadChunker()
        assertEquals(576, chunker.inputSamples)

        val inputs = mutableListOf<FloatArray>()
        chunker.feed(ramp(512)) { inputs += it.copyOf() }

        assertEquals(1, inputs.size)
        assertEquals(576, inputs.single().size)
    }

    @Test
    fun `pierwszy chunk dostaje kontekst wyzerowany`() {
        val chunker = VadChunker()
        val inputs = mutableListOf<FloatArray>()
        chunker.feed(ramp(512)) { inputs += it.copyOf() }

        val first = inputs.single()
        for (i in 0 until 64) {
            assertEquals("Kontekst pierwszego chunka musi być zerem (próbka $i)", 0f, first[i], 0f)
        }
    }

    /** Sedno sprawy: kontekst kolejnego wejścia to ostatnie 64 próbki poprzedniego chunka. */
    @Test
    fun `kontekst kolejnego chunka to ogon poprzedniego`() {
        val chunker = VadChunker()
        val samples = ramp(1024)
        val inputs = mutableListOf<FloatArray>()
        chunker.feed(samples) { inputs += it.copyOf() }

        assertEquals(2, inputs.size)
        val second = inputs[1]
        for (i in 0 until 64) {
            val expected = samples[512 - 64 + i] / 32768f
            assertEquals("Kontekst drugiego chunka, próbka $i", expected, second[i], 1e-7f)
        }
        // A za kontekstem idzie już właściwy drugi chunk.
        for (i in 0 until 512) {
            assertEquals(samples[512 + i] / 32768f, second[64 + i], 1e-7f)
        }
    }

    @Test
    fun `ramki mniejsze niz chunk sa sklejane`() {
        val chunker = VadChunker()
        val samples = ramp(512)
        val inputs = mutableListOf<FloatArray>()

        // Pipeline karmi 320-próbkowymi ramkami, więc chunk nigdy nie przychodzi w całości.
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(320, samples.size - offset)
            chunker.feed(samples.copyOfRange(offset, offset + count)) { inputs += it.copyOf() }
            offset += count
        }

        assertEquals(1, inputs.size)
        for (i in 0 until 512) {
            assertEquals(samples[i] / 32768f, inputs.single()[64 + i], 1e-7f)
        }
    }

    @Test
    fun `niepelny ogon nie produkuje wejscia`() {
        val chunker = VadChunker()
        val inputs = mutableListOf<FloatArray>()
        chunker.feed(ramp(511)) { inputs += it.copyOf() }
        assertTrue("Niepełny chunk nie może trafić do modelu", inputs.isEmpty())
    }

    @Test
    fun `normalizacja trzyma sie zakresu minus jeden do jeden`() {
        val chunker = VadChunker()
        val extremes = ShortArray(512) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val inputs = mutableListOf<FloatArray>()
        chunker.feed(extremes) { inputs += it.copyOf() }

        val input = inputs.single()
        assertTrue(input.all { it in -1f..1f })
        assertEquals(Short.MAX_VALUE / 32768f, input[64], 1e-7f)
        assertEquals(-1f, input[65], 1e-7f)
    }

    @Test
    fun `reset czysci kontekst miedzy klipami`() {
        val chunker = VadChunker()
        chunker.feed(ramp(512)) { }

        chunker.reset()
        val inputs = mutableListOf<FloatArray>()
        chunker.feed(ramp(512, from = 5_000)) { inputs += it.copyOf() }

        for (i in 0 until 64) {
            assertEquals("Kontekst po resecie musi być zerem", 0f, inputs.single()[i], 0f)
        }
    }

    @Test
    fun `dlugi strumien daje tyle wejsc ile pelnych chunkow`() {
        val chunker = VadChunker()
        var count = 0
        chunker.feed(ramp(512 * 7 + 100)) { count++ }
        assertEquals(7, count)
    }
}
