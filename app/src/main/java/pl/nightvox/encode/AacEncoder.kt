package pl.nightvox.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * AAC-LC → kontener MPEG-4 (`.m4a`), przez `MediaCodec` + `MediaMuxer`.
 *
 * Klasyczna pętla synchroniczna `dequeueInputBuffer`/`dequeueOutputBuffer`. Instancja
 * żyje na **jednym** wątku enkodera — nigdy na wątku odczytu z mikrofonu, bo `MediaCodec`
 * potrafi się zablokować na kilkadziesiąt ms i wtedy `AudioRecord` gubi próbki.
 *
 * 32 kbps mono 16 kHz ≈ 4 kB/s, czyli minutowy klip ≈ 240 kB.
 */
class AacEncoder(
    val outputFile: File,
    private val sampleRate: Int,
    private val bitRate: Int = DEFAULT_BIT_RATE,
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var fedSamples = 0L
    private var finished = false
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Ile próbek PCM trafiło do enkodera — źródło prawdy o długości klipu. */
    val encodedSamples: Long get() = fedSamples

    fun start() {
        outputFile.parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun write(pcm: ShortArray, count: Int = pcm.size) {
        val encoder = codec ?: error("AacEncoder.start() nie zostało wywołane")
        if (finished) return
        var offset = 0
        while (offset < count) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val buffer = encoder.getInputBuffer(index)
                if (buffer == null) {
                    encoder.queueInputBuffer(index, 0, 0, presentationTimeUs(), 0)
                } else {
                    buffer.clear()
                    val capacityShorts = buffer.remaining() / 2
                    val n = minOf(capacityShorts, count - offset)
                    buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
                    encoder.queueInputBuffer(index, 0, n * 2, presentationTimeUs(), 0)
                    fedSamples += n
                    offset += n
                }
            }
            drain(endOfStream = false)
        }
    }

    /**
     * Domyka strumień i kontener. Bez `BUFFER_FLAG_END_OF_STREAM` przepadłoby ostatnie
     * ~100 ms audio. Zwraca `true`, jeśli plik zawiera cokolwiek nadającego się do odtworzenia.
     */
    fun finish(): Boolean {
        if (finished) return muxerStarted
        finished = true
        val encoder = codec
        if (encoder != null) {
            runCatching {
                // Enkoder z wejściem bufferowym nie obsługuje signalEndOfInputStream() —
                // EOS musi pojechać jako pusty bufor z flagą.
                var attempts = 0
                while (attempts++ < EOS_QUEUE_ATTEMPTS) {
                    val index = encoder.dequeueInputBuffer(EOS_TIMEOUT_US)
                    if (index >= 0) {
                        encoder.queueInputBuffer(
                            index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        break
                    }
                    drain(endOfStream = false)
                }
            }.onFailure { Log.w(TAG, "Nie udało się zakolejkować EOS", it) }
            runCatching { drain(endOfStream = true) }
                .onFailure { Log.w(TAG, "Błąd przy domykaniu strumienia", it) }
        }
        release()
        return muxerStarted
    }

    /** Przerywa i kasuje plik — używane, gdy klip zostaje odrzucony przez bramkę. */
    fun abort() {
        if (!finished) {
            finished = true
            release()
        }
        outputFile.delete()
    }

    private fun presentationTimeUs(): Long = fedSamples * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val mux = muxer ?: return
        var idleRounds = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) EOS_TIMEOUT_US else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // W trakcie nagrywania po prostu wracamy; przy domykaniu dajemy enkoderowi
                // szansę wypluć ogon, ale nie kręcimy się w nieskończoność.
                if (!endOfStream || ++idleRounds >= EOS_DRAIN_ROUNDS) return
                continue
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!muxerStarted) { "Format wyjściowy zmienił się dwa razy" }
                trackIndex = mux.addTrack(encoder.outputFormat)
                mux.start()
                muxerStarted = true
                continue
            }
            if (index < 0) continue

            val buffer = encoder.getOutputBuffer(index)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // Konfiguracja kodeka jedzie w formacie ścieżki, nie jako próbka.
                bufferInfo.size = 0
            }
            if (bufferInfo.size > 0 && buffer != null && muxerStarted) {
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                mux.writeSampleData(trackIndex, buffer, bufferInfo)
            }
            encoder.releaseOutputBuffer(index, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun release() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        muxer?.let { m ->
            if (muxerStarted) runCatching { m.stop() }
            runCatching { m.release() }
        }
        muxer = null
    }

    companion object {
        private const val TAG = "NightVox/Encoder"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L
        private const val EOS_TIMEOUT_US = 100_000L
        private const val EOS_QUEUE_ATTEMPTS = 10
        private const val EOS_DRAIN_ROUNDS = 5
        private const val MAX_INPUT_SIZE = 16 * 1024
        const val DEFAULT_BIT_RATE = 32_000
    }
}
