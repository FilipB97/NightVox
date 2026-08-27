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

    @Query("SELECT * FROM clips WHERE isDiscarded = 0 ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE isDiscarded = 0 AND isFavorite = 1 ORDER BY startedAt DESC")
    fun observeFavorites(): Flow<List<ClipEntity>>

    /** Kosz: co bramka wyrzuciła. Do przeglądania przy strojeniu progów. */
    @Query("SELECT * FROM clips WHERE isDiscarded = 1 ORDER BY startedAt DESC")
    fun observeDiscarded(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE sessionId = :sessionId AND isDiscarded = 0 ORDER BY startedAt ASC")
    fun observeBySession(sessionId: String): Flow<List<ClipEntity>>

    /** Cała noc, razem z odrzuconymi — przegląd sesji ma pokazywać też to, co filtr wyciął. */
    @Query("SELECT * FROM clips WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    fun observeBySessionIncludingDiscarded(sessionId: String): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    suspend fun bySession(sessionId: String): List<ClipEntity>

    @Query("UPDATE clips SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    /** Przywraca odrzucony klip do zwykłej listy — gdy okaże się, że filtr wyciął mowę. */
    @Query("UPDATE clips SET isDiscarded = 0, discardReason = NULL WHERE id = :id")
    suspend fun restore(id: String)

    /** Odwrotność [restore]: ręczne przeniesienie do kosza, bez kasowania pliku. */
    @Query("UPDATE clips SET isDiscarded = 1, discardReason = :reason WHERE id = :id")
    suspend fun discard(id: String, reason: String)

    @Query("UPDATE clips SET transcript = :transcript WHERE id = :id")
    suspend fun setTranscript(id: String, transcript: String?)

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Kandydaci do retencji. Ulubione nigdy nie są kasowane (§5); odrzucone mają własny,
     * krótszy termin — kosz ma służyć do strojenia, nie rosnąć bez końca.
     */
    @Query("SELECT * FROM clips WHERE startedAt < :olderThan AND isFavorite = 0 AND isDiscarded = 0")
    suspend fun expired(olderThan: Long): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE startedAt < :olderThan AND isFavorite = 0 AND isDiscarded = 1")
    suspend fun expiredDiscarded(olderThan: Long): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE isDiscarded = 1")
    suspend fun allDiscarded(): List<ClipEntity>

    @Query("SELECT COUNT(*) FROM clips WHERE isDiscarded = 0")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM clips WHERE isDiscarded = 1")
    fun observeDiscardedCount(): Flow<Int>

    @Query("SELECT * FROM clips")
    suspend fun all(): List<ClipEntity>
}
