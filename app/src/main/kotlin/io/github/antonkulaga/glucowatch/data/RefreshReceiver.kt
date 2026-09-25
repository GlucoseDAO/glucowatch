package io.github.antonkulaga.glucowatch.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import io.github.antonkulaga.glucowatch.complications.GlucoseChartComplicationService
import io.github.antonkulaga.glucowatch.complications.GlucoseValueComplicationService
import io.github.antonkulaga.glucowatch.complications.LoopComplicationService
import io.github.antonkulaga.glucowatch.complications.PredictionComplicationService
import io.github.antonkulaga.glucowatch.complications.TreatmentComplicationService
import io.github.antonkulaga.glucowatch.tile.GlucoseTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Alarm target: fetch, push complication updates, schedule the next run. Also re-arms after boot/update. */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                refreshNow(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GlucoRefresh"
        private const val INTERVAL_MS = 5 * 60_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Fetch now, update all complications and arm the next alarm. Safe to call from anywhere. */
        suspend fun refreshNow(context: Context): GlucoseState {
            val state = GlucoseRepository(context).refresh()
            updateComplications(context)
            scheduleNext(context, state)
            return state
        }

        fun kick(context: Context) {
            val app = context.applicationContext
            scope.launch { refreshNow(app) }
        }

        fun updateComplications(context: Context) {
            listOf(
                GlucoseValueComplicationService::class.java,
                GlucoseChartComplicationService::class.java,
                PredictionComplicationService::class.java,
                LoopComplicationService::class.java,
                TreatmentComplicationService::class.java,
            ).forEach {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, it)).requestUpdateAll()
            }
            GlucoseTile.all.forEach { TileService.getUpdater(context).requestUpdate(it) }
        }

        /** CGMs upload every 5 min: aim ~20 s after the next expected reading, retry sooner if it is late. */
        private fun scheduleNext(context: Context, state: GlucoseState) {
            val now = System.currentTimeMillis()
            val latest = state.latest?.timeMillis
            val delay = when {
                state.settings.source == DataSource.DEMO -> INTERVAL_MS
                latest == null -> INTERVAL_MS
                else -> {
                    val expected = latest + INTERVAL_MS + 20_000 - now
                    when {
                        expected > 30_000 -> expected.coerceAtMost(INTERVAL_MS + 20_000)
                        now - latest < 20 * 60_000 -> 60_000L // reading is late: poll every minute for a while
                        else -> INTERVAL_MS
                    }
                }
            }
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(context, RefreshReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val at = SystemClock.elapsedRealtime() + delay
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
            Log.d(TAG, "next refresh in ${delay / 1000}s (exact=$exact)")
        }
    }
}
