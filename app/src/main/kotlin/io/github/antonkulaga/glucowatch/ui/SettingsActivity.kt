package io.github.antonkulaga.glucowatch.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.GlucoseUnit
import glucowatch.core.Predictors
import glucowatch.core.Region
import io.github.antonkulaga.glucowatch.data.DataSource
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import io.github.antonkulaga.glucowatch.data.RefreshReceiver
import io.github.antonkulaga.glucowatch.data.Settings
import io.github.antonkulaga.glucowatch.data.SettingsStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Source, Dexcom login, units and the optional forecast.
 * For development the fields can be prefilled from adb, see README ("Connect real Share data").
 */
class SettingsActivity : Activity() {
    private val scope = MainScope()
    private val store by lazy { SettingsStore(this) }

    private lateinit var source: RadioGroup
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var region: RadioGroup
    private lateinit var unit: RadioGroup
    private lateinit var prediction: CheckBox
    private lateinit var predictor: RadioGroup
    private lateinit var result: TextView
    private lateinit var shareFields: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = fromIntent(store.load())
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(48))
        }

        source = radios(DataSource.entries.map { it.name to if (it == DataSource.DEMO) "Demo data" else "Dexcom Share" }, s.source.name)
        username = EditText(this).apply {
            hint = "Dexcom username / email"; setText(s.username); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        password = EditText(this).apply {
            hint = "Password"; setText(s.password); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        region = radios(Region.entries.map { it.name to it.label }, s.region.name)
        unit = radios(GlucoseUnit.entries.map { it.name to it.label }, s.unit.name)
        prediction = CheckBox(this).apply { text = "Show forecast"; isChecked = s.predictionEnabled }
        predictor = radios(Predictors.all.map { it.id to it.displayName }, s.predictorId)
        result = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) }
        val save = Button(this).apply { text = "Save & test"; setOnClickListener { saveAndTest() } }

        shareFields = listOf(label("Account"), username, password, label("Region"), region)
        listOf(label("Data source"), source).forEach(column::addView)
        shareFields.forEach(column::addView)
        listOf(label("Units"), unit, label("Forecast"), prediction, predictor, save, result).forEach(column::addView)
        setContentView(ScrollView(this).apply { addView(column) })

        source.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        prediction.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        updateVisibility()

        if (isDebuggable && intent.getBooleanExtra("save", false)) saveAndTest()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateVisibility() {
        val share = selected(source) == DataSource.SHARE.name
        shareFields.forEach { it.visibility = if (share) View.VISIBLE else View.GONE }
        predictor.visibility = if (prediction.isChecked && Predictors.all.size > 1) View.VISIBLE else View.GONE
    }

    private fun saveAndTest() {
        val old = store.load()
        val new = old.copy(
            source = DataSource.valueOf(selected(source)),
            username = username.text.toString().trim(),
            password = password.text.toString(),
            region = Region.valueOf(selected(region)),
            unit = GlucoseUnit.valueOf(selected(unit)),
            predictionEnabled = prediction.isChecked,
            predictorId = selected(predictor),
        )
        val repo = GlucoseRepository(this)
        if (new.source != old.source || new.username != old.username || new.region != old.region) repo.clearCache()
        store.save(new)
        result.text = "Testing…"
        scope.launch {
            val state = RefreshReceiver.refreshNow(applicationContext)
            val latest = state.latest
            result.text = when {
                state.lastError != null -> "⚠ ${state.lastError}"
                latest == null -> "Logged in, but no readings. Is Share on with at least one follower?"
                else -> "OK: ${new.unit.format(latest.mgdl.toDouble())} ${new.unit.label}, ${state.ageMinutes()} min ago"
            }
        }
    }

    /** adb shell am start -n …/.ui.SettingsActivity --es source SHARE --es username … (debug builds only). */
    private fun fromIntent(s: Settings): Settings {
        if (!isDebuggable) return s
        val e = intent.extras ?: return s
        return s.copy(
            source = e.getString("source")?.let { DataSource.valueOf(it.uppercase()) } ?: s.source,
            username = e.getString("username") ?: s.username,
            password = e.getString("password") ?: s.password,
            region = e.getString("region")?.let(Region::parse) ?: s.region,
            unit = e.getString("unit")?.let(GlucoseUnit::parse) ?: s.unit,
            predictionEnabled = if (e.containsKey("prediction")) e.getBoolean("prediction") else s.predictionEnabled,
        )
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
        this.text = text; textSize = 12f; setTextColor(0xFF90CAF9.toInt()); setPadding(0, dp(10), 0, 0)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
