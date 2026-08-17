package pl.nightvox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.nightvox.R
import pl.nightvox.ui.MainActivity
import pl.nightvox.util.Format

/** Kanały i budowa notyfikacji FGS. */
class NotificationHelper(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val recording = NotificationChannel(
            CHANNEL_RECORDING,
            context.getString(R.string.notif_channel_recording_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_recording_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notif_channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_desc)
        }
        manager.createNotificationChannel(recording)
        manager.createNotificationChannel(alerts)
    }

    fun buildRecordingNotification(state: RecorderState): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_nightvox)
            .setContentTitle(context.getString(R.string.notif_recording_title))
            .setContentText(statusLine(state))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    fun updateRecordingNotification(state: RecorderState) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        runCatching { manager.notify(NOTIFICATION_ID, buildRecordingNotification(state)) }
    }

    fun postAlert(title: String, text: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_nightvox)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    2,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        runCatching { manager.notify(ALERT_ID, notification) }
    }

    private fun statusLine(state: RecorderState): String {
        val parts = mutableListOf<String>()
        when {
            state.isSilenced -> parts += "⚠ mikrofon wyciszony przez system"
            state.isWarmingUp -> parts += "pomiar tła…"
            state.isRecordingClip -> parts += "● nagrywa"
            else -> parts += "nasłuchuje"
        }
        if (state.startedAtMs > 0) {
            parts += Format.duration(System.currentTimeMillis() - state.startedAtMs)
        }
        parts += "${state.clipCount} klip."
        return parts.joinToString(" · ")
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIFICATION_ID = 1001
        const val ALERT_ID = 1002
    }
}
