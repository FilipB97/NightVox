package pl.nightvox.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Odczyt z mikrofonu na dedykowanym wątku o priorytecie `THREAD_PRIORITY_URGENT_AUDIO`.
 *
 * Odpowiada za wszystko, co potrafi się popsuć w mikrofonie przez noc (§6 planu):
 * fallback źródeł, wyłączenie AGC/NS, backoff i restart po błędzie `read()`, watchdog na
 * martwy strumień oraz wykrycie wyciszenia przez system (`isClientSilenced`).
 *
 * Ramki trafiają do [Listener.onFrame] **na wątku capture** — odbiorca ma je natychmiast
 * przekazać dalej (kanał) i nic ciężkiego tu nie robić.
 */
class AudioCapture(
    private val context: Context,
    private val sampleRate: Int = GateConfig.DEFAULT_SAMPLE_RATE,
    private val frameSamples: Int = GateConfig.DEFAULT_FRAME_SAMPLES,
    private val listener: Listener,
) {
    interface Listener {
        fun onFrame(frame: Frame)

        /** Capture padło; [attempt] to numer próby, [delayMs] — ile czekamy przed restartem. */
        fun onCaptureError(reason: String, attempt: Int, delayMs: Long)

        /** Capture wstało po błędzie albo po watchdogu. */
        fun onCaptureRestarted(source: Int)

        /** System wyciszył nasz strumień (rozmowa, inna apka) — dostajemy same zera. */
        fun onSilencedChanged(silenced: Boolean)

        /** Nie udało się w ogóle otworzyć mikrofonu — sesja nie ma sensu. */
        fun onFatalError(reason: String)
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    var activeSource: Int = -1
        private set

    @Volatile
    var isSilenced: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ captureLoop() }, "nightvox-capture").apply {
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(2_000)
        thread = null
    }

    // --- wątek capture ---

    private fun captureLoop() {
        // Wątek capture nie ma nad sobą nikogo: niewyłapany błąd tutaj zabijał cały proces,
        // czyli aplikacja znikała zamiast pokazać, co się stało.
        try {
            captureLoopInner()
        } catch (t: Throwable) {
            Log.e(TAG, "Wątek capture przewrócił się", t)
            running.set(false)
            runCatching { releaseRecord() }
            listener.onFatalError("Nagrywanie przerwane błędem: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun captureLoopInner() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var attempt = 0
        val buffer = ShortArray(frameSamples)

        while (running.get()) {
            val opened = openRecord()
            if (opened == null) {
                attempt++
                if (attempt > MAX_OPEN_ATTEMPTS) {
                    running.set(false)
                    listener.onFatalError("Nie udało się otworzyć mikrofonu po $MAX_OPEN_ATTEMPTS próbach")
                    break
                }
                val delay = backoffMs(attempt)
                listener.onCaptureError("Nie można otworzyć AudioRecord", attempt, delay)
                sleepInterruptible(delay)
                continue
            }

            if (attempt > 0) listener.onCaptureRestarted(activeSource)
            attempt = 0

            val failure = readUntilFailure(opened, buffer)
            releaseRecord()

            if (!running.get()) break

            attempt++
            val delay = backoffMs(attempt)
            listener.onCaptureError(failure, attempt, delay)
            sleepInterruptible(delay)
        }

        releaseRecord()
    }

    /** Czyta ramki aż do błędu lub zatrzymania. Zwraca opis przyczyny wyjścia. */
    private fun readUntilFailure(record: AudioRecord, buffer: ShortArray): String {
        var silentSinceMs = -1L

        while (running.get()) {
            val read = record.read(buffer, 0, frameSamples)
            if (read < 0) {
                return when (read) {
                    AudioRecord.ERROR_INVALID_OPERATION -> "read() = ERROR_INVALID_OPERATION"
                    AudioRecord.ERROR_DEAD_OBJECT -> "read() = ERROR_DEAD_OBJECT"
                    AudioRecord.ERROR_BAD_VALUE -> "read() = ERROR_BAD_VALUE"
                    else -> "read() = $read"
                }
            }
            if (read == 0) continue

            val samples = if (read == frameSamples) buffer.copyOf() else buffer.copyOf(read)
            val now = System.currentTimeMillis()
            listener.onFrame(Frame(samples, now))

            // Watchdog martwego strumienia (§6.3): dokładne zera przez DEAD_STREAM_MS.
            if (isAllZero(samples)) {
                val uptime = SystemClock.elapsedRealtime()
                if (silentSinceMs < 0) {
                    silentSinceMs = uptime
                } else if (uptime - silentSinceMs > DEAD_STREAM_MS) {
                    return "watchdog: same zera przez ${DEAD_STREAM_MS / 1000} s"
                }
            } else {
                silentSinceMs = -1L
            }
        }
        return "zatrzymane"
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val frameBytes = frameSamples * 2
        val bufferBytes = maxOf(minBuffer * 4, frameBytes * 16)

        for (source in SOURCE_PREFERENCE) {
            // Producenci potrafią rzucić z tego konstruktora praktycznie czymkolwiek
            // (UnsupportedOperationException, RuntimeException z HAL-a). Każde takie
            // źródło po prostu pomijamy i próbujemy następnego z listy.
            val candidate = try {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Brak uprawnienia RECORD_AUDIO", e)
                return null
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord(source=${sourceName(source)}) odrzucone", e)
                null
            }

            if (candidate == null) continue
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release()
                continue
            }

            runCatching { disableProcessing(candidate.audioSessionId) }
            try {
                candidate.startRecording()
            } catch (e: Exception) {
                Log.w(TAG, "startRecording() nie wystartowało dla ${sourceName(source)}", e)
                runCatching { candidate.release() }
                continue
            }
            if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                candidate.release()
                continue
            }

            record = candidate
            activeSource = source
            runCatching { registerSilenceCallback(candidate) }
                .onFailure { Log.w(TAG, "Nie udało się zarejestrować callbacku wyciszenia", it) }
            return candidate
        }
        return null
    }

    /**
     * Mamrotanie przez sen jest ciche — producencki NS/AGC potrafi je „wyrównać” do tła.
     * Wyłączamy, jeśli platforma na to pozwala.
     */
    private fun disableProcessing(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = false }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Nie można wyłączyć NoiseSuppressor", e)
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = false }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Nie można wyłączyć AutomaticGainControl", e)
        }
    }

    private fun registerSilenceCallback(record: AudioRecord) {
        unregisterSilenceCallback()
        val manager = audioManager ?: return
        val sessionId = record.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val mine = configs?.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
                val silenced = mine.isClientSilenced
                if (silenced != isSilenced) {
                    isSilenced = silenced
                    listener.onSilencedChanged(silenced)
                }
            }
        }
        recordingCallback = callback
        manager.registerAudioRecordingCallback(callback, mainHandler)
    }

    private fun unregisterSilenceCallback() {
        recordingCallback?.let { audioManager?.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
    }

    private fun releaseRecord() {
        unregisterSilenceCallback()
        noiseSuppressor?.runCatching { release() }
        noiseSuppressor = null
        agc?.runCatching { release() }
        agc = null
        record?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
        }
        record = null
        if (isSilenced) {
            isSilenced = false
            listener.onSilencedChanged(false)
        }
    }

    private fun sleepInterruptible(ms: Long) {
        val deadline = SystemClock.elapsedRealtime() + ms
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(minOf(200L, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        private const val TAG = "NightVox/Capture"
        private const val DEAD_STREAM_MS = 60_000L
        private const val MAX_OPEN_ATTEMPTS = 20

        /**
         * VOICE_RECOGNITION najpierw — wyłącza część agresywnego przetwarzania producenta.
         * Gdy nieobsługiwane, schodzimy na UNPROCESSED, a na końcu na zwykły MIC.
         */
        private val SOURCE_PREFERENCE = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
        )

        /** Backoff 1 s, 2 s, 5 s, 10 s, dalej 30 s (§6.2). */
        fun backoffMs(attempt: Int): Long = when (attempt) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 5_000L
            4 -> 10_000L
            else -> 30_000L
        }

        fun isAllZero(samples: ShortArray): Boolean {
            for (s in samples) if (s.toInt() != 0) return false
            return true
        }

        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> "źródło #$source"
        }
    }
}
