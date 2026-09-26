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
import glucowatch.core.DohResolver
import glucowatch.core.ProxyEndpoint
import glucowatch.core.Region
import glucowatch.core.HttpRequest
import glucowatch.core.UrlConnectionTransport
import glucowatch.core.link.LinkAccount
import glucowatch.core.link.LinkSource
import glucowatch.core.link.PhoneLink
import glucowatch.core.link.WatchLinkClient
import io.github.antonkulaga.glucowatch.R
import io.github.antonkulaga.glucowatch.data.DataSource
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import io.github.antonkulaga.glucowatch.data.PhoneConnection
import io.github.antonkulaga.glucowatch.data.PhonePairing
import io.github.antonkulaga.glucowatch.data.PhonePairingStore
import io.github.antonkulaga.glucowatch.data.RefreshReceiver
import io.github.antonkulaga.glucowatch.data.Settings
import io.github.antonkulaga.glucowatch.data.SettingsStore
import io.github.antonkulaga.glucowatch.data.StaleAlert
import io.github.antonkulaga.glucowatch.data.StaleAlertReceiver
import io.github.antonkulaga.glucowatch.data.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Source (demo, Dexcom Share, Nightscout or the phone app), its login, pairing with the phone,
 * units and the optional forecast.
 * For development the fields can be prefilled from adb, see README ("Connect real Share data").
 */
class SettingsActivity : Activity() {
    private val scope = MainScope()
    private val store by lazy { SettingsStore(this) }
    private val pairings by lazy { PhonePairingStore(this) }

    private lateinit var source: RadioGroup
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var region: RadioGroup
    private lateinit var shareProxy: EditText
    private lateinit var proxyResult: TextView
    private lateinit var shareDoh: CheckBox
    private lateinit var dohEndpoint: RadioGroup
    private lateinit var nightscoutUrl: EditText
    private lateinit var nightscoutToken: EditText
    private lateinit var nightscoutApi: RadioGroup
    private lateinit var unit: RadioGroup
    private lateinit var prediction: CheckBox
    private lateinit var predictor: RadioGroup
    private lateinit var staleAlert: RadioGroup
    private lateinit var alertPermission: Button
    private lateinit var result: TextView
    private lateinit var shareFields: List<View>
    private lateinit var nightscoutFields: List<View>
    private lateinit var loopPredictor: View
    private lateinit var phonePredictor: View
    private lateinit var phoneFields: List<View>
    private lateinit var phoneStatus: TextView
    private lateinit var pairButton: Button
    private lateinit var copyButton: Button
    private lateinit var codeBlock: View
    private lateinit var codeView: TextView

    /** Pairing and copy messages, beside their buttons rather than at the end of the screen. */
    private lateinit var phoneResult: TextView

    /** A pairing the phone agreed to, shown as a code until the user confirms it here. */
    private var pendingPairing: PhonePairing? = null

