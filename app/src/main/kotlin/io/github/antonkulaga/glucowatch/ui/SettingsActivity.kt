package io.github.antonkulaga.glucowatch.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.GlucoseUnit
import glucowatch.core.LinearTrendPredictor
import glucowatch.core.LoopStatus
import glucowatch.core.NightscoutApi
import glucowatch.core.Predictors
import glucowatch.core.Region
import io.github.antonkulaga.glucowatch.R
import io.github.antonkulaga.glucowatch.data.DataSource
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import io.github.antonkulaga.glucowatch.data.RefreshReceiver
import io.github.antonkulaga.glucowatch.data.Settings
import io.github.antonkulaga.glucowatch.data.SettingsStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Source (demo, Dexcom Share or Nightscout), its login, units and the optional forecast.
 * For development the fields can be prefilled from adb, see README ("Connect real Share data").
 */
class SettingsActivity : Activity() {
    private val scope = MainScope()
    private val store by lazy { SettingsStore(this) }

    private lateinit var source: RadioGroup
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var region: RadioGroup
    private lateinit var nightscoutUrl: EditText
    private lateinit var nightscoutToken: EditText
    private lateinit var nightscoutApi: RadioGroup
    private lateinit var unit: RadioGroup
    private lateinit var prediction: CheckBox
    private lateinit var predictor: RadioGroup
    private lateinit var result: TextView
    private lateinit var shareFields: List<View>
    private lateinit var nightscoutFields: List<View>
    private lateinit var loopPredictor: View
    private lateinit var heartButton: Button
    private lateinit var heartStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = fromIntent(store.load())
        // Round screens clip the corners: inset the column and let the ends scroll to the middle.
        val screen = resources.displayMetrics.widthPixels
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((screen * 0.11).toInt(), (screen * 0.17).toInt(), (screen * 0.11).toInt(), (screen * 0.3).toInt())
        }

        source = radios(DataSource.entries.map { it.name to it.label }, s.source.name)
        username = EditText(this).apply {
            hint = "Dexcom username / email"; setText(s.username); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        password = EditText(this).apply {
            hint = "Password"; setText(s.password); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        region = radios(Region.entries.map { it.name to it.label }, s.region.name)
        nightscoutUrl = EditText(this).apply {
            hint = "https://your-site.example"; setText(s.nightscoutUrl); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        nightscoutToken = EditText(this).apply {
            hint = "optional"; setText(s.nightscoutToken); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        nightscoutApi = radios(NightscoutApi.entries.map { it.name to it.label }, s.nightscoutApi.name)
        unit = radios(GlucoseUnit.entries.map { it.name to it.label }, s.unit.name)
        prediction = CheckBox(this).apply { text = "Show forecast"; isChecked = s.predictionEnabled }
        predictor = radios(Predictors.all.map { it.id to it.displayName } + (LoopStatus.MODEL_ID to "Loop (Nightscout)"), s.predictorId)
        loopPredictor = predictor.findViewWithTag(LoopStatus.MODEL_ID)
        result = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(Brand.TEXT) }
        val save = Button(this).apply { text = "Save & test"; setOnClickListener { saveAndTest() }; Brand.style(this, primary = true) }

        shareFields = listOf(label("Account"), username, revealable(password), label("Region"), region)
        nightscoutFields = listOf(
            label("Nightscout address"), nightscoutUrl,
            label("Token or API secret"), revealable(nightscoutToken), hint("A token with the readable role is safer. v3 needs a token."),
            label("API"), nightscoutApi,
        )
        listOf(label("Data source"), source).forEach(column::addView)
        shareFields.forEach(column::addView)
        nightscoutFields.forEach(column::addView)
        heartButton = Button(this).apply {
            text = "Allow heart rate"; Brand.style(this, primary = false)
            setOnClickListener { requestPermissions(arrayOf(heartPermission), REQUEST_HEART_RATE) }
        }
        heartStatus = hint("On the \u201cGlucose, time and heart\u201d tile. Allowed; change it in the watch's app permissions.")
        listOf(
            label("Units"), unit, label("Forecast"), prediction, predictor,
            label("Heart rate"), heartButton, heartStatus, save, result,
        ).forEach(column::addView)
        updateHeartRate()
        setContentView(ScrollView(this).apply { addView(column) })

        source.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        prediction.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        updateVisibility()

        if (isDebuggable && intent.getBooleanExtra("save", false)) saveAndTest()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_HEART_RATE) {
            updateHeartRate()
            RefreshReceiver.updateComplications(this)
        }
    }

    /** Wear OS 6 asks per health data type; older watches have the one body-sensors permission. */
    private val heartPermission
        get() = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

    private fun updateHeartRate() {
        val granted = checkSelfPermission(heartPermission) == PackageManager.PERMISSION_GRANTED
        heartButton.visibility = if (granted) View.GONE else View.VISIBLE
        heartStatus.visibility = if (granted) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateVisibility() {
        val source = DataSource.valueOf(selected(source))
        shareFields.forEach { it.visibility = if (source == DataSource.SHARE) View.VISIBLE else View.GONE }
        nightscoutFields.forEach { it.visibility = if (source == DataSource.NIGHTSCOUT) View.VISIBLE else View.GONE }
        val loop = source == DataSource.NIGHTSCOUT
        loopPredictor.visibility = if (loop) View.VISIBLE else View.GONE
        if (!loop && selected(predictor) == LoopStatus.MODEL_ID) predictor.check(predictor.findViewWithTag<View>(LinearTrendPredictor.ID).id)
        val choices = Predictors.all.size + if (loop) 1 else 0
        predictor.visibility = if (prediction.isChecked && choices > 1) View.VISIBLE else View.GONE
    }

    private fun saveAndTest() {
        val old = store.load()
        val new = old.copy(
            source = DataSource.valueOf(selected(source)),
            username = username.text.toString().trim(),
            password = password.text.toString(),
            region = Region.valueOf(selected(region)),
            nightscoutUrl = nightscoutUrl.text.toString().trim(),
            nightscoutToken = nightscoutToken.text.toString().trim(),
            nightscoutApi = NightscoutApi.valueOf(selected(nightscoutApi)),
            unit = GlucoseUnit.valueOf(selected(unit)),
            predictionEnabled = prediction.isChecked,
            predictorId = selected(predictor),
        )
        val repo = GlucoseRepository(this)
        if (new.accountKey != old.accountKey) repo.clearCache()
        store.save(new)
        result.text = "Testing…"
        scope.launch {
            val state = RefreshReceiver.refreshNow(applicationContext)
            val latest = state.latest
            result.text = when {
                state.lastError != null -> "⚠ ${state.lastError}"
                latest == null && new.source == DataSource.NIGHTSCOUT -> "Connected, but no readings in the last 24 h"
                latest == null -> "Logged in, but no readings. Is Share on with at least one follower?"
                else -> "OK: ${new.unit.format(latest.mgdl.toDouble())} ${new.unit.label}, ${state.ageMinutes()} min ago" +
                    state.loop?.let { "\nLoop reported ${it.ageMinutes()} min ago" }.orEmpty()
            }
        }
    }

    /**
     * adb shell am start -n …/.ui.SettingsActivity --es source SHARE --es username … (debug builds only).
     * Nightscout: --es source NIGHTSCOUT --es nightscoutUrl https://… --es nightscoutToken … --es nightscoutApi v1.
     */
    private fun fromIntent(s: Settings): Settings {
        if (!isDebuggable) return s
        val e = intent.extras ?: return s
        return s.copy(
            source = e.getString("source")?.let { DataSource.valueOf(it.uppercase()) } ?: s.source,
            username = e.getString("username") ?: s.username,
            password = e.getString("password") ?: s.password,
            region = e.getString("region")?.let(Region::parse) ?: s.region,
            nightscoutUrl = e.getString("nightscoutUrl") ?: s.nightscoutUrl,
            nightscoutToken = e.getString("nightscoutToken") ?: s.nightscoutToken,
            nightscoutApi = e.getString("nightscoutApi")?.let(NightscoutApi::parse) ?: s.nightscoutApi,
            unit = e.getString("unit")?.let(GlucoseUnit::parse) ?: s.unit,
            predictionEnabled = if (e.containsKey("prediction")) e.getBoolean("prediction") else s.predictionEnabled,
            predictorId = e.getString("predictor") ?: s.predictorId,
        )
    }

    private companion object {
        const val REQUEST_HEART_RATE = 1
    }

    private val isDebuggable get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun radios(options: List<Pair<String, String>>, checked: String) = RadioGroup(this).apply {
        options.forEach { (key, text) ->
            addView(RadioButton(this@SettingsActivity).apply {
                id = View.generateViewId(); tag = key; this.text = text; isChecked = key == checked
            })
        }
    }

    private fun selected(group: RadioGroup): String =
        group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String
            ?: (group.getChildAt(0).tag as String)

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Brand.TEAL_LIGHT); setPadding(0, dp(10), 0, 0)
    }

    /** [field] with an eye button after it that shows or hides what was typed. Starts hidden. */
    private fun revealable(field: EditText): View {
        val hidden = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val shown = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val eye = ImageButton(this).apply {
            setImageResource(R.drawable.ic_visibility)
            background = null
            contentDescription = "Show"
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                val reveal = field.inputType != shown
                val cursor = field.selectionEnd
                field.inputType = if (reveal) shown else hidden
                // Changing the input type resets the font to monospace for hidden text; keep one font.
                field.typeface = Typeface.DEFAULT
                field.setSelection(cursor.coerceIn(0, field.length()))
                setImageResource(if (reveal) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
                contentDescription = if (reveal) "Hide" else "Show"
            }
        }
        field.typeface = Typeface.DEFAULT
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(eye, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f; setTextColor(Brand.MUTED)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
