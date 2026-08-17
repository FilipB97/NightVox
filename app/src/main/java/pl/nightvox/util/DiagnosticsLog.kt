package pl.nightvox.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rotowany log diagnostyczny w `cacheDir` (§11): przejścia bramki, tło, przerwania.
 *
 * Po pierwszej nocy to jest jedyne źródło, z którego da się wyczytać, dlaczego progi są
 * złe. Zapis jest zbuforowany i leci na wątku wołającego — wywołania są rzadkie
 * (przejścia stanów, nie ramki).
 */
class DiagnosticsLog(context: Context) {

    private val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
    private val current = File(dir, "nightvox.log")
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var enabled: Boolean = true

    @Synchronized
    fun log(tag: String, message: String) {
        if (!enabled) return
        try {
            rotateIfNeeded()
            current.appendText("${stamp.format(Date())} [$tag] $message\n")
        } catch (e: IOException) {
            Log.w(TAG, "Nie udało się dopisać do logu diagnostycznego", e)
        }
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
        val parts = files().sortedBy { it.lastModified() }
        if (parts.isEmpty()) return null
        val out = File(dir, "nightvox-diagnostics.txt")
        return runCatching {
            out.delete()
            parts.filter { it.name != out.name }.forEach { out.appendBytes(it.readBytes()) }
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
    }
}