    /** What to do once the user allows Nearby devices. */
    private var afterBluetooth: (() -> Unit)? = null
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
        shareProxy = EditText(this).apply {
            hint = "proxy.example:8080"; setText(s.shareProxy); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        proxyResult = hint("")
        shareDoh = CheckBox(this).apply { text = "Resolve over HTTPS"; isChecked = s.shareDoh }
        dohEndpoint = radios(DohResolver.PRESETS.map { (name, url) -> url to name }, endpointOf(s.dohEndpoint))
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
        predictor = radios(
            Predictors.all.map { it.id to it.displayName } + (LoopStatus.MODEL_ID to "Loop (Nightscout)") + (PhoneLink.MODEL_ID to "Phone app model"),
            s.predictorId,
        )
        staleAlert = radios(StaleAlert.entries.map { it.name to it.label }, s.staleAlert.name)
        alertPermission = Button(this).apply {
            text = "Allow stale alerts"; Brand.style(this, primary = false)
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS) }
        }
        loopPredictor = predictor.findViewWithTag(LoopStatus.MODEL_ID)
        phonePredictor = predictor.findViewWithTag(PhoneLink.MODEL_ID)
        result = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(Brand.TEXT) }
        val save = Button(this).apply {
            text = "Save & test"; Brand.style(this, primary = true)
            setOnClickListener { if (DataSource.valueOf(selected(source)) == DataSource.PHONE) withBluetooth(::saveAndTest) else saveAndTest() }
        }

        shareFields = listOf(
            label("Account"), username, revealable(password), label("Region"), region,
            label("If DNS fails"), shareDoh,
            hint(
                "Some mobile networks will not resolve Dexcom. This asks a resolver over HTTPS instead, and " +
                    "still checks Dexcom's certificate. It cannot help when the network blocks the address itself.",
            ),
            dohEndpoint,
            label("Proxy fallback (optional)"), shareProxy,
            hint("Leave empty for the watch network. Use only a proxy you trust."),
            Button(this).apply {
                text = "Test proxy"; Brand.style(this, primary = false)
                setOnClickListener { testProxy() }
            },
            proxyResult,
        )
        nightscoutFields = listOf(
            label("Nightscout address"), nightscoutUrl,
            label("Token or API secret"), revealable(nightscoutToken), hint("A token with the readable role is safer. v3 needs a token."),
            label("API"), nightscoutApi,
        )
        phoneFields = listOf(hint("The phone app fetches and passes it on over Bluetooth. No login or internet on the watch."))
        phoneStatus = hint("")
        pairButton = Button(this).apply { Brand.style(this, primary = false); setOnClickListener { pair() } }
        codeView = TextView(this).apply {
            textSize = 26f; gravity = Gravity.CENTER; setTextColor(Brand.TEXT); typeface = Typeface.DEFAULT_BOLD
        }
        codeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(codeView)
            addView(Button(this@SettingsActivity).apply { text = "Codes match"; Brand.style(this, primary = true); setOnClickListener { confirmPairing() } })
            addView(Button(this@SettingsActivity).apply { text = "Cancel"; Brand.style(this, primary = false); setOnClickListener { pendingPairing = null; phoneResult.text = ""; updatePhone() } })
        }
        phoneResult = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(Brand.TEXT) }
        copyButton = Button(this).apply { text = "Copy login from phone"; Brand.style(this, primary = false); setOnClickListener { copyLogin() } }

        listOf(label("Data source"), source).forEach(column::addView)
        shareFields.forEach(column::addView)
        nightscoutFields.forEach(column::addView)
        phoneFields.forEach(column::addView)
        listOf(label("Phone"), phoneStatus, pairButton, codeBlock, copyButton, phoneResult).forEach(column::addView)
        heartButton = Button(this).apply {
            text = "Allow heart rate"; Brand.style(this, primary = false)
            setOnClickListener { requestPermissions(arrayOf(heartPermission), REQUEST_HEART_RATE) }
        }
        heartStatus = hint("On the \u201cGlucose, time and heart\u201d tile. Allowed; change it in the watch's app permissions.")
        listOf(
            label("Units"), unit, label("Forecast"), prediction, predictor,
            label("Old reading alert (after 10 min)"), staleAlert, alertPermission,
            label("Heart rate"), heartButton, heartStatus, save, result,
            Button(this).apply {
                text = "Connection check"; Brand.style(this, primary = false)
                setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, DiagnosticsActivity::class.java)) }
            },
            hint("Where a fetch stops: name, route, handshake or the request itself."),
        ).forEach(column::addView)
        updateHeartRate()
        updateAlertPermission()
        updatePhone()
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
        if (requestCode == REQUEST_BLUETOOTH) {
            val action = afterBluetooth
            afterBluetooth = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
            else phoneResult.text = "⚠ The phone app needs the Nearby devices permission"
        }
        if (requestCode == REQUEST_NOTIFICATIONS) {
            updateAlertPermission()
            StaleAlertReceiver.schedule(this, GlucoseRepository(this).state())
        }
    }

    /** Runs [action] now, or after the user allows Nearby devices (Android 12 and later). */
    private fun withBluetooth(action: () -> Unit) {
        if (PhoneConnection(this).hasPermission()) return action()
        afterBluetooth = action
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
    }

    private fun updatePhone() {
        val pairing = pairings.load()
        val pending = pendingPairing != null
        phoneStatus.text = pairing?.let { "Paired with ${it.phoneName}." }
            ?: "Not paired. Tap Pair a watch in the phone app, then Pair here."
        pairButton.text = if (pairing == null) "Pair with phone" else "Pair again"
        pairButton.visibility = if (pending) View.GONE else View.VISIBLE
        codeBlock.visibility = if (pending) View.VISIBLE else View.GONE
        copyButton.visibility = if (pairing != null && !pending) View.VISIBLE else View.GONE
    }

    /** Agrees on a key with the phone and shows the code the user compares on both screens. */
    private fun pair() = withBluetooth {
        phoneResult.text = "Looking for GlucoWatch on the phone…"
        pairButton.isEnabled = false
        scope.launch {
            val found = runCatching {
                withContext(Dispatchers.IO) {
                    PhoneConnection(this@SettingsActivity).open(null) { device, input, output ->
                        val offer = WatchLinkClient(pairings.watchId).pair(input, output)
                        PhonePairing(offer.phoneId.toHex(), offer.phoneName, device.address, offer.keys.key) to offer.keys.displayCode
                    }
                }
            }
            pairButton.isEnabled = true
            found.onSuccess { (pairing, code) ->
                pendingPairing = pairing
                codeView.text = code
                phoneResult.text = "Check that ${pairing.phoneName} shows the same code, then confirm on both."
            }.onFailure { phoneResult.text = "⚠ ${it.message}" }
            updatePhone()
        }
    }

    private fun confirmPairing() {
        val pairing = pendingPairing ?: return
        pendingPairing = null
        pairings.save(pairing)
        val old = store.load()
        val new = old.copy(phoneId = pairing.phoneId)
        if (new.accountKey != old.accountKey) GlucoseRepository(this).clearCache()
        store.save(new)
        phoneResult.text = "Paired. Confirm on the phone too. Then pick Phone app above, or copy the phone's login."
        updatePhone()
    }

    private fun copyLogin() = withBluetooth {
        val pairing = pairings.load() ?: return@withBluetooth
        phoneResult.text = "Asking the phone…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PhoneConnection(this@SettingsActivity).open(pairing.address) { _, input, output ->
                        WatchLinkClient(pairings.watchId).account(input, output, pairing.key)
                    }
                }
            }.onSuccess(::fillFrom).onFailure { phoneResult.text = "⚠ ${it.message}" }
        }
    }

    /** Puts the phone's login into the fields. Nothing is saved until Save & test. */
    private fun fillFrom(account: LinkAccount) {
        when (account.source) {
            LinkSource.DEMO -> {
                phoneResult.text = "The phone shows demo data, so there is no login to copy."
                return
            }
            LinkSource.SHARE -> {
                username.setText(account.username)
                password.setText(account.password)
                check(region, account.region.name)
            }
            LinkSource.NIGHTSCOUT -> {
                nightscoutUrl.setText(account.nightscoutUrl)
                nightscoutToken.setText(account.nightscoutToken)
                check(nightscoutApi, account.nightscoutApi.name)
            }
        }
        check(source, account.source.name)
        phoneResult.text = "Copied the phone's ${DataSource.valueOf(account.source.name).label} login. Tap Save & test to use it."
    }

    /** Wear OS 6 asks per health data type; older watches have the one body-sensors permission. */
    private val heartPermission
        get() = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

    private fun updateHeartRate() {
        val granted = checkSelfPermission(heartPermission) == PackageManager.PERMISSION_GRANTED
        heartButton.visibility = if (granted) View.GONE else View.VISIBLE
        heartStatus.visibility = if (granted) View.VISIBLE else View.GONE
    }

    private fun updateAlertPermission() {
        alertPermission.visibility = if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateVisibility() {
        val source = DataSource.valueOf(selected(source))
        shareFields.forEach { it.visibility = if (source == DataSource.SHARE) View.VISIBLE else View.GONE }
        nightscoutFields.forEach { it.visibility = if (source == DataSource.NIGHTSCOUT) View.VISIBLE else View.GONE }
        phoneFields.forEach { it.visibility = if (source == DataSource.PHONE) View.VISIBLE else View.GONE }
        val loop = source in GlucoseRepository.LOOP_SOURCES
        val phone = source == DataSource.PHONE
        loopPredictor.visibility = if (loop) View.VISIBLE else View.GONE
        phonePredictor.visibility = if (phone) View.VISIBLE else View.GONE
        val chosen = selected(predictor)
        if ((!loop && chosen == LoopStatus.MODEL_ID) || (!phone && chosen == PhoneLink.MODEL_ID)) check(predictor, LinearTrendPredictor.ID)
        val choices = Predictors.all.size + (if (loop) 1 else 0) + (if (phone) 1 else 0)
        predictor.visibility = if (prediction.isChecked && choices > 1) View.VISIBLE else View.GONE
    }

    private fun saveAndTest() {
        val old = store.load()
        val proxyText = shareProxy.text.toString().trim()
        if (DataSource.valueOf(selected(source)) == DataSource.SHARE && proxyText.isNotEmpty()) {
            val problem = runCatching { ProxyEndpoint.parse(proxyText) }.exceptionOrNull()?.message
            if (problem != null) {
                proxyResult.text = "⚠ $problem"
                return
            }
        }
        val new = old.copy(
            source = DataSource.valueOf(selected(source)),
            username = username.text.toString().trim(),
            password = password.text.toString(),
            region = Region.valueOf(selected(region)),
            shareProxy = proxyText,
            shareDoh = shareDoh.isChecked,
            dohEndpoint = selected(dohEndpoint),
            nightscoutUrl = nightscoutUrl.text.toString().trim(),
            nightscoutToken = nightscoutToken.text.toString().trim(),
            nightscoutApi = NightscoutApi.valueOf(selected(nightscoutApi)),
            unit = GlucoseUnit.valueOf(selected(unit)),
            predictionEnabled = prediction.isChecked,
            predictorId = selected(predictor),
            staleAlert = StaleAlert.valueOf(selected(staleAlert)),
        )
        val repo = GlucoseRepository(this)
        if (new.accountKey != old.accountKey) repo.clearCache()
        store.save(new)
        if (new.source != DataSource.DEMO && new.staleAlert != StaleAlert.OFF &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        result.text = "Testing…"
        scope.launch {
            val state = RefreshReceiver.refreshNow(applicationContext)
            val latest = state.latest
            val latestAge = state.ageMinutes()
            result.text = when {
                state.lastError != null -> "⚠ ${state.lastError}"
                latest == null && new.source == DataSource.NIGHTSCOUT -> "Connected, but no readings in the last 24 h"
                latest == null && new.source == DataSource.PHONE -> "Connected, but the phone has no readings yet"
                latest == null -> "Logged in, but no readings. Is Share on with at least one follower?"
                new.source == DataSource.SHARE && latestAge != null && latestAge >= 7 ->
                    "Share answered, but its newest reading is $latestAge min old"
                else -> "OK: ${new.unit.format(latest.mgdl.toDouble())} ${new.unit.label}, ${state.ageMinutes()} min ago" +
                    state.loop?.let { "\nLoop reported ${it.ageMinutes()} min ago" }.orEmpty()
            }
        }
    }

    /** Test only the HTTPS tunnel to Dexcom; never send a login during a proxy test. */
    private fun testProxy() {
        val endpoint = runCatching { ProxyEndpoint.parse(shareProxy.text.toString()) }
            .getOrElse { proxyResult.text = "⚠ ${it.message}"; return }
        val server = Region.valueOf(selected(region)).baseUrl
        proxyResult.text = "Testing proxy…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    UrlConnectionTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000,
                        openConnection = { it.openConnection(endpoint.javaProxy()) })
                        .execute(HttpRequest.get(server))
                }
            }
            proxyResult.text = result.fold(
                onSuccess = { "Proxy tunnel reached Dexcom (HTTP ${it.status}; 404 here is expected)" },
                onFailure = { "⚠ Proxy failed: ${it.message ?: it.javaClass.simpleName}" },
            )
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
            shareProxy = e.getString("shareProxy") ?: s.shareProxy,
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
        const val REQUEST_BLUETOOTH = 2
        const val REQUEST_NOTIFICATIONS = 3
    }

    private val isDebuggable get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun radios(options: List<Pair<String, String>>, checked: String) = RadioGroup(this).apply {
        options.forEach { (key, text) ->
            addView(RadioButton(this@SettingsActivity).apply {
                id = View.generateViewId(); tag = key; this.text = text; isChecked = key == checked
            })
        }
    }

    private fun check(group: RadioGroup, key: String) = group.check(group.findViewWithTag<View>(key).id)

    /** Falls back to the default resolver when the stored one is not one of the presets. */
    private fun endpointOf(stored: String) =
        if (DohResolver.PRESETS.any { it.second == stored }) stored else DohResolver.CLOUDFLARE

    private fun selected(group: RadioGroup): String =
        group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String
            ?: (group.getChildAt(0).tag as String)

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Brand.TEXT); setPadding(0, dp(10), 0, 0)
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
