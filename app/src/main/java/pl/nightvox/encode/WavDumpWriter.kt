package pl.nightvox.encode

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Surowy dump całej sesji do WAV — narzędzie do strojenia progów z fazy 0, nie funkcja
 * produkcyjna. 16 kHz mono PCM16 to ok. 115 MB na godzinę, więc domyślnie wyłączone.
 *
 * Nagłówek jest łatany przy zamknięciu; jeśli apka padnie w trakcie, plik i tak da się
 * odtworzyć większością odtwarzaczy (rozmiary w nagłówku będą zerowe).
 */
class WavDumpWriter(private val file: File, private val sampleRate: Int) {

    private var stream: BufferedOutputStream? = null
    private var dataBytes = 0L
    private val scratch = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)

    fun open() {
        file.parentFile?.mkdirs()
        val out = BufferedOutputStream(file.outputStream(), 64 * 1024)
        out.write(header(0))
        stream = out
        dataBytes = 0
    }

    fun write(samples: ShortArray, count: Int = samples.size) {
        val out = stream ?: return
        var offset = 0
        while (offset < count) {
            scratch.clear()
            val n = minOf(scratch.capacity() / 2, count - offset)
            for (i in 0 until n) scratch.putShort(samples[offset + i])
            out.write(scratch.array(), 0, n * 2)
            offset += n
            dataBytes += n * 2L
        }
    }

    fun close() {
        val out = stream ?: return
        stream = null
        runCatching { out.flush(); out.close() }
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header(dataBytes))
            }
        }.onFailure { Log.w(TAG, "Nie udało się załatać nagłówka WAV", it) }
    }

    val bytesWritten: Long get() = dataBytes

    private fun header(dataSize: Long): ByteArray {
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * 2
        buffer.put("RIFF".toByteArray())
        buffer.putInt((36 + dataSize).toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)               // PCM
        buffer.putShort(1)               // mono
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(2)               // block align
        buffer.putShort(16)              // bits per sample
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize.toInt())
        return buffer.array()
    }

    companion object {
        private const val TAG = "NightVox/WavDump"
    }
}
