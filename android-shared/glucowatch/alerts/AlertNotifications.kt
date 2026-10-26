package glucowatch.alerts

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import glucowatch.core.*
import org.json.JSONArray
import org.json.JSONObject

class AlertPreferences(context: Context) {
    val prefs = context.getSharedPreferences("glucose-alerts", Context.MODE_PRIVATE)
    fun bindAccount(account: String) {
        val key = DebugDefaults.fingerprint(account)
        if (prefs.getString("account", "") != key) prefs.edit().putString("account", key)
            .remove("reading").remove("sent").remove("kind").remove("history").apply()
    }
    fun load() = GlucoseAlertSettings(
        enabled = prefs.getBoolean("enabled", false), lowEnabled = prefs.getBoolean("low", true), highEnabled = prefs.getBoolean("high", true),
        lowMgdl = prefs.getInt("lowMgdl", 90), urgentLowMgdl = prefs.getInt("urgent", 54),
        fallMgdlPerMinute = prefs.getFloat("fall", 2f).toDouble(), highMgdl = prefs.getInt("highMgdl", 220),
        forecastMinutes = prefs.getInt("forecast", 20), repeatMinutes = prefs.getInt("repeat", 15))
    fun save(s: GlucoseAlertSettings) { prefs.edit().putBoolean("enabled", s.enabled).putBoolean("low", s.lowEnabled)
        .putBoolean("high", s.highEnabled).putInt("lowMgdl", s.lowMgdl).putInt("urgent", s.urgentLowMgdl)
        .putFloat("fall", s.fallMgdlPerMinute.toFloat()).putInt("highMgdl", s.highMgdl)
        .putInt("forecast", s.forecastMinutes).putInt("repeat", s.repeatMinutes).apply() }
}

/** Same evaluation and notification behavior on each device. Never sends pump commands. */
object AlertNotifications {
    const val CHANNEL = "glucose_danger_alerts"
    private const val ID = 31
    fun channel(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "Glucose warnings", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rapid falls near low glucose, urgent lows and high glucose"; enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    fun available(context: Context): Boolean {
        val manager = channel(context)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    @Synchronized
    fun evaluate(context: Context, account: String, demo: Boolean, readings: List<GlucoseReading>, forecast: Prediction?, icon: Int, destination: Class<out Activity>) {
        val manager = channel(context); val store = AlertPreferences(context); val settings = store.load()
        val key = DebugDefaults.fingerprint(account); val p = store.prefs
        if (p.getString("account", "") != key) { store.bindAccount(account); manager.cancel(ID) }
        val event = if (demo) null else GlucoseAlerts.evaluate(readings, forecast, settings, System.currentTimeMillis())
        if (event == null) { manager.cancel(ID); p.edit().remove("kind").apply(); return }
        val now = System.currentTimeMillis()
        if (p.getLong("reading", 0) >= event.readingTime) return
        val last = p.getString("kind", "")
        val escalation = event.kind == GlucoseAlertKind.URGENT_LOW && last != GlucoseAlertKind.URGENT_LOW.name ||
            event.kind == GlucoseAlertKind.FALLING_LOW && last == GlucoseAlertKind.HIGH.name
        if (!escalation && now - p.getLong("sent", 0) < settings.repeatMinutes * 60_000L) return
        if (!available(context)) return
        val open = PendingIntent.getActivity(context, ID, Intent(context, destination), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(icon).setContentTitle(event.title)
            .setContentText("${event.mgdl} mg/dL. ${event.message}")
            .setStyle(Notification.BigTextStyle().bigText("${event.mgdl} mg/dL${if (event.predicted) " now; forecast crosses threshold" else ""}. ${event.message}"))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open).setAutoCancel(true).setLocalOnly(true).build()
        try { manager.notify(ID, notification) } catch (_: SecurityException) { return }
        val history = JSONArray(p.getString("history", "[]"))
        history.put(JSONObject().put("time", now).put("title", event.title).put("mgdl", event.mgdl))
        val bounded = JSONArray(); (maxOf(0, history.length() - 30) until history.length()).forEach { bounded.put(history.getJSONObject(it)) }
        p.edit().putLong("reading", event.readingTime).putLong("sent", now).putString("kind", event.kind.name).putString("history", bounded.toString()).apply()
    }
}
