package pl.nightvox.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings

/** Stan otoczenia, który decyduje o tym, czy sesja dożyje rana. */
data class EnvironmentStatus(
    val ignoringBatteryOptimizations: Boolean,
    val isCharging: Boolean,
    val freeBytes: Long,
) {
    val lowStorage: Boolean get() = freeBytes in 0 until LOW_STORAGE_BYTES

    companion object {
        const val LOW_STORAGE_BYTES = 500L * 1024 * 1024
    }
}

object SystemChecks {

    fun environment(context: Context, storageDir: java.io.File): EnvironmentStatus =
        EnvironmentStatus(
            ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context),
            isCharging = isCharging(context),
            freeBytes = freeBytes(storageDir),
        )

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    /**
     * Prośba o zwolnienie z optymalizacji baterii. To sideload do własnego użytku, więc
     * restrykcje Play Store nie obowiązują — ale zostaje opcjonalnym promptem, nie blokadą.
     */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun isCharging(context: Context): Boolean {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return false
        return manager.isCharging
    }

    fun freeBytes(dir: java.io.File): Long = runCatching {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(-1L)

    /** Producenci, którzy najchętniej ubijają długie serwisy — do sekcji „Rozwiązywanie problemów”. */
    val dontKillMyAppUrl: String = "https://dontkillmyapp.com"
}
