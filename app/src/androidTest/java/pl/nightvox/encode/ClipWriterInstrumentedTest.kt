package pl.nightvox.encode

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.nightvox.audio.ClipStats
import pl.nightvox.audio.DiscardReason
import pl.nightvox.audio.GateAction
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Integralność pliku `.m4a` (§10): `MediaExtractor` musi odczytać zadeklarowaną długość.
 *
 * To jest test, który wyłapuje brak `BUFFER_FLAG_END_OF_STREAM` albo niedomknięty muxer —
 * czyli klasę błędów, po których pliki „są", ale nie dają się odtworzyć.
 */
@RunWith(AndroidJUnit4::class)
class ClipWriterInstrumentedTest {

    private val sampleRate = 16_000
    private lateinit var clipsDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val finished = mutableListOf<FinishedClip>()
    private val discarded = mutableListOf<DiscardReason>()
    private val errors = mutableListOf<String>()

    private val callbacks = object : ClipWriter.Callbacks {
        override suspend fun onClipFinished(clip: FinishedClip) { finished += clip }
        override suspend fun onClipDiscarded(reason: DiscardReason, stats: ClipStats) { discarded += reason }
        override suspend fun onWriterError(message: String, cause: Throwable?) { errors += message }
        override suspend fun onOutOfSpace(freeBytes: Long) { errors += "brak miejsca" }
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clipsDir = File(context.cacheDir, "test-clips").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        clipsDir.deleteRecursively()
    }

    @Test
    fun zapisany_klip_ma_poprawna_dlugosc_i_obwiednie() = runBlocking {
        val writer = ClipWriter(clipsDir, sampleRate, callbacks = callbacks)
        writer.start(scope)

        val durationMs = 2_000L
        val startedAt = System.currentTimeMillis()
        writer.submit(GateAction.OpenClip(startedAt))
        feedTone(writer, durationMs)
        writer.submit(
            GateAction.CloseClip(
                ClipStats(
                    startedAtMs = startedAt,
                    durationMs = durationMs,
                    voicedMs = durationMs,
                    peakDb = -3f,
                    meanDb = -6f,
                    segments = 1,
                ),
            ),
        )
        writer.close()

        assertTrue("Błędy zapisu: $errors", errors.isEmpty())
        assertEquals(1, finished.size)

        val clip = finished.single()
        assertTrue("Plik nie powstał", clip.file.isFile)
        assertTrue("Plik jest pusty", clip.file.length() > 1_000)

        val declaredUs = declaredDurationUs(clip.file)
        assertNotNull("MediaExtractor nie znalazł ścieżki audio", declaredUs)
        val declaredMs = declaredUs!! / 1000
        assertTrue(
            "Zadeklarowana długość $declaredMs ms odbiega od zapisanych $durationMs ms",
            abs(declaredMs - durationMs) < 250,
        )

        val peaks = Waveform.read(clip.file)
        assertNotNull("Brak pliku .peaks obok klipu", peaks)
        val expectedBuckets = (durationMs / Waveform.BUCKET_MS).toInt()
        assertTrue(
            "Obwiednia ma ${peaks!!.size} kubełków, oczekiwano ~$expectedBuckets",
            abs(peaks.size - expectedBuckets) <= 2,
        )
        assertTrue("Obwiednia jest płaska — peaki nie są liczone", peaks.any { (it.toInt() and 0xFF) > 100 })
    }

    @Test
    fun odrzucony_klip_nie_zostawia_plikow() = runBlocking {
        val writer = ClipWriter(clipsDir, sampleRate, callbacks = callbacks)
        writer.start(scope)

        val startedAt = System.currentTimeMillis()
        writer.submit(GateAction.OpenClip(startedAt))
        feedTone(writer, 300)
        writer.submit(
            GateAction.DiscardClip(
                DiscardReason.TOO_SHORT,
                ClipStats(startedAt, 300, 60, -20f, -30f, 1),
            ),
        )
        writer.close()

        assertEquals(listOf(DiscardReason.TOO_SHORT), discarded)
        assertEquals(0, finished.size)
        val leftovers = clipsDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("Po odrzuceniu zostały pliki: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun kolejne_klipy_traf_iaja_do_osobnych_plikow() = runBlocking {
        val writer = ClipWriter(clipsDir, sampleRate, callbacks = callbacks)
        writer.start(scope)

        repeat(2) {
            val startedAt = System.currentTimeMillis()
            writer.submit(GateAction.OpenClip(startedAt))
            feedTone(writer, 500)
            writer.submit(
                GateAction.CloseClip(ClipStats(startedAt, 500, 500, -3f, -6f, 1)),
            )
        }
        writer.close()

        assertEquals(2, finished.size)
        assertFalse(
            "Oba klipy wylądowały w tym samym pliku",
            finished[0].file.absolutePath == finished[1].file.absolutePath,
        )
    }

    private suspend fun feedTone(writer: ClipWriter, durationMs: Long) {
        val frameSamples = 320
        val frames = (durationMs * sampleRate / 1000 / frameSamples).toInt()
        var phase = 0.0
        repeat(frames) {
            val chunk = ShortArray(frameSamples) { i ->
                (24_000 * sin(2 * PI * 440.0 * (phase + i) / sampleRate)).toInt().toShort()
            }
            phase += frameSamples
            writer.submit(GateAction.Write(chunk))
        }
    }

    private fun declaredDurationUs(file: File): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
        } finally {
            extractor.release()
        }
    }
}
