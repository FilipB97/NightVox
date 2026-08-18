package pl.nightvox.audio.speech

import kotlin.math.cos
import kotlin.math.sin

/**
 * Iteracyjna FFT radix-2 (Cooley–Tukey) z prekomputowanymi tablicami.
 *
 * Czysty Kotlin, zero zależności — po historii z ONNX Runtime to jest świadoma decyzja:
 * kod, który nie jest natywny, nie potrafi zabić procesu sygnałem SIGSEGV. Sześćdziesiąt
 * linijek arytmetyki kosztuje mniej niż 12 MB `.so` na architekturę i daje się przetestować
 * na JVM razem z resztą pipeline'u.
 *
 * Instancja alokuje bufory raz i **nie jest thread-safe** — żyje na wątku enkodera.
 */
class Fft(val size: Int) {

    init {
        require(size >= 4 && size and (size - 1) == 0) { "rozmiar FFT musi być potęgą dwójki >= 4" }
    }

    /** Liczba użytecznych prążków widma mocy: 0..size/2 włącznie. */
    val bins: Int = size / 2 + 1

    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val reversed = IntArray(size)

    init {
        for (i in 0 until size / 2) {
            val angle = 2.0 * Math.PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) reversed[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /**
     * Liczy |X(k)|² dla k = 0..[bins]-1. [input] musi mieć co najmniej [size] próbek,
     * [out] co najmniej [bins] miejsc.
     */
    fun powerSpectrum(input: FloatArray, out: FloatArray) {
        require(input.size >= size) { "wejście krótsze niż okno FFT" }
        require(out.size >= bins) { "za mało miejsca na widmo" }

        for (i in 0 until size) {
            re[reversed[i]] = input[i]
            im[i] = 0f
        }

        var len = 2
        while (len <= size) {
            val half = len shr 1
            val step = size / len
            var base = 0
            while (base < size) {
                var j = 0
                var twiddle = 0
                while (j < half) {
                    val a = base + j
                    val b = a + half
                    val wr = cosTable[twiddle]
                    val wi = -sinTable[twiddle]
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    j++
                    twiddle += step
                }
                base += len
            }
            len = len shl 1
        }

        for (k in 0 until bins) {
            out[k] = re[k] * re[k] + im[k] * im[k]
        }
    }

    companion object {
        /** Okno Hanna — bez niego przeciek widmowy zamazuje harmoniczne i psuje płaskość. */
        fun hann(size: Int): FloatArray = FloatArray(size) { i ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))).toFloat()
        }
    }
}
