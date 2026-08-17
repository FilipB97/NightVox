package pl.nightvox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    @Query(
        """
        SELECT s.id AS id, s.startedAt AS startedAt, s.endedAt AS endedAt,
               s.noiseFloorDb AS noiseFloorDb, s.interruptions AS interruptions,
               s.endReason AS endReason,
               COUNT(c.id) AS clipCount,
               COALESCE(SUM(c.voicedMs), 0) AS totalVoicedMs,
               COALESCE(SUM(c.durationMs), 0) AS totalDurationMs
        FROM sessions s LEFT JOIN clips c ON c.sessionId = s.id
        GROUP BY s.id
        ORDER BY s.startedAt DESC
        """,
    )
    fun observeAllWithStats(): Flow<List<SessionWithStats>>

    /** Sesje bez `endedAt` — osierocone po crashu w środku nocy (§6.6). */
    @Query("SELECT * FROM sessions WHERE endedAt IS NULL")
    suspend fun orphaned(): List<SessionEntity>

    @Query("UPDATE sessions SET clipCount = (SELECT COUNT(*) FROM clips WHERE sessionId = :id) WHERE id = :id")
    suspend fun refreshClipCount(id: String)

    @Query("UPDATE sessions SET endedAt = :endedAt, endReason = :reason WHERE id = :id")
    suspend fun markEnded(id: String, endedAt: Long, reason: String)

    @Query("UPDATE sessions SET noiseFloorDb = :floorDb, interruptions = :interruptions WHERE id = :id")
    suspend fun updateStats(id: String, floorDb: Float, interruptions: Int)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions WHERE endedAt IS NOT NULL AND (SELECT COUNT(*) FROM clips WHERE sessionId = sessions.id) = 0")
    suspend fun deleteEmptyFinishedSessions(): Int
}
