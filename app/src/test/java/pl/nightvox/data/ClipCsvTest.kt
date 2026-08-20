package pl.nightvox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.nightvox.data.db.ClipEntity

class ClipCsvTest {

    private fun clip(
        id: String = "a",
        startedAt: Long = 1_700_000_000_000,
        score: Float? = 0.4567f,
        discarded: Boolean = false,
        path: String = "/data/clips/2026-08-18/013755.m4a",
    ) = ClipEntity(
        id = id,
        sessionId = "s",
        startedAt = startedAt,
        durationMs = 13_400,
        filePath = path,
        peakDb = -17.14f,
        meanDb = -31.02f,
        voicedMs = 2_780,
        vadScore = score,
        isDiscarded = discarded,
        discardReason = if (discarded) "NOT_SPEECH" else null,
    )

    @Test
    fun naglowek_i_wiersz_maja_tyle_samo_kolumn() {
        assertEquals(ClipCsv.HEADER.size, parse(ClipCsv.row(clip())).size)
    }

    /** Kropka dziesiętna, nie przecinek — inaczej CSV rozjeżdża się na polskim telefonie. */
    @Test
    fun liczby_uzywaja_kropki_dziesietnej() {
        val row = ClipCsv.row(clip())
        assertTrue(row, row.contains("-17.1"))
        assertTrue(row, row.contains("0.457"))
    }

    @Test
    fun brak_oceny_zostawia_puste_pole_a_nie_zero() {
        val cells = parse(ClipCsv.row(clip(score = null)))
        assertEquals("", cells[ClipCsv.HEADER.indexOf("ocena_mowy")])
    }

    @Test
    fun odrzucony_klip_niesie_powod() {
        val cells = parse(ClipCsv.row(clip(discarded = true)))
        assertEquals("tak", cells[ClipCsv.HEADER.indexOf("odrzucony")])
        assertEquals("NOT_SPEECH", cells[ClipCsv.HEADER.indexOf("powod")])
    }

    @Test
    fun przecinek_w_nazwie_pliku_jest_cytowany() {
        val row = ClipCsv.row(clip(path = "/data/clips/dziwna,nazwa.m4a"))
        assertTrue(row, row.contains("\"dziwna,nazwa.m4a\""))
        assertEquals(ClipCsv.HEADER.size, parse(row).size)
        assertEquals("dziwna,nazwa.m4a", parse(row)[ClipCsv.HEADER.indexOf("plik")])
    }

    @Test
    fun czas_jest_sortowalny_i_bez_przecinka() {
        val cells = parse(ClipCsv.row(clip()))
        assertTrue(cells[0], cells[0].matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }

    /** Minimalny parser CSV — test nie ma prawa zakładać, że w polach nie ma przecinków. */
    private fun parse(row: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                quoted && c == '"' && row.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { cells += cell.toString(); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        cells += cell.toString()
        return cells
    }

    @Test
    fun tabela_zaczyna_sie_naglowkiem_i_sortuje_po_czasie() {
        val csv = ClipCsv.build(
            listOf(
                clip(id = "drugi", startedAt = 2_000),
                clip(id = "pierwszy", startedAt = 1_000, path = "/x/pierwszy.m4a"),
            ),
        )
        val lines = csv.trim().lines()
        assertEquals(ClipCsv.HEADER.joinToString(","), lines[0])
        assertTrue(lines[1], lines[1].contains("pierwszy.m4a"))
        assertEquals(3, lines.size)
    }
}
