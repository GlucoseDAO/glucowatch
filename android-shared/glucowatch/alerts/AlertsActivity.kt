package glucowatch.alerts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.widget.*
import glucowatch.core.GlucoseAlertSettings
import glucowatch.core.GlucosePalette
import org.json.JSONArray

/** Shared phone/watch controls. Android owns sound, vibration and permission preferences. */
abstract class AlertsActivity : Activity() {
    protected abstract fun changed()
    protected abstract fun warningAccount(): String
    private lateinit var status: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val store = AlertPreferences(this); store.bindAccount(warningAccount()); val s = store.load()
        val round = resources.configuration.isScreenRound; val side = if (round) (resources.displayMetrics.widthPixels * .15).toInt() else 24
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(side, if (round) 50 else 24, side, if (round) 100 else 24); setBackgroundColor(0xff000000.toInt()) }
        fun text(value: String, size: Float = 15f) = TextView(this).apply { text = value; textSize = size; setTextColor(-1); setPadding(0, 12, 0, 12) }.also(body::addView)
        fun button(value: String, action: () -> Unit) { body.addView(Button(this).apply {
            text = value; isAllCaps = false; setTextColor(GlucosePalette.GREY)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xff141414.toInt())
            setOnClickListener { action() }
        }) }
        button("Back") { finish() }; text("Alarms & notifications", if (round) 20f else 26f)
        fun toggle(value: String, selected: Boolean) = CheckBox(this).apply { text = value; setTextColor(GlucosePalette.WHITE); isChecked = selected
            buttonTintList = android.content.res.ColorStateList.valueOf(GlucosePalette.GREY) }.also(body::addView)
        val enabled = toggle("Enable glucose warnings on this device", s.enabled)
        val low = toggle("Urgent low and rapid fall warnings", s.lowEnabled)
        val high = toggle("High glucose warnings", s.highEnabled)
        fun field(label: String, value: String, decimal: Boolean = false): EditText {
            text(label)
            return EditText(this).apply { setText(value); setTextColor(-1); setSingleLine(); contentDescription = label
                inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0 }.also(body::addView)
        }
        val lowAt = field("Rapid fall: glucose at or below (mg/dL)", s.lowMgdl.toString())
        val urgent = field("Urgent low at or below (mg/dL)", s.urgentLowMgdl.toString())
        val fall = field("Rapid fall (mg/dL/min)", s.fallMgdlPerMinute.toString(), true)
        val highAt = field("High above (mg/dL)", s.highMgdl.toString())
        val horizon = field("Forecast look ahead (5–30 min)", s.forecastMinutes.toString())
        val repeat = field("Repeat interval (5–60 min)", s.repeatMinutes.toString())
        status = text("")
        button("Save warnings") {
            try {
                store.save(GlucoseAlertSettings(enabled.isChecked, low.isChecked, high.isChecked, lowAt.text.toString().toInt(),
                    urgent.text.toString().toInt(), fall.text.toString().toDouble(), highAt.text.toString().toInt(),
                    horizon.text.toString().toInt(), repeat.text.toString().toInt()))
                if (enabled.isChecked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                changed(); status.text = "Saved. " + permissionStatus()
            } catch (_: IllegalArgumentException) {
                status.text = "Use urgent low 40–70; rapid-fall glucose from urgent low to 120; fall 0.5–5; high 180–350; forecast 5–30; repeat 5–60."
            }
        }
        button("Sound, vibration & notification permission") {
            try { startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, AlertNotifications.CHANNEL)) }
            catch (_: android.content.ActivityNotFoundException) {
                try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                catch (_: android.content.ActivityNotFoundException) { status.text = "Open this app's notification permissions in device Settings." }
            }
        }
        text("Uses fresh readings (up to 10 minutes old). Forecasts can warn earlier. No alerts from demo data or historical imports. Configure this device and your other device separately.")
        text("Keep your CGM's official alerts enabled. Warnings depend on incoming data, Android background scheduling, notification permissions and Do Not Disturb. This app does not control the pump.")
        val history = JSONArray(store.prefs.getString("history", "[]")); text("Recent warnings", 18f)
        if (history.length() == 0) text("No warnings recorded on this device.")
        for (i in history.length() - 1 downTo 0) { val e = history.getJSONObject(i)
            text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.getLong("time")))}\n${e.getString("title")} · ${e.getInt("mgdl")} mg/dL") }
        val scroll = ScrollView(this).apply { addView(body) }
        if (round) {
            scroll.isFocusableInTouchMode = true
            scroll.setOnGenericMotionListener { _, event ->
                if (event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER) && event.action == android.view.MotionEvent.ACTION_SCROLL) {
                    scroll.scrollBy(0, (-event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL) * 48 * resources.displayMetrics.density).toInt()); true
                } else false
            }
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) { val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); view.setPadding(b.left,b.top,b.right,b.bottom) }
            else { @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }; insets
        }
        setContentView(scroll)
        if (round) scroll.requestFocus()
    }
    private fun permissionStatus() = if (AlertNotifications.available(this)) "Android notifications allowed." else "Android notifications are blocked; allow them in system settings."
    override fun onResume() { super.onResume(); status.text = permissionStatus() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) { changed(); status.text = permissionStatus() }
    }
}
