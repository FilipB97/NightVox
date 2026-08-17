package pl.nightvox

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import pl.nightvox.data.ClipRepository
import pl.nightvox.data.SettingsStore
import pl.nightvox.data.db.NightVoxDatabase
import pl.nightvox.util.DiagnosticsLog
import java.io.File

/**
 * Ręczny graf zależności. Bez Hilta — przy tej liczbie obiektów framework DI dokłada
 * więcej ceremonii niż oszczędza (§1 planu). Jeśli graf urośnie, wtedy Hilt.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    val database: NightVoxDatabase by lazy { NightVoxDatabase.build(appContext) }

    /** Klipy w `filesDir` — app-private, zero uprawnień do storage. */
    val clipsDir: File by lazy { File(appContext.filesDir, "clips").apply { mkdirs() } }

    val debugDir: File by lazy { File(appContext.filesDir, "debug").apply { mkdirs() } }

    val exportsDir: File by lazy { File(appContext.cacheDir, "exports").apply { mkdirs() } }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val diagnostics: DiagnosticsLog by lazy { DiagnosticsLog(appContext) }

    val clipRepository: ClipRepository by lazy {
        ClipRepository(database.clipDao(), database.sessionDao(), clipsDir)
    }
}
