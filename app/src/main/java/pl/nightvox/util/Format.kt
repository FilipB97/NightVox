package pl.nightvox.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Formatowanie na potrzeby UI. Jedno miejsce, żeby daty wyglądały wszędzie tak samo. */
object Format {

    /**
     * Formattery są cache'owane per wątek **i per locale**: `SimpleDateFormat` nie jest
     * thread-safe, a trzymanie go w statycznym polu zamraża język z chwili startu procesu.
     */
    private val formatters = ThreadLocal.withInitial { HashMap<String, SimpleDateFormat>() }

    private fun formatter(pattern: String, locale: Locale): SimpleDateFormat {
        val cache = formatters.get()!!
        val key = "$pattern|$locale"
        return cache.getOrPut(key) { SimpleDateFormat(pattern, locale) }
    }

    private val polish = Locale.forLanguageTag("pl-PL")

    fun time(ms: Long): String = formatter("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    fun shortTime(ms: Long): String = formatter("HH:mm", Locale.getDefault()).format(Date(ms))

    fun date(ms: Long): String =
        formatter("EEEE, d MMMM", polish).format(Date(ms)).replaceFirstChar { it.uppercase() }

    fun dateTime(ms: Long): String = formatter("d MMM, HH:mm:ss", polish).format(Date(ms))

    /** `1:23:45` albo `4:05` — zależnie od długości. */
    fun duration(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val hours = TimeUnit.MILLISECONDS.toHours(total)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(total) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(total) % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Krótki czas trwania klipu: `2,4 s` / `1:04`. */
    fun clipDuration(ms: Long): String =
        if (ms < 60_000) String.format(Locale.getDefault(), "%.1f s", ms / 1000f) else duration(ms)

    fun db(value: Float): String = String.format(Locale.US, "%.1f dB", value)

    fun bytes(value: Long): String = when {
        value >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", value / (1024f * 1024 * 1024))
        value >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", value / (1024f * 1024))
        value >= 1024 -> String.format(Locale.getDefault(), "%.0f kB", value / 1024f)
        else -> "$value B"
    }

    /** Ocena mowy 0..1 — wszędzie tak samo, żeby dało się porównywać wzrokiem. */
    fun score(value: Float): String = String.format(Locale.US, "%.2f", value)
}
