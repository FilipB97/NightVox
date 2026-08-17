package pl.nightvox.data

import pl.nightvox.data.db.ClipEntity
import pl.nightvox.data.db.SessionEntity
import pl.nightvox.util.Format
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Eksport nocy do ZIP-a: klipy + czytelne podsumowanie z parametrami, przy jakich powstały.
 * Bez podsumowania archiwum jest bezużyteczne za trzy miesiące.
 */
class SessionExporter(
    private val exportsDir: File,
    private val repository: ClipRepository,
) {
    fun export(session: SessionEntity, clips: List<ClipEntity>): File {
        exportsDir.mkdirs()
        exportsDir.listFiles()?.forEach { it.delete() }

        val name = "nightvox-${Format.shortTime(session.startedAt).replace(":", "")}-" +
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(session.startedAt))
        val zipFile = File(exportsDir, "$name.zip")

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("podsumowanie.txt"))
            zip.write(summary(session, clips).toByteArray())
            zip.closeEntry()

            for (clip in clips) {
                val file = File(clip.filePath)
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry("klipy/${file.name}"))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return zipFile
    }

    private fun summary(session: SessionEntity, clips: List<ClipEntity>): String = buildString {
        appendLine("NightVox — sesja ${Format.date(session.startedAt)}")
        appendLine("Start:        ${Format.dateTime(session.startedAt)}")
        appendLine("Koniec:       ${session.endedAt?.let { Format.dateTime(it) } ?: "—"}")
        session.endedAt?.let { appendLine("Długość:      ${Format.duration(it - session.startedAt)}") }
        appendLine("Tło szumu:    ${Format.db(session.noiseFloorDb)}")
        appendLine("Przerwania:   ${session.interruptions}")
        appendLine("Powód końca:  ${session.endReason ?: "—"}")
        appendLine("Klipy:        ${clips.size}")
        appendLine()

        repository.decodeSettings(session.settingsSnapshot)?.let { s ->
            appendLine("Parametry bramki użyte tej nocy:")
            appendLine("  triggerDeltaDb  ${s.triggerDeltaDb}")
            appendLine("  attackFrames    ${s.attackFrames}")
            appendLine("  preRollMs       ${s.preRollMs}")
            appendLine("  hangoverMs      ${s.hangoverMs}")
            appendLine("  mergeGapMs      ${s.mergeGapMs}")
            appendLine("  minVoicedMs     ${s.minVoicedMs}")
            appendLine("  maxClipMs       ${s.maxClipMs}")
            appendLine()
        }

        appendLine("Klipy:")
        clips.forEach { clip ->
            appendLine(
                "  ${Format.time(clip.startedAt)}  ${Format.clipDuration(clip.durationMs).padEnd(8)}" +
                    "peak ${Format.db(clip.peakDb).padEnd(10)}mowa ${clip.voicedMs} ms" +
                    (if (clip.isFavorite) "  ★" else "") +
                    "  ${File(clip.filePath).name}",
            )
        }
    }
}
