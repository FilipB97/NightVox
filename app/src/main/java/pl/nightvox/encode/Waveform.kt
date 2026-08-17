package pl.nightvox.encode

import android.util.Log
import java.io.File

/**
 * Obwiednia klipu: jeden bajt (0..255) na [BUCKET_MS] audio, liczona **przy zapisie**.
 * Rysowanie waveformu nie może wymagać ponownego dekodowania pliku — przy 30 klipach
 * na noc to zauważalny koszt na liście.
 */
object Waveform {
    const val BUCKET_MS = 50
    private const val TAG = "NightVox/Waveform"

    fun bucketSamples(sampleRate: Int): Int = sampleRate * BUCKET_MS / 1000

    fun fileFor(clipFile: File): File = File(clipFile.parentFile, clipFile.nameWithoutExtension + ".peaks")

    fun write(clipFile: File, peaks: ByteArray) {
        runCatching { fileFor(clipFile).writeBytes(peaks) }
            .onFailure { Log.w(TAG, "Nie udało się zapisać obwiedni dla ${clipFile.name}", it) }
    }

    fun read(clipFile: File): ByteArray? {
        val f = fileFor(clipFile)
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun delete(clipFile: File) {
        fileFor(clipFile).delete()
    }

    /** Zbiera szczyty w kubełkach o stałej długości w trakcie enkodowania. */
    class Accumulator(private val bucketSamples: Int) {
        private val peaks = ArrayList<Byte>(256)
        private var samplesInBucket = 0
        private var bucketPeak = 0

        fun add(samples: ShortArray, count: Int = samples.size) {
            for (i in 0 until count) {
                val v = samples[i].toInt()
                val a = if (v < 0) -v else v
                if (a > bucketPeak) bucketPeak = a
                if (++samplesInBucket >= bucketSamples) {
                    peaks.add((bucketPeak * 255 / 32768).coerceIn(0, 255).toByte())
                    samplesInBucket = 0
                    bucketPeak = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            val out = ByteArray(peaks.size + if (samplesInBucket > 0) 1 else 0)
            for (i in peaks.indices) out[i] = peaks[i]
            if (samplesInBucket > 0) {
                out[peaks.size] = (bucketPeak * 255 / 32768).coerceIn(0, 255).toByte()
            }
            return out
        }
    }
}
