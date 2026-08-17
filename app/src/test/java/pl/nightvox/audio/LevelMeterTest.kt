package pl.nightvox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMeterTest {

    @Test
    fun `cisza daje minimalny poziom`() {
        val silence = ShortArray(320)
        assertEquals(LevelMeter.MIN_DBFS, LevelMeter.rmsDbfs(silence), 0.01f)
        assertEquals(0, LevelMeter.peak(silence))
    }

    @Test
    fun `pelna skala daje zero dBFS`() {
        val full = ShortArray(320) { 32767 }
        assertEquals(0f, LevelMeter.rmsDbfs(full), 0.01f)
    }

    @Test
    fun `polowa amplitudy to okolo minus 6 dB`() {
        val half = ShortArray(320) { 16384 }
        assertEquals(-6.02f, LevelMeter.rmsDbfs(half), 0.05f)
    }

    @Test
    fun `ton o zadanym RMS mierzy sie zgodnie z zalozeniem`() {
        for (target in listOf(-20f, -35f, -50f)) {
            val signal = SignalFixtures.tone(16_000, target)
            assertEquals("target=$target", target, LevelMeter.rmsDbfs(signal), 0.3f)
        }
    }

    @Test
    fun `peak jest zawsze nie mniejszy niz rms`() {
        val signal = SignalFixtures.tone(1_600, -30f)
        assertTrue(LevelMeter.peakDbfs(signal) >= LevelMeter.rmsDbfs(signal))
    }

    @Test
    fun `peak radzi sobie z wartoscia minimalna short`() {
        val signal = shortArrayOf(0, -32768, 5)
        assertEquals(32768, LevelMeter.peak(signal))
        assertEquals(0f, LevelMeter.toDbfs(32768.0), 0.01f)
    }
}
