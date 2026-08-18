package pl.nightvox.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import pl.nightvox.audio.ClipStats
import pl.nightvox.data.db.ClipDao
import pl.nightvox.data.db.ClipEntity
import pl.nightvox.data.db.SessionDao
import pl.nightvox.data.db.SessionEntity
import pl.nightvox.data.db.SessionWithStats
import pl.nightvox.encode.Waveform
import java.io.File
import java.util.UUID

/**
 * Jedyne miejsce, w którym pliki klipów i wiersze w bazie zmieniają się razem.
 *
 * Kasowanie: **najpierw plik, potem wiersz** (§7). Odwrotna kolejność zostawia po crashu
 * osierocone pliki, których nikt już nie znajdzie; ta zostawia najwyżej wiersz bez pliku,
 * co widać i da się posprzątać.
 */
class ClipRepository(
    private val clipDao: ClipDao,
    private val sessionDao: SessionDao,
    private val clipsDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val clips: Flow<List<ClipEntity>> = clipDao.observeAll()
    val favorites: Flow<List<ClipEntity>> = clipDao.observeFavorites()
    val discarded: Flow<List<ClipEntity>> = clipDao.observeDiscarded()
    val sessions: Flow<List<SessionWithStats>> = sessionDao.observeAllWithStats()

    fun clipsOfSession(sessionId: String): Flow<List<ClipEntity>> = clipDao.observeBySession(sessionId)

    fun observeClip(id: String): Flow<ClipEntity?> = clipDao.observeById(id)

    fun observeSession(id: String): Flow<SessionEntity?> = sessionDao.observeById(id)

    suspend fun clip(id: String): ClipEntity? = clipDao.byId(id)

    suspend fun startSession(settings: NightVoxSettings, noiseFloorDb: Float): String {
        val id = UUID.randomUUID().toString()
        sessionDao.insert(
            SessionEntity(
                id = id,
                startedAt = System.currentTimeMillis(),
                endedAt = null,
                noiseFloorDb = noiseFloorDb,
                clipCount = 0,
                interruptions = 0,
                settingsSnapshot = json.encodeToString(NightVoxSettings.serializer(), settings),
            ),
        )
        return id
    }

    suspend fun updateSessionStats(sessionId: String, floorDb: Float, interruptions: Int) {
        sessionDao.updateStats(sessionId, floorDb, interruptions)
    }

    suspend fun endSession(sessionId: String, reason: String) {
        sessionDao.refreshClipCount(sessionId)
        sessionDao.markEnded(sessionId, System.currentTimeMillis(), reason)
    }

    suspend fun addClip(
        sessionId: String,
        file: File,
        stats: ClipStats,
        discardReason: String? = null,
        vadScore: Float? = null,
    ): ClipEntity {
        val entity = ClipEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            startedAt = stats.startedAtMs,
            durationMs = stats.durationMs,
            filePath = file.absolutePath,
            peakDb = stats.peakDb,
            meanDb = stats.meanDb,
            voicedMs = stats.voicedMs,
            vadScore = vadScore,
            isDiscarded = discardReason != null,
            discardReason = discardReason,
        )
        clipDao.insert(entity)
        sessionDao.refreshClipCount(sessionId)
        return entity
    }

    suspend fun setFavorite(clipId: String, favorite: Boolean) = clipDao.setFavorite(clipId, favorite)

    /** Przywraca odrzucony klip do zwykłej listy — gdy okaże się, że filtr wyciął mowę. */
    suspend fun restoreDiscarded(clip: ClipEntity) {
        clipDao.restore(clip.id)
        sessionDao.refreshClipCount(clip.sessionId)
    }

    suspend fun clearDiscarded(): Int {
        var deleted = 0
        for (clip in clipDao.allDiscarded()) {
            deleteClip(clip)
            if (clipDao.byId(clip.id) == null) deleted++
        }
        return deleted
    }

    suspend fun deleteClip(clip: ClipEntity) {
        val file = File(clip.filePath)
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Nie udało się skasować ${file.absolutePath}; wiersz zostaje")
            return
        }
        Waveform.delete(file)
        clipDao.delete(clip.id)
        sessionDao.refreshClipCount(clip.sessionId)
    }

    suspend fun deleteSession(sessionId: String) {
        clipDao.bySession(sessionId).forEach { deleteClip(it) }
        sessionDao.delete(sessionId)
    }

    /**
     * Sprząta po crashu w środku nocy (§6.6): sesje bez `endedAt`, które nie są bieżące,
     * dostają znacznik końca z ostatniego klipu, a wiersze bez pliku na dysku znikają.
     */
    suspend fun repairAfterCrash(activeSessionId: String?): RepairResult {
        var closedSessions = 0
        var removedRows = 0

        for (clip in clipDao.all()) {
            if (!File(clip.filePath).isFile) {
                clipDao.delete(clip.id)
                removedRows++
            }
        }

        for (session in sessionDao.orphaned()) {
            if (session.id == activeSessionId) continue
            val clips = clipDao.bySession(session.id)
            val endedAt = clips.maxOfOrNull { it.startedAt + it.durationMs } ?: session.startedAt
            sessionDao.refreshClipCount(session.id)
            sessionDao.markEnded(session.id, endedAt, END_REASON_CRASH)
            closedSessions++
        }

        val removedSessions = sessionDao.deleteEmptyFinishedSessions()
        return RepairResult(closedSessions, removedRows, removedSessions)
    }

    /** Pliki bez wiersza w bazie — np. po skasowaniu bazy albo przerwanym zapisie. */
    suspend fun orphanedFiles(): List<File> {
        val known = clipDao.all().mapTo(HashSet()) { it.filePath }
        return clipsDir.walkTopDown()
            .filter { it.isFile && it.extension == "m4a" }
            .filterNot { known.contains(it.absolutePath) }
            .toList()
    }

    /**
     * Kosz „Odrzucone” ma własny, krótszy termin: służy do strojenia progów, więc trzymanie
     * go przez 30 dni jak zwykłych nagrań byłoby zbieractwem.
     */
    suspend fun applyRetention(
        retentionDays: Int,
        discardedRetentionDays: Int = retentionDays,
        now: Long = System.currentTimeMillis(),
    ): Int {
        var deleted = 0

        if (retentionDays > NightVoxSettings.RETENTION_NEVER) {
            deleted += deleteAll(clipDao.expired(now - retentionDays * DAY_MS))
        }
        if (discardedRetentionDays > NightVoxSettings.RETENTION_NEVER) {
            deleted += deleteAll(clipDao.expiredDiscarded(now - discardedRetentionDays * DAY_MS))
        }
        if (deleted > 0) sessionDao.deleteEmptyFinishedSessions()
        return deleted
    }

    private suspend fun deleteAll(clips: List<ClipEntity>): Int {
        var deleted = 0
        for (clip in clips) {
            deleteClip(clip)
            if (clipDao.byId(clip.id) == null) deleted++
        }
        return deleted
    }

    fun decodeSettings(snapshot: String): NightVoxSettings? =
        runCatching { json.decodeFromString(NightVoxSettings.serializer(), snapshot) }.getOrNull()

    data class RepairResult(
        val closedSessions: Int,
        val removedClipRows: Int,
        val removedEmptySessions: Int,
    ) {
        val isEmpty: Boolean get() = closedSessions == 0 && removedClipRows == 0 && removedEmptySessions == 0
    }

    companion object {
        private const val TAG = "NightVox/Repo"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val END_REASON_CRASH = "crash"
        const val END_REASON_USER = "user"
        const val END_REASON_AUTO_STOP = "auto_stop"
        const val END_REASON_NO_SPACE = "no_space"
        const val END_REASON_ERROR = "error"

        /** Bramka RMS przepuściła, ale analiza widmowa nie uznała tego za mowę. */
        const val DISCARD_REASON_NOT_SPEECH = "NOT_SPEECH"

        /** Ten sam powód sprzed wycofania Silero — zostaje, bo takie wiersze są w bazie. */
        const val DISCARD_REASON_LOW_VAD = "LOW_VAD"
    }
}
