package pl.nightvox.data

import pl.nightvox.data.db.ClipEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Klipy nocy jako CSV — jeden plik, który da się otworzyć w arkuszu i posortować po ocenie.
 *
 * Po nocy jedyne sensowne pytanie o strojenie brzmi „jakie oceny dostały klipy, które
 * okazały się mową, a jakie te z oddechem”. Przeklikanie stu ekranów tego nie odpowiada,
 * a jedna tabelka tak. Format jest celowo nudny: przecinek, kropka dziesiętna, `Locale.US`,
 * żeby nie zależał od ustawień telefonu.
 */
object ClipCsv {

    val HEADER = listOf(
        "czas",
        "plik",
        "dlugosc_ms",
        "nad_progiem_ms",
        "szczyt_db",
        "srednia_db",
        "ocena_mowy",
        "odrzucony",
        "powod",
        "ulubiony",
    )

    fun build(clips: List<ClipEntity>): String = buildString {
        appendLine(HEADER.joinToString(","))
        for (clip in clips.sortedBy { it.startedAt }) appendLine(row(clip))
    }

    fun row(clip: ClipEntity): String = listOf(
        TIMESTAMP.format(Date(clip.startedAt)),
        File(clip.filePath).name,
        clip.durationMs.toString(),
        clip.voicedMs.toString(),
        decimal(clip.peakDb),
        decimal(clip.meanDb),
        clip.vadScore?.let { String.format(Locale.US, "%.3f", it) } ?: "",
        if (clip.isDiscarded) "tak" else "nie",
        clip.discardReason ?: "",
        if (clip.isFavorite) "tak" else "nie",
    ).joinToString(",") { escape(it) }

    /**
     * Znacznik czasu celowo w formacie ISO-podobnym, a nie tym z UI: sortuje się
     * leksykograficznie, nie zawiera przecinka i znaczy to samo w każdym arkuszu.
     */
    private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun decimal(value: Float): String = String.format(Locale.US, "%.1f", value)

    /** Nazwy plików i powody są nasze, ale ucieczka kosztuje linijkę i chroni przed niespodzianką. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
