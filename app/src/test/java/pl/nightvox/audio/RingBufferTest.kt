package pl.nightvox.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RingBufferTest {

    @Test
    fun `pusty bufor zwraca pusta migawke`() {
        val buffer = RingBuffer(100)
        assertEquals(0, buffer.available)
        assertEquals(0, buffer.snapshot().size)
    }

    @Test
    fun `czesciowo wypelniony bufor zwraca tylko to co zapisano`() {
        val buffer = RingBuffer(100)
        buffer.write(shortArrayOf(1, 2, 3))
        assertEquals(3, buffer.available)
        assertArrayEquals(shortArrayOf(1, 2, 3), buffer.snapshot())
    }

    /** Zawijanie musi działać dla **każdej** pozycji zapisu, nie tylko dla ładnych dzielników. */
    @Test
    fun `zawijanie dziala w kazdej pozycji`() {
        val capacity = 17
        for (writeCount in 1..60) {
            val buffer = RingBuffer(capacity)
            var next: Short = 0
            repeat(writeCount) {
                buffer.write(ShortArray(5) { (next + it).toShort() })
                next = (next + 5).toShort()
            }
            val written = writeCount * 5
            val expectedSize = minOf(capacity, written)
            val snapshot = buffer.snapshot()

            assertEquals("writeCount=$writeCount", expectedSize, snapshot.size)
            val firstValue = written - expectedSize
            val expected = ShortArray(expectedSize) { (firstValue + it).toShort() }
            assertArrayEquals("writeCount=$writeCount", expected, snapshot)
        }
    }

    @Test
    fun `zapis dluzszy niz bufor zostawia ogon`() {
        val buffer = RingBuffer(4)
        buffer.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertEquals(4, buffer.available)
        assertArrayEquals(shortArrayOf(4, 5, 6, 7), buffer.snapshot())
    }

    /** §4.4: po zrzucie pre-rollu bufor leci dalej, nie jest czyszczony. */
    @Test
    fun `migawka nie czysci bufora`() {
        val buffer = RingBuffer(6)
        buffer.write(shortArrayOf(1, 2, 3, 4, 5, 6))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6), buffer.snapshot())
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6), buffer.snapshot())

        buffer.write(shortArrayOf(7, 8))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7, 8), buffer.snapshot())
    }

    @Test
    fun `clear resetuje bufor`() {
        val buffer = RingBuffer(4)
        buffer.write(shortArrayOf(1, 2, 3, 4))
        buffer.clear()
        assertEquals(0, buffer.available)
        assertEquals(0, buffer.snapshot().size)
    }

    @Test
    fun `zapis z jawnym licznikiem ignoruje ogon tablicy`() {
        val buffer = RingBuffer(10)
        buffer.write(shortArrayOf(1, 2, 3, 9, 9), count = 3)
        assertArrayEquals(shortArrayOf(1, 2, 3), buffer.snapshot())
    }
}
