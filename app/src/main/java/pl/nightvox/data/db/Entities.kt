package pl.nightvox.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val noiseFloorDb: Float,
    val clipCount: Int,
    val interruptions: Int,
    /** JSON parametrów, przy jakich powstała sesja — bez tego strojenie progów to zgadywanka. */
    val settingsSnapshot: String,
    /** Powód zakończenia: użytkownik, auto-stop, brak miejsca, awaria. */
    val endReason: String? = null,
)

@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "startedAt"])],
)
data class ClipEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val startedAt: Long,
    val durationMs: Long,
    val filePath: String,
    val peakDb: Float,
    val meanDb: Float,
    val voicedMs: Long,
    /** Faza 3 — Silero VAD. */
    val vadScore: Float? = null,
    /** Faza 3 — whisper.cpp. */
    val transcript: String? = null,
    val isFavorite: Boolean = false,
)

/** Sesja z policzonymi klipami — dla listy nocy, żeby nie robić N+1 zapytań. */
data class SessionWithStats(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val noiseFloorDb: Float,
    val interruptions: Int,
    val endReason: String?,
    val clipCount: Int,
    val totalVoicedMs: Long,
    val totalDurationMs: Long,
)
