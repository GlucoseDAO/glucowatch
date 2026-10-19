package io.github.antonkulaga.glucowatch.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the active, user-started folder collection runnable while a portal owns the screen.
 * The activity owns the export and cancels this service with its watcher. No boot/background
 * restart: a reclaimed activity restores its private checkpoint when the user returns. */
class ExportDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ExportDiagnostics.event(this, "collector_service_created")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Export downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Collecting export downloads")
            .setContentText("Imports completed CSVs and opens the next selected source")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        try {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            ExportDiagnostics.event(this, "collector_foreground_started")
        } catch (e: Exception) {
            ExportDiagnostics.event(this, "collector_foreground_failed", error = e)
            stopSelf() // A system quota must not crash the export activity.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("workout", false) == true) {
            val open = PendingIntent.getActivity(this, 1, Intent(this, WorkoutImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            getSystemService(NotificationManager::class.java).notify(ID, Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Collecting workout download")
                .setContentText("Waiting for a completed FIT file in your selected folder")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build())
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    companion object {
        private const val CHANNEL = "export-downloads"
        private const val ID = 4203
        fun start(context: Context, workout: Boolean = false) = context.startForegroundService(Intent(context, ExportDownloadService::class.java).putExtra("workout", workout))
        fun stop(context: Context) { context.stopService(Intent(context, ExportDownloadService::class.java)) }
    }
}
