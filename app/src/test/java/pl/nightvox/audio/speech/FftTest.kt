package pl.nightvox.audio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    @Test
    fun sinus_daje_szczyt_w_swoim_prazku() {
        val size = 1024
        val fft = Fft(size)
        val bin = 64
        val input = FloatArray(size) { i -> sin(2 * PI * bin * i / size).toFloat() }
        val out = FloatArray(fft.bins)
        fft.powerSpectrum(input, out)

        val peak = out.indices.maxByOrNull { out[it] }
        assertEquals(bin, peak)
        // Energia poza szczytem musi być pomijalna — inaczej motylki są posklejane źle.
        val leakage = out.filterIndexed { i, _ -> i != bin }.sum()
        assertTrue("przeciek $leakage względem szczytu ${out[bin]}", leakage < out[bin] / 1_000f)
    }

    /** Twierdzenie Parsevala — najtańszy sposób złapać pomylony czynnik skali. */
    @Test
    fun energia_widma_zgadza_sie_z_energia_sygnalu() {
        val size = 256
        val fft = Fft(size)
        val input = FloatArray(size) { i -> sin(2 * PI * 7 * i / size).toFloat() + 0.5f }
        val out = FloatArray(fft.bins)
        fft.powerSpectrum(input, out)

        val timeEnergy = input.sumOf { it.toDouble() * it }
        // out trzyma tylko połowę widma; prążki 1..size/2-1 występują w pełnym widmie dwa razy.
        var spectrumEnergy = out[0].toDouble() + out[size / 2]
        for (k in 1 until size / 2) spectrumEnergy += 2.0 * out[k]
        assertEquals(timeEnergy, spectrumEnergy / size, timeEnergy * 1e-4)
    }

    @Test
    fun rozmiar_musi_byc_potega_dwojki() {
        val error = runCatching { Fft(1000) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun okno_hanna_zaczyna_i_konczy_sie_zerem() {
        val window = Fft.hann(64)
        assertEquals(0f, window.first(), 1e-6f)
        assertEquals(0f, window.last(), 1e-6f)
        assertEquals(1f, window[32], 0.01f)
    }
}
