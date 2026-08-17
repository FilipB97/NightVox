package pl.nightvox.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pl.nightvox.NightVoxApp
import pl.nightvox.data.NightVoxSettings
import java.util.concurrent.TimeUnit

/**
 * Kasuje klipy starsze niż `retentionDays`. Ulubione są nietykalne (§5).
 *
 * Chodzi raz na dobę, przy naładowanej baterii — kasowanie plików w środku nocy podczas
 * nagrywania nie ma sensu, a rano na ładowarce nikomu nie przeszkadza.
 */
class RetentionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NightVoxApp ?: return Result.success()
        val container = app.container
        return runCatching {
            val settings = container.settingsStore.current()
            val deleted = container.clipRepository.applyRetention(settings.retentionDays)
            if (deleted > 0) {
                container.diagnostics.log("retention", "skasowano $deleted klipów starszych niż ${settings.retentionDays} dni")
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "nightvox-retention"

        fun schedule(context: Context, retentionDays: Int) {
            val manager = WorkManager.getInstance(context)
            if (retentionDays <= NightVoxSettings.RETENTION_NEVER) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
