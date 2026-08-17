package pl.nightvox.audio

/**
 * Kołowy bufor PCM16 na pre-roll.
 *
 * Zapis jest bezwarunkowy (każda ramka, niezależnie od stanu bramki). [snapshot] **nie**
 * czyści bufora — po zrzucie do enkodera bufor leci dalej, zgodnie z §4.4 planu.
 */
class RingBuffer(val capacitySamples: Int) {

    init {
        require(capacitySamples > 0) { "capacitySamples musi być > 0" }
    }

    private val data = ShortArray(capacitySamples)
    private var writeIndex = 0
    private var filled = 0

    /** Liczba próbek dostępnych do odczytu (rośnie do [capacitySamples], potem stała). */
    val available: Int get() = filled

    fun write(samples: ShortArray, count: Int = samples.size) {
        if (count <= 0) return
        // Jeśli ramka jest większa niż bufor, ma znaczenie tylko jej ogon.
        val start = if (count > capacitySamples) count - capacitySamples else 0
        val n = count - start

        val untilEnd = capacitySamples - writeIndex
        if (n <= untilEnd) {
            System.arraycopy(samples, start, data, writeIndex, n)
        } else {
            System.arraycopy(samples, start, data, writeIndex, untilEnd)
            System.arraycopy(samples, start + untilEnd, data, 0, n - untilEnd)
        }
        writeIndex = (writeIndex + n) % capacitySamples
        filled = minOf(capacitySamples, filled + n)
    }

    /** Zawartość bufora od najstarszej do najnowszej próbki. Nie modyfikuje stanu. */
    fun snapshot(): ShortArray {
        if (filled == 0) return ShortArray(0)
        val out = ShortArray(filled)
        val start = ((writeIndex - filled) % capacitySamples + capacitySamples) % capacitySamples
        val untilEnd = capacitySamples - start
        if (filled <= untilEnd) {
            System.arraycopy(data, start, out, 0, filled)
        } else {
            System.arraycopy(data, start, out, 0, untilEnd)
            System.arraycopy(data, 0, out, untilEnd, filled - untilEnd)
        }
        return out
    }

    fun clear() {
        writeIndex = 0
        filled = 0
    }
}
