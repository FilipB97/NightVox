package pl.nightvox.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pl.nightvox.NightVoxApp
import java.util.concurrent.TimeUnit

/**
 * Kasuje klipy starsze niż `retentionDays`, a odrzucone — starsze niż
 * `discardedRetentionDays`. Ulubione są nietykalne (§5).
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
            val deleted = container.clipRepository.applyRetention(
                retentionDays = settings.retentionDays,
                discardedRetentionDays = settings.discardedRetentionDays,
            )
            if (deleted > 0) {
                container.diagnostics.log(
                    "retention",
                    "skasowano $deleted klipów (zwykłe: ${settings.retentionDays} dni, " +
                        "odrzucone: ${settings.discardedRetentionDays} dni)",
                )
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "nightvox-retention"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            // Kosz „Odrzucone” ma własny termin, więc worker jest potrzebny nawet przy
            // wyłączonej retencji zwykłych klipów.
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
