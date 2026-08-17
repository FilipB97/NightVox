package pl.nightvox

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.nightvox.service.RecorderStateHolder
import pl.nightvox.util.CrashReporter
import pl.nightvox.work.RetentionWorker

class NightVoxApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope by lazy { CoroutineScope(SupervisorJob() + container.ioDispatcher) }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Instalujemy jako pierwsze: crash w dowolnym miejscu ma zostawić ślad, który da
        // się udostępnić z Ustawień. Sideload nie ma Play Console, a apka celowo nie ma sieci.
        CrashReporter(this, container.diagnostics).install()

        appScope.launch {
            // Po crashu w środku nocy zostają sesje bez `endedAt` i wiersze bez plików (§6.6).
            runCatching {
                val active = RecorderStateHolder.state.value.sessionId
                val result = container.clipRepository.repairAfterCrash(active)
                if (!result.isEmpty) {
                    container.diagnostics.log(
                        "startup",
                        "naprawa: zamknięte=${result.closedSessions} " +
                            "usunięte_wiersze=${result.removedClipRows} " +
                            "puste_sesje=${result.removedEmptySessions}",
                    )
                }
            }.onFailure { Log.w(TAG, "Naprawa po crashu nie powiodła się", it) }

            runCatching {
                RetentionWorker.schedule(this@NightVoxApp)
            }.onFailure { Log.w(TAG, "Nie udało się zaplanować retencji", it) }
        }
    }

    companion object {
        private const val TAG = "NightVox/App"
    }
}
