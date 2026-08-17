package pl.nightvox.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.nightvox.audio.ClipStats
import pl.nightvox.data.db.NightVoxDatabase
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClipRepositoryInstrumentedTest {

    private lateinit var database: NightVoxDatabase
    private lateinit var repository: ClipRepository
    private lateinit var clipsDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, NightVoxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clipsDir = File(context.cacheDir, "repo-test-clips").apply {
            deleteRecursively()
            mkdirs()
        }
        repository = ClipRepository(database.clipDao(), database.sessionDao(), clipsDir)
    }

    @After
    fun tearDown() {
        database.close()
        clipsDir.deleteRecursively()
    }

    @Test
    fun sesja_i_klipy_zapisuja_sie_i_odczytuja() = runBlocking {
        val sessionId = repository.startSession(NightVoxSettings.DEFAULTS, noiseFloorDb = -58f)
        val file = newClipFile("klip1")
        repository.addClip(sessionId, file, stats(startedAt = 1_000))

        val clips = repository.clipsOfSession(sessionId).first()
        assertEquals(1, clips.size)
        assertEquals(file.absolutePath, clips.single().filePath)

        val sessions = repository.sessions.first()
        assertEquals(1, sessions.size)
        assertEquals(1, sessions.single().clipCount)
    }

    @Test
    fun migawka_ustawien_daje_sie_odczytac() = runBlocking {
        val settings = NightVoxSettings.DEFAULTS.copy(triggerDeltaDb = 17f, hangoverMs = 6_000)
        val sessionId = repository.startSession(settings, noiseFloorDb = -55f)

        val stored = repository.observeSession(sessionId).first()
        assertNotNull(stored)
        val decoded = repository.decodeSettings(stored!!.settingsSnapshot)
        assertEquals(17f, decoded!!.triggerDeltaDb)
        assertEquals(6_000L, decoded.hangoverMs)
    }

    /** §7: plik znika przed wierszem, żeby nigdy nie zostać sierotą na dysku. */
    @Test
    fun kasowanie_usuwa_plik_i_wiersz() = runBlocking {
        val sessionId = repository.startSession(NightVoxSettings.DEFAULTS, -60f)
        val file = newClipFile("do-skasowania")
        val clip = repository.addClip(sessionId, file, stats())

        repository.deleteClip(clip)

        assertFalse("Plik przetrwał kasowanie", file.exists())
        assertNull(repository.clip(clip.id))
    }

    @Test
    fun retencja_nie_rusza_ulubionych() = runBlocking {
        val sessionId = repository.startSession(NightVoxSettings.DEFAULTS, -60f)
        val old = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000

        val expiring = repository.addClip(sessionId, newClipFile("stary"), stats(startedAt = old))
        val favorite = repository.addClip(sessionId, newClipFile("ulubiony"), stats(startedAt = old))
        repository.setFavorite(favorite.id, true)

        val deleted = repository.applyRetention(retentionDays = 30)

        assertEquals(1, deleted)
        assertNull(repository.clip(expiring.id))
        assertNotNull("Ulubiony klip został skasowany przez retencję", repository.clip(favorite.id))
    }

    @Test
    fun retencja_wylaczona_nie_kasuje_nic() = runBlocking {
        val sessionId = repository.startSession(NightVoxSettings.DEFAULTS, -60f)
        val old = System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000
        val clip = repository.addClip(sessionId, newClipFile("prastary"), stats(startedAt = old))

        val deleted = repository.applyRetention(NightVoxSettings.RETENTION_NEVER)

        assertEquals(0, deleted)
        assertNotNull(repository.clip(clip.id))
    }

    /** §6.6: po crashu w nocy zostaje sesja bez `endedAt` i wiersze bez plików. */
    @Test
    fun naprawa_po_crashu_domyka_sesje_i_czysci_wiersze() = runBlocking {
        val sessionId = repository.startSession(NightVoxSettings.DEFAULTS, -60f)
        val good = repository.addClip(sessionId, newClipFile("ocalaly"), stats(startedAt = 1_000))
        val ghostFile = newClipFile("znikniety")
        val ghost = repository.addClip(sessionId, ghostFile, stats(startedAt = 2_000))
        ghostFile.delete()

        val result = repository.repairAfterCrash(activeSessionId = null)

        assertEquals(1, result.removedClipRows)
        assertEquals(1, result.closedSessions)
        assertNull(repository.clip(ghost.id))
        assertNotNull(repository.clip(good.id))

        val session = repository.observeSession(sessionId).first()
        assertNotNull(session!!.endedAt)
        assertEquals(ClipRepository.END_REASON_CRASH, session.endReason)
    }

    @Test
    fun osierocone_pliki_sa_wykrywane() = runBlocking {
        val sessionId = repository.startSession(NightVoxSettings.DEFAULTS, -60f)
        repository.addClip(sessionId, newClipFile("znany"), stats())
        newClipFile("nieznany")

        val orphans = repository.orphanedFiles()

        assertEquals(1, orphans.size)
        assertTrue(orphans.single().name.startsWith("nieznany"))
    }

    private fun newClipFile(name: String): File =
        File(clipsDir, "$name.m4a").apply { writeBytes(ByteArray(2_048)) }

    private fun stats(startedAt: Long = System.currentTimeMillis()) = ClipStats(
        startedAtMs = startedAt,
        durationMs = 5_000,
        voicedMs = 1_200,
        peakDb = -18f,
        meanDb = -34f,
        segments = 1,
    )
}
