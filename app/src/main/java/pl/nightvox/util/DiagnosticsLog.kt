package pl.nightvox.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Rotowany log diagnostyczny w `cacheDir` (§11): przejścia bramki, tło, przerwania.
 *
 * Po pierwszej nocy to jedyne źródło, z którego da się wyczytać, dlaczego progi są złe.
 *
 * [log] jest wołane m.in. z wątku pipeline'u audio, więc **nie dotyka dysku synchronicznie** —
 * wpis idzie do kolejki, a zapisuje go osobny wątek. Zablokowanie pipeline'u na `fsync`
 * przy przepełnionym storage kosztowałoby zgubione ramki.
 */
class DiagnosticsLog(context: Context) {

    private val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
    private val current = File(dir, "nightvox.log")
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val writer = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "nightvox-diag").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        // Kolejka pełna = dysk nie nadąża. Log diagnostyczny nigdy nie może zatrzymać
        // producenta, więc najstarszy wpis po prostu przepada.
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    @Volatile
    var enabled: Boolean = true

    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = "${stamp.format(Date())} [$tag] $message\n"
        runCatching { writer.execute { append(line) } }
    }

    @Synchronized
    private fun append(line: String) {
        try {
            rotateIfNeeded()
            current.appendText(line)
        } catch (e: IOException) {
            Log.w(TAG, "Nie udało się dopisać do logu diagnostycznego", e)
        }
    }

    /** Czeka, aż zaległe wpisy trafią na dysk. Wołane przed odczytem albo udostępnieniem. */
    fun flush(timeoutMs: Long = 2_000) {
        val done = java.util.concurrent.CountDownLatch(1)
        runCatching { writer.execute { done.countDown() } }
            .onFailure { return }
        runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    fun files(): List<File> = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun totalBytes(): Long = files().sumOf { it.length() }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Zwraca plik gotowy do udostępnienia (scalone rotacje, najstarsze pierwsze). */
    @Synchronized
    fun snapshotForSharing(): File? {
        val out = File(dir, SHARE_FILE_NAME)
        val parts = files().filter { it.name != SHARE_FILE_NAME }.sortedBy { it.lastModified() }
        if (parts.isEmpty()) return null
        return runCatching {
            out.delete()
            parts.forEach { out.appendBytes(it.readBytes()) }
            out.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    private fun rotateIfNeeded() {
        if (current.length() < MAX_BYTES) return
        val previous = File(dir, "nightvox.log.1")
        previous.delete()
        current.renameTo(previous)
    }

    companion object {
        private const val TAG = "NightVox/Diag"
        private const val MAX_BYTES = 2L * 1024 * 1024
        private const val QUEUE_CAPACITY = 2_048
        private const val SHARE_FILE_NAME = "nightvox-diagnostics.txt"
    }
}
