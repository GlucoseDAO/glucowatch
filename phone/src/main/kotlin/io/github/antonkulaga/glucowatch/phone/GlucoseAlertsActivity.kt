package io.github.antonkulaga.glucowatch.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import glucowatch.alerts.*
import glucowatch.core.link.LinkSource
import kotlinx.coroutines.*

class GlucoseAlertsActivity : AlertsActivity() {
    private val scope = MainScope()
    override fun warningAccount() = PhoneSettingsStore(this).load().configurationKey
    override fun changed() {
        PhoneAlertReceiver.schedule(this)
        scope.launch(Dispatchers.IO) { PhoneAlerts.evaluate(this@GlucoseAlertsActivity, PhoneRepository(this@GlucoseAlertsActivity).state()) }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

object PhoneAlerts {
    fun evaluate(context: Context, state: PhoneState) {
        if (PhoneSettingsStore(context).load().configurationKey != state.settings.configurationKey) return
        val config = AlertPreferences(context).load()
        val prediction = if (config.enabled && state.settings.source != LinkSource.DEMO)
            PhoneRepository(context).forecast(state, config.forecastMinutes) else null
        runCatching { AlertNotifications.evaluate(context, state.settings.configurationKey, state.settings.source == LinkSource.DEMO,
            state.readings, prediction, R.drawable.ic_launcher, GlucoseAlertsActivity::class.java) }
            .onFailure { android.util.Log.w("GlucoPhoneAlerts", "Could not evaluate warnings", it) }
    }
}

/** User-enabled inexact five-minute refresh. Android may defer this in battery saving modes. */
class PhoneAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        schedule(context)
        if (!AlertPreferences(context).load().enabled) return
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(31, ComponentName(context, PhoneAlertJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(60_000).build())
    }
    companion object {
        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(context, 31, Intent(context, PhoneAlertReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            alarms.cancel(pending)
            if (!AlertPreferences(context).load().enabled) context.getSystemService(JobScheduler::class.java).cancel(31)
            if (AlertPreferences(context).load().enabled) alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5 * 60_000L, pending)
        }
    }
}

/** Network work belongs to a job, rather than the short lifetime of an alarm broadcast. */
class PhoneAlertJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!AlertPreferences(this).load().enabled) return false
        running = scope.launch {
            try { PhoneRepository(this@PhoneAlertJob).refresh(maxAgeMs = 30_000) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("GlucoPhoneAlerts", "Background refresh failed", e) }
            finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
