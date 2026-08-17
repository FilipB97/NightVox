package pl.nightvox.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Pomiary poziomu na ramce PCM16. Czysta arytmetyka — testowalna bez Androida. */
object LevelMeter {

    /** Najniższy raportowany poziom; odpowiada pojedynczemu LSB w PCM16. */
    const val MIN_DBFS: Float = -90.31f

    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / count)
    }

    fun peak(samples: ShortArray, count: Int = samples.size): Int {
        var peak = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        return peak
    }

    /** Amplituda liniowa (0..32768) → dBFS, przycięte od dołu do [MIN_DBFS]. */
    fun toDbfs(linear: Double): Float {
        val v = max(linear, 1.0) / 32768.0
        return (20.0 * log10(v)).toFloat()
    }

    fun rmsDbfs(samples: ShortArray, count: Int = samples.size): Float =
        toDbfs(rms(samples, count))

    fun peakDbfs(samples: ShortArray, count: Int = samples.size): Float =
        toDbfs(peak(samples, count).toDouble())
}
