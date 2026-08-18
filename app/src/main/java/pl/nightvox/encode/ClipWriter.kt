package pl.nightvox.encode

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import pl.nightvox.audio.ClipStats
import pl.nightvox.audio.DiscardReason
import pl.nightvox.audio.GateAction
import pl.nightvox.audio.speech.SpeechScore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Gotowy, zamknięty plik klipu razem ze statystykami z bramki i oceną mowy. */
data class FinishedClip(
    val file: File,
    val stats: ClipStats,
    /** `null`, gdy detektor jest wyłączony albo klip był krótszy niż jedno okno analizy. */
    val speech: SpeechScore? = null,
)

/**
 * Konsumuje akcje bramki i zamienia je na pliki `.m4a`.
 *
 * Pracuje na **własnym, jednowątkowym dispatcherze** — enkodowanie nigdy nie może
 * blokować wątku odczytu z mikrofonu (§3 planu). Akcje idą przez bufforowany kanał,
 * więc chwilowy zator w `MediaCodec` nie zatrzymuje pipeline'u.
 */
class ClipWriter(
    private val clipsDir: File,
    private val sampleRate: Int,
    private val bitRate: Int = AacEncoder.DEFAULT_BIT_RATE,
    private val minFreeBytes: Long = MIN_FREE_BYTES,
    /**
     * Czy zachowywać nagrania odrzucone przez bramkę. Dopóki progi nie są dostrojone,
     * najważniejsze pytanie brzmi „czy filtr nie wyrzuca mowy” — a bez pliku nie da się
     * na nie odpowiedzieć.
     */
    private val keepDiscarded: Boolean = true,
    /** Drugi stopień detekcji; `null` = tylko bramka RMS. */
    private val speechDetector: SpeechDetector? = null,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        suspend fun onClipFinished(clip: FinishedClip)

        /** [file] jest `null`, gdy zachowywanie odrzuconych jest wyłączone. */
        suspend fun onClipDiscarded(
            reason: DiscardReason,
            stats: ClipStats,
            file: File?,
            speech: SpeechScore?,
        )
        suspend fun onWriterError(message: String, cause: Throwable?)

        /** Za mało miejsca na dysku — sesja powinna się zakończyć (§6.5). */
        suspend fun onOutOfSpace(freeBytes: Long)
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "nightvox-encoder") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val actions = Channel<GateAction>(capacity = CHANNEL_CAPACITY)
    private var job: Job? = null

    private var encoder: AacEncoder? = null
    private var accumulator: Waveform.Accumulator? = null
    private var skipCurrentClip = false

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(dispatcher) {
            for (action in actions) {
                runCatching { handle(action) }
                    .onFailure { callbacks.onWriterError("Błąd zapisu klipu", it) }
            }
        }
    }

    /** Wołane z wątku pipeline'u. Zawieszenie tu oznacza, że enkoder nie nadąża. */
    suspend fun submit(action: GateAction) {
        actions.send(action)
    }

    /** Domyka kanał, czeka na przetworzenie zaległości i zwalnia wątek enkodera. */
    suspend fun close() {
        actions.close()
        job?.join()
        job = null
        abortOpenEncoder()
        runCatching { speechDetector?.close() }
        executor.shutdown()
    }

    private suspend fun handle(action: GateAction) {
        when (action) {
            is GateAction.OpenClip -> openClip(action.startedAtMs)
            is GateAction.Write -> {
                if (!skipCurrentClip) {
                    encoder?.write(action.samples)
                    accumulator?.add(action.samples)
                    speechDetector?.feed(action.samples)
                }
            }
            is GateAction.CloseClip -> {
                val file = finalizeFile()
                val speech = speechDetector?.finish()
                skipCurrentClip = false
                if (file != null) callbacks.onClipFinished(FinishedClip(file, action.stats, speech))
            }

            is GateAction.DiscardClip -> {
                val wasSkipped = skipCurrentClip
                val file = if (keepDiscarded) finalizeFile() else { abortOpenEncoder(); null }
                val speech = speechDetector?.finish()
                skipCurrentClip = false
                if (!wasSkipped) callbacks.onClipDiscarded(action.reason, action.stats, file, speech)
            }
        }
    }

    private suspend fun openClip(startedAtMs: Long) {
        abortOpenEncoder()
        skipCurrentClip = false

        val free = freeBytes()
        if (free in 0 until minFreeBytes) {
            skipCurrentClip = true
            callbacks.onOutOfSpace(free)
            return
        }

        speechDetector?.start()
        val file = nextClipFile(startedAtMs)
        try {
            encoder = AacEncoder(file, sampleRate, bitRate).apply { start() }
            accumulator = Waveform.Accumulator(Waveform.bucketSamples(sampleRate))
        } catch (e: Exception) {
            encoder = null
            accumulator = null
            skipCurrentClip = true
            callbacks.onWriterError("Nie udało się otworzyć enkodera dla ${file.name}", e)
        }
    }

    /** Domyka enkoder i zwraca gotowy plik, albo `null`, jeśli nic nadającego się nie powstało. */
    private suspend fun finalizeFile(): File? {
        val enc = encoder ?: return null
        val acc = accumulator
        encoder = null
        accumulator = null

        val playable = enc.finish()
        if (!playable || !enc.outputFile.isFile || enc.outputFile.length() == 0L) {
            enc.outputFile.delete()
            Waveform.delete(enc.outputFile)
            callbacks.onWriterError("Klip ${enc.outputFile.name} wyszedł pusty — pomijam", null)
            return null
        }
        acc?.let { Waveform.write(enc.outputFile, it.toByteArray()) }
        return enc.outputFile
    }

    private fun abortOpenEncoder() {
        encoder?.let { enc ->
            runCatching { enc.abort() }
                .onFailure { Log.w(TAG, "abort() enkodera rzucił", it) }
            Waveform.delete(enc.outputFile)
        }
        encoder = null
        accumulator = null
    }

    private fun freeBytes(): Long = runCatching {
        val stat = StatFs(clipsDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(-1L)

    private fun nextClipFile(startedAtMs: Long): File {
        val date = Date(startedAtMs)
        val dir = File(clipsDir, DAY_FORMAT.format(date))
        dir.mkdirs()
        val base = TIME_FORMAT.format(date)
        var candidate = File(dir, "$base.m4a")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$suffix.m4a")
            suffix++
        }
        return candidate
    }

    companion object {
        private const val TAG = "NightVox/ClipWriter"
        private const val CHANNEL_CAPACITY = 512

        /** Poniżej tego progu przerywamy sesję zamiast walić wyjątkiem (§6.5). */
        const val MIN_FREE_BYTES = 200L * 1024 * 1024

        private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val TIME_FORMAT = SimpleDateFormat("HHmmss", Locale.US)
    }
}
