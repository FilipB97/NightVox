package pl.nightvox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clip: ClipEntity)

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun byId(id: String): ClipEntity?

    @Query("SELECT * FROM clips WHERE id = :id")
    fun observeById(id: String): Flow<ClipEntity?>

    @Query("SELECT * FROM clips ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE isFavorite = 1 ORDER BY startedAt DESC")
    fun observeFavorites(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    fun observeBySession(sessionId: String): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    suspend fun bySession(sessionId: String): List<ClipEntity>

    @Query("UPDATE clips SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE clips SET transcript = :transcript WHERE id = :id")
    suspend fun setTranscript(id: String, transcript: String?)

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun delete(id: String)

    /** Kandydaci do retencji — ulubione nigdy nie kasowane (§5). */
    @Query("SELECT * FROM clips WHERE startedAt < :olderThan AND isFavorite = 0")
    suspend fun expired(olderThan: Long): List<ClipEntity>

    @Query("SELECT COUNT(*) FROM clips")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM clips")
    suspend fun all(): List<ClipEntity>
}
