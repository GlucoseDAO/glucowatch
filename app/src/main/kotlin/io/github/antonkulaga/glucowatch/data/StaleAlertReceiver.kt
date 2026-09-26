package io.github.antonkulaga.glucowatch.data

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import io.github.antonkulaga.glucowatch.R
import io.github.antonkulaga.glucowatch.ui.MainActivity

/** Warns once per reading when it becomes old, even if the next network fetch is delayed. */
class StaleAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = GlucoseRepository(context).state()
        RefreshReceiver.updateComplications(context)
        schedule(context, state)
    }

    companion object {
        private const val NOTIFICATION_ID = 10
        private const val VIBRATE_CHANNEL = "stale_vibrate"
        private const val SOUND_CHANNEL = "stale_sound"
        private const val ALERTED_KEY = "lastAlertedReading"

        fun schedule(context: Context, state: GlucoseState) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(context, StaleAlertReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarms.cancel(pending)
            val manager = context.getSystemService(NotificationManager::class.java)
            val latest = state.latest
            if (state.settings.source == DataSource.DEMO || latest == null) {
                manager.cancel(NOTIFICATION_ID)
                return
            }
            if (state.isStale()) {
                if (state.settings.staleAlert == StaleAlert.OFF) manager.cancel(NOTIFICATION_ID)
                else notifyOnce(context, state, manager)
                return
            }
            manager.cancel(NOTIFICATION_ID)
            val delay = (latest.timeMillis + GlucoseState.STALE_AFTER_MS + 1 - System.currentTimeMillis()).coerceAtLeast(1)
            val at = SystemClock.elapsedRealtime() + delay
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        }

        private fun notifyOnce(context: Context, state: GlucoseState, manager: NotificationManager) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val latest = state.latest ?: return
            val key = "${state.settings.accountKey}:${latest.timeMillis}"
            val prefs = context.getSharedPreferences("stale-alert", Context.MODE_PRIVATE)
            if (prefs.getString(ALERTED_KEY, null) == key) return
            val channel = if (state.settings.staleAlert == StaleAlert.SOUND) SOUND_CHANNEL else VIBRATE_CHANNEL
            manager.createNotificationChannel(NotificationChannel(VIBRATE_CHANNEL, "Old glucose reading (vibrate)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warn when the latest glucose reading is more than 10 minutes old"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 250, 300)
            })
            manager.createNotificationChannel(NotificationChannel(SOUND_CHANNEL, "Old glucose reading (sound)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warn with sound when the latest glucose reading is more than 10 minutes old"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 250, 300)
            })
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(NOTIFICATION_ID, Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Glucose reading is old")
                .setContentText("Last reading was ${state.ageMinutes()} min ago. Check the source app.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build())
            prefs.edit().putString(ALERTED_KEY, key).apply()
        }
    }
}
