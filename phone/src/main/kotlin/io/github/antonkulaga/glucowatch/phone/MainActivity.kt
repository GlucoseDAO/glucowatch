package io.github.antonkulaga.glucowatch.phone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import glucowatch.core.GlucoseUnit
import glucowatch.core.NightscoutApi
import glucowatch.core.Predictors
import glucowatch.core.Region
import glucowatch.core.link.LinkSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The phone app's one screen: the latest reading, the phone's source and its login, the model it
 * forecasts with for the watch, and the paired watches. The watch does the showing; this app
 * only fetches and relays (docs/phone-link.md).
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val store by lazy { PhoneSettingsStore(this) }
    private val repository by lazy { PhoneRepository(this) }
    private val watches by lazy { PairedWatches(this) }

    private lateinit var value: TextView
    private lateinit var detail: TextView
    private lateinit var source: RadioGroup
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var region: RadioGroup
    private lateinit var nightscoutUrl: EditText
    private lateinit var nightscoutToken: EditText
    private lateinit var nightscoutApi: RadioGroup
    private lateinit var unit: RadioGroup
    private lateinit var predictor: RadioGroup
    private lateinit var result: TextView
    private lateinit var shareFields: List<View>
    private lateinit var nightscoutFields: List<View>
    private lateinit var watchList: LinearLayout
    private lateinit var pairButton: Button
    private lateinit var pairingText: TextView
    private lateinit var pairingCode: TextView
    private lateinit var pairingButtons: View

    /** What to do once the user allows Nearby devices. */
    private var afterBluetooth: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = store.load()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        val title = TextView(this).apply { text = getString(R.string.app_name); textSize = 22f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD }
        value = TextView(this).apply { textSize = 44f; gravity = Gravity.CENTER; setTextColor(TEAL_BRIGHT); typeface = Typeface.DEFAULT_BOLD }
        detail = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(MUTED) }

        source = radios(LinkSource.entries.map { it.name to PhoneSettings(source = it).sourceLabel }, s.source.name)
        username = field("Dexcom username / email", s.username, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = field("Password", s.password, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        region = radios(Region.entries.map { it.name to it.label }, s.region.name)
        nightscoutUrl = field("https://your-site.example", s.nightscoutUrl, InputType.TYPE_TEXT_VARIATION_URI)
        nightscoutToken = field("optional", s.nightscoutToken, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        nightscoutApi = radios(NightscoutApi.entries.map { it.name to it.label }, s.nightscoutApi.name)
        unit = radios(GlucoseUnit.entries.map { it.name to it.label }, s.unit.name)
        predictor = radios(Predictors.all.map { it.id to it.displayName }, s.predictorId)
        result = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(TEXT); setPadding(0, dp(8), 0, 0) }
        val save = Button(this).apply { text = "Save & test"; style(this, primary = true); setOnClickListener { saveAndTest() } }

        shareFields = listOf(label("Account"), username, password, label("Region"), region, hint("Share must be on in the Dexcom app, with at least one follower."))
        nightscoutFields = listOf(
            label("Nightscout address"), nightscoutUrl,
            label("Token or API secret"), nightscoutToken, hint("A token with the readable role is safer. v3 needs a token."),
            label("API"), nightscoutApi,
        )

        watchList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pairButton = Button(this).apply { text = "Pair a watch"; style(this, primary = false); setOnClickListener { startPairing() } }
        pairingText = TextView(this).apply { textSize = 14f; setTextColor(TEXT); setPadding(0, dp(8), 0, 0) }
        pairingCode = TextView(this).apply { textSize = 34f; gravity = Gravity.CENTER; setTextColor(TEAL_LIGHT); typeface = Typeface.DEFAULT_BOLD }
        pairingButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply { text = "Codes match"; style(this, primary = true); setOnClickListener { confirmPairing() } }, weight())
            addView(Button(this@MainActivity).apply { text = "Cancel"; style(this, primary = false); setOnClickListener { PairingWindow.close(); LinkService.update(this@MainActivity) } }, weight())
        }

        listOf(title, value, detail, label("Source"), source).forEach(column::addView)
        shareFields.forEach(column::addView)
        nightscoutFields.forEach(column::addView)
        listOf(
            label("Units"), unit,
            label("Forecast model"), predictor, hint("The watch uses it when its forecast is set to “Phone app model”."),
            save, result,
            label("Watches"), watchList, pairButton, pairingText, pairingCode, pairingButtons,
            hint("The watch connects over Bluetooth. Only watches paired here get readings, and each message is encrypted with the key from pairing."),
            hint("Not a medical device. Keep using the official Dexcom app and its alarms."),
        ).forEach(column::addView)
        setContentView(ScrollView(this).apply {
            addView(column)
            // Android 15 and later draw behind the status and navigation bars; keep the content clear of them.
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        })

        source.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        updateVisibility()
        updateWatches()
        PairingWindow.listener = {
            updatePairing()
            // Pairing ran out with no watch paired: nothing left to listen for.
            if (!PairingWindow.isOpen && PairingWindow.pending == null) LinkService.update(this)
        }
        updatePairing()

        render(repository.state())
        scope.launch { render(repository.refresh(maxAgeMs = 60_000)) }
        // Ask for Nearby devices only once a watch is paired; pairing asks too.
        if (watches.all().isNotEmpty()) withBluetooth { LinkService.update(this) }
    }

    override fun onDestroy() {
        PairingWindow.listener = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        val action = afterBluetooth
        afterBluetooth = null
        if (LinkService.hasPermission(this)) action?.invoke()
        else result.text = "⚠ The watch can only connect with the Nearby devices permission"
    }

    /** Runs [action] now, or after the user allows Nearby devices. Also asks once for the service's notification. */
    private fun withBluetooth(action: () -> Unit) {
        val wanted = buildList {
            if (!LinkService.hasPermission(this@MainActivity)) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Manifest.permission.BLUETOOTH_CONNECT !in wanted) {
            action()
            if (wanted.isEmpty()) return
        } else {
            afterBluetooth = action
        }
        requestPermissions(wanted.toTypedArray(), REQUEST_BLUETOOTH)
    }

    private fun render(state: PhoneState) {
        val latest = state.latest
        val u = state.settings.unit
        if (latest == null) {
            value.text = "—"
            detail.text = if (state.settings.source == LinkSource.DEMO) "Demo data" else "No readings yet"
        } else {
            val minutes = (System.currentTimeMillis() - latest.timeMillis) / 60_000
            value.text = "${u.format(latest.mgdl.toDouble())} ${latest.trend.arrow}".trim()
            // The watch's default target range, 70–180 mg/dL, in the watch's colours.
            value.setTextColor(if (latest.mgdl < 70) LOW else if (latest.mgdl > 180) HIGH else TEAL_BRIGHT)
            detail.text = "${u.label} · $minutes min ago · ${state.settings.sourceLabel}"
        }
        state.lastError?.takeIf { state.settings.source != LinkSource.DEMO }?.let { detail.append("\n⚠ $it") }
    }

    private fun updateVisibility() {
        val chosen = LinkSource.valueOf(selected(source))
        shareFields.forEach { it.visibility = if (chosen == LinkSource.SHARE) View.VISIBLE else View.GONE }
        nightscoutFields.forEach { it.visibility = if (chosen == LinkSource.NIGHTSCOUT) View.VISIBLE else View.GONE }
    }

    private fun saveAndTest() {
        val old = store.load()
        val new = old.copy(
            source = LinkSource.valueOf(selected(source)),
            username = username.text.toString().trim(),
            password = password.text.toString(),
            region = Region.valueOf(selected(region)),
            nightscoutUrl = nightscoutUrl.text.toString().trim(),
            nightscoutToken = nightscoutToken.text.toString().trim(),
            nightscoutApi = NightscoutApi.valueOf(selected(nightscoutApi)),
            unit = GlucoseUnit.valueOf(selected(unit)),
            predictorId = selected(predictor),
        )
        if (new.accountKey != old.accountKey) repository.clearCache()
        store.save(new)
        result.text = "Testing…"
        scope.launch {
            val state = repository.refresh()
            render(state)
            result.text = when {
                new.source == LinkSource.DEMO -> "Saved. The watch gets demo data."
                state.lastError != null -> "⚠ ${state.lastError}"
                state.latest == null -> "Connected, but no readings in the last 24 h"
                else -> "OK. Paired watches get this now."
            }
        }
    }

    private fun startPairing() = withBluetooth {
        PairingWindow.open()
        LinkService.update(this)
    }

    private fun confirmPairing() {
        val pending = PairingWindow.pending ?: return
        watches.add(pending.watchId, pending.watchName, pending.keys.key)
        PairingWindow.close()
        LinkService.update(this)
        updateWatches()
        result.text = "Paired with ${pending.watchName}. Confirm on the watch too."
    }

    private fun updatePairing() {
        val pending = PairingWindow.pending
        val open = PairingWindow.isOpen
        pairingText.text = when {
            pending != null -> "${pending.watchName} wants to pair. Check that the watch shows the same code."
            open -> "On the watch, open GlucoWatch → Settings and tap Pair with phone. Pairing stays open for 2 minutes."
            else -> ""
        }
        pairingText.visibility = if (pairingText.text.isEmpty()) View.GONE else View.VISIBLE
        pairingCode.text = pending?.keys?.displayCode.orEmpty()
        pairingCode.visibility = if (pending != null) View.VISIBLE else View.GONE
        pairingButtons.visibility = if (pending != null) View.VISIBLE else View.GONE
        pairButton.visibility = if (open || pending != null) View.GONE else View.VISIBLE
    }

    private fun updateWatches() {
        watchList.removeAllViews()
        val all = watches.all()
        if (all.isEmpty()) watchList.addView(hint("No watch paired yet."))
        all.forEach { watch ->
            watchList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply { text = watch.name; textSize = 16f; setTextColor(TEXT) }, weight())
                addView(Button(this@MainActivity).apply {
                    text = "Forget"; style(this, primary = false)
                    setOnClickListener {
                        watches.remove(watch.id)
                        LinkService.update(this@MainActivity)
                        updateWatches()
                    }
                })
            })
        }
    }

    private fun field(hint: String, value: String, variation: Int) = EditText(this).apply {
        this.hint = hint; setText(value); isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or variation
    }

    private fun radios(options: List<Pair<String, String>>, checked: String) = RadioGroup(this).apply {
        options.forEach { (key, text) ->
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId(); tag = key; this.text = text; isChecked = key == checked
            })
        }
    }

    private fun selected(group: RadioGroup): String =
        group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String
            ?: (group.getChildAt(0).tag as String)

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(TEAL_LIGHT); setPadding(0, dp(16), 0, 0)
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(6), 0, 0)
    }

    private fun style(button: Button, primary: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (primary) TEAL else SURFACE)
        button.setTextColor(if (primary) 0xFFFFFFFF.toInt() else TEAL_LIGHT)
        button.isAllCaps = false
    }

    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val REQUEST_BLUETOOTH = 1

        // GlucoseDAO colours, as in the watch app's ui/Brand.kt.
        const val TEAL = 0xFF0B7285.toInt()
        const val TEAL_BRIGHT = 0xFF2FB3C6.toInt()
        const val TEAL_LIGHT = 0xFF6CCBD8.toInt()
        const val SURFACE = 0xFF13263A.toInt()
        const val MUTED = 0xFF8FA0AA.toInt()
        const val TEXT = 0xFFE6EDF0.toInt()
        const val HIGH = 0xFFF2C94C.toInt()
        const val LOW = 0xFFF0525A.toInt()
    }
}
