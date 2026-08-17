package pl.nightvox.service

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.nightvox.NightVoxApp

/**
 * Cykl życia serwisu (§10).
 *
 * Uwaga: od API 34 FGS typu `microphone` nie wystartuje, gdy żadne Activity nie jest
 * widoczne — dlatego test nie zakłada, że sesja *musi* ruszyć. Sprawdza kontrakt, który
 * obowiązuje w obie strony: albo serwis wstaje i publikuje stan, albo odmawia i zostawia
 * `RecorderStateHolder` w spójnym stanie z komunikatem błędu. Zawieszony serwis
 * bez żadnego z tych dwóch to błąd.
 */
@RunWith(AndroidJUnit4::class)
class RecorderServiceLifecycleTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        RecorderStateHolder.reset()
    }

    @After
    fun tearDown() {
        context.startService(Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        Thread.sleep(1_500)
        RecorderStateHolder.reset()
    }

    @Test
    fun kanaly_notyfikacji_sa_tworzone() {
        NotificationHelper(context).ensureChannels()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.notificationChannels.any { it.id == NotificationHelper.CHANNEL_RECORDING })
        assertTrue(manager.notificationChannels.any { it.id == NotificationHelper.CHANNEL_ALERTS })
    }

    @Test
    fun start_publikuje_stan_albo_czytelny_blad() {
        RecorderService.start(context)
        Thread.sleep(3_000)

        val state = RecorderStateHolder.state.value
        if (state.isRunning) {
            assertTrue("Sesja działa, ale nie ma identyfikatora", state.sessionId != null || state.startedAtMs > 0)
        } else {
            assertTrue(
                "Serwis nie wystartował i nie powiedział dlaczego",
                state.lastError != null,
            )
        }
    }

    @Test
    fun stop_konczy_sesje_i_zeruje_stan() {
        RecorderService.start(context)
        Thread.sleep(3_000)
        val started = RecorderStateHolder.state.value.isRunning

        RecorderService.stop(context)
        Thread.sleep(3_000)

        val state = RecorderStateHolder.state.value
        assertFalse("Stan nadal twierdzi, że sesja trwa", state.isRunning)

        if (started) {
            // Sesja, która wystartowała, musi zostać domknięta w bazie — bez `endedAt`
            // zostałaby uznana za osieroconą przy następnym starcie apki.
            val app = context.applicationContext as NightVoxApp
            val orphaned = runBlocking { app.container.database.sessionDao().orphaned() }
            assertEquals("Sesja została bez endedAt", 0, orphaned.size)
        }
    }
}
