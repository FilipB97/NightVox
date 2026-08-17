package pl.nightvox.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zapisuje niewyłapane wyjątki do logu diagnostycznego, zanim proces umrze.
 *
 * Bez tego jedyną informacją zwrotną z telefonu jest „apka się wywala", co nie pozwala
 * naprawić niczego. Sideload nie ma Play Console ani Crashlytics (i nie powinien mieć —
 * apka nie ma dostępu do sieci), więc stack trace musi wylądować w pliku, który da się
 * udostępnić z Ustawień.
 *
 * Handler **nie** zjada wyjątku: po zapisaniu przekazuje go dalej, żeby system zachował
 * się normalnie (ANR dialog, restart), a nie żeby apka udawała, że nic się nie stało.
 */
class CrashReporter(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("=== CRASH ${STAMP.format(Date())} ===")
            appendLine("wątek: ${thread.name}")
            appendLine("urządzenie: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine(stack)
        }
        // Log diagnostyczny bywa wyłączony w Ustawieniach, ale crash zapisujemy zawsze —
        // to jedyny moment, w którym ta informacja istnieje.
        diagnostics.logAlwaysBlocking("crash", report)
        markerFile(context).writeText(report)
    }

    companion object {
        private const val MARKER_NAME = "last-crash.txt"
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun markerFile(context: Context): File = File(context.cacheDir, MARKER_NAME)

        /** Pierwsza linia ostatniego crasha albo `null`. Do pokazania karty w UI. */
        fun lastCrashSummary(context: Context): String? {
            val file = markerFile(context)
            if (!file.isFile) return null
            return runCatching {
                val lines = file.readLines()
                val when0 = lines.firstOrNull()?.removePrefix("=== CRASH ")?.removeSuffix(" ===")
                val cause = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }
                listOfNotNull(when0, cause?.trim()).joinToString(" · ").ifBlank { null }
            }.getOrNull()
        }

        fun clear(context: Context) {
            markerFile(context).delete()
        }
    }
}
