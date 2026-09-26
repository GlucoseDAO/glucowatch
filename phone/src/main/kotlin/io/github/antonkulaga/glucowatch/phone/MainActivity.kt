package io.github.antonkulaga.glucowatch.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.health.connect.HealthPermissions
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import glucowatch.core.GlucoseUnit
import glucowatch.core.NightscoutApi
import glucowatch.core.OnnxPredictor
import glucowatch.core.Predictors
import glucowatch.core.DemoData
import glucowatch.core.HeartSample
import glucowatch.core.Region
import glucowatch.core.Treatment
import glucowatch.core.formatAge
import glucowatch.core.lastManualBolus
import glucowatch.core.lastBasal
import glucowatch.core.InsulinKind
import glucowatch.core.CareLinkToken
import glucowatch.core.formatAmount
import glucowatch.core.lastDelta
import glucowatch.core.link.LinkSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The companion dashboard: today's glucose with the meals the user logged, the source, the
 * forecast model, and the paired watches. Views are built in code, as in the watch app, so the
 * phone app stays free of layout inflation and extra libraries.
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private var foregroundRefresh: Job? = null
    private val store by lazy { PhoneSettingsStore(this) }
    private val repository by lazy { PhoneRepository(this) }
    private val watches by lazy { PairedWatches(this) }
    private val modelStore by lazy { PhoneModelStore(this) }
    private val heartSource by lazy { HeartRateSource(this) }
    private val foodLog by lazy { FoodLog(this) }

    private lateinit var value: TextView
    private lateinit var valueUnit: TextView
    private lateinit var trendArrow: TextView
    private lateinit var statusPill: TextView
    private lateinit var age: TextView
    private lateinit var sourceLine: TextView
    private lateinit var chart: GlucoseChartView
    private lateinit var chartUnit: TextView
    private lateinit var rangeValue: TextView
    private lateinit var changeValue: TextView
    private lateinit var forecastValue: TextView
    private lateinit var mealSummary: TextView
    private lateinit var heartState: TextView
    private lateinit var heartLabel: TextView
    private lateinit var heartValue: TextView
    private lateinit var heartIcon: ImageView
    private lateinit var nowChip: TextView
    private lateinit var periodRow: LinearLayout
    private lateinit var insulinLine: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelInput: EditText
    private lateinit var modelFiles: LinearLayout
    private lateinit var removeButton: Button
    private lateinit var sections: List<LinearLayout>
    private lateinit var tabs: List<LinearLayout>
    private lateinit var scroller: ScrollView
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
    private lateinit var carelinkFields: List<View>
    private lateinit var carelinkStatus: TextView
    private lateinit var carelinkCountry: EditText
    private val therapySources = linkedMapOf<LinkSource, CheckBox>()
    private lateinit var watchList: LinearLayout
    private lateinit var pairButton: Button
    private lateinit var pairingText: TextView
    private lateinit var pairingCode: TextView
    private lateinit var pairingButtons: View

    /** What to do once the user allows Nearby devices. */
    private var afterBluetooth: (() -> Unit)? = null

    /** Where the camera app is writing the meal photo being taken, if any. */
    private var pendingPhoto: File? = null

    /** Heart rate for the chart's track and the current value: Health Connect, or demo data. */
    private var heartSamples: List<HeartSample> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("importCareLink", false)) {
            // adb writes to this debug app's private files through stdin; tokens never enter an Intent or BuildConfig.
            val file = filesDir.resolve("carelink-import.json")
            val token = file.takeIf { it.isFile }?.readText()?.let(CareLinkToken::decode)
            if (token?.subject != null) {
                PhoneCareLinkStore(this).save(token)
                store.save(store.load().copy(carelinkAccount = token.subject!!))
                file.delete()
            }
        }
        // Screenshot automation chooses a source without putting credentials in adb arguments.
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("source")?.let { name ->
                runCatching { LinkSource.valueOf(name.uppercase()) }.getOrNull()?.let { chosen ->
                    val previous = store.load()
                    if (previous.source != chosen) {
                        repository.clearCache()
                        store.save(previous.copy(source = chosen))
                    }
                }
            }
            intent.getStringExtra("also")?.let { names ->
                store.save(store.load().copy(alsoFrom = names.split(',')
                    .mapNotNull { runCatching { LinkSource.valueOf(it.uppercase()) }.getOrNull() }.toSet()))
            }
        }
        val s = store.load()

        val today = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val connect = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val model = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val watch = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sections = listOf(today, connect, model, watch)

        buildToday(today)
        buildConnect(connect, s)
        buildModel(model, s)
        buildWatch(watch)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(16))
            sections.forEach(::addView)
        }
        scroller = ScrollView(this).apply {
            addView(column)
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header())
            addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomNavigation())
            // Android 15 and later draw behind the status and navigation bars; keep the content clear of them.
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(root)
        showSection(0)
        updateModelStatus()

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
        refreshHeartRate()
        // Ask for Nearby devices only once a watch is paired; pairing asks too.
        if (watches.all().isNotEmpty()) withBluetooth { LinkService.update(this) }
    }

    // ---------------------------------------------------------------- chrome

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(10), dp(4), dp(6))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_launcher)
            contentDescription = getString(R.string.app_name)
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.app_name); textSize = 24f
            setTextColor(Brand.TEXT); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = android.content.res.ColorStateList.valueOf(Brand.MUTED)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "More"
            setOnClickListener(::showOverflow)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun showOverflow(anchor: View) = PopupMenu(this, anchor).apply {
        menu.add(0, MENU_REFRESH, 0, "Refresh now")
        menu.add(0, MENU_PRIVACY, 1, "Heart-rate privacy")
        menu.add(0, MENU_ABOUT, 2, "About ${getString(R.string.app_name)}")
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REFRESH -> scope.launch { render(repository.refresh()) }
                MENU_PRIVACY -> startActivity(Intent(this@MainActivity, HealthPrivacyActivity::class.java))
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("About ${getString(R.string.app_name)}")
                    .setMessage(
                        "GlucoPhone combines Dexcom Share, Nightscout and CareLink data, or shows demo data, and relays it to a " +
                            "paired GlucoWatch over Bluetooth. Your login, your meal photos and any model you " +
                            "import stay in this app's private storage.\n\n" +
                            "This is not a medical device. Keep using your CGM's official alerts."
                    )
                    .setPositiveButton("Close", null)
                    .show()
            }
            true
        }
    }.show()

    private fun bottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Brand.BACKGROUND)
            setPadding(0, dp(5), 0, dp(4))
        }
        val items = listOf(
            "Today" to R.drawable.ic_today,
            "Connect" to R.drawable.ic_connect,
            "Model" to R.drawable.ic_model,
            "Watch" to R.drawable.ic_watch,
        )
        tabs = items.mapIndexed { index, (text, drawable) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(4))
                addView(icon(drawable, Brand.MUTED, 22))
                addView(TextView(this@MainActivity).apply {
                    this.text = text; textSize = 11f; setTextColor(Brand.MUTED)
                    gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
                })
                contentDescription = text
                setOnClickListener { showSection(index) }
                bar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        return bar
    }

    private fun showSection(index: Int) {
        sections.forEachIndexed { i, section -> section.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, tab ->
            val color = if (i == index) Brand.TEXT else Brand.MUTED
            (tab.getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(color)
            (tab.getChildAt(1) as TextView).apply {
                setTextColor(color)
                typeface = if (i == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        scroller.scrollTo(0, 0)
    }

    // ---------------------------------------------------------------- today

    private fun buildToday(today: LinearLayout) {
        value = TextView(this).apply { textSize = 56f; setTextColor(Brand.GREEN); typeface = Typeface.DEFAULT_BOLD }
        valueUnit = TextView(this).apply { textSize = 14f; setTextColor(Brand.MUTED); setPadding(dp(6), 0, dp(10), dp(11)) }
        trendArrow = TextView(this).apply { textSize = 28f; setTextColor(Brand.GREEN); setPadding(0, 0, dp(10), dp(7)) }
        statusPill = TextView(this).apply {
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        age = TextView(this).apply { textSize = 13f; setTextColor(Brand.MUTED); gravity = Gravity.END }
        heartIcon = icon(R.drawable.ic_heart, Brand.HEART, 18)
        heartValue = TextView(this).apply {
            textSize = 20f; setTextColor(Brand.TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(5), 0, 0, 0)
        }
        sourceLine = hint("")

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.BOTTOM
                addView(value); addView(valueUnit); addView(trendArrow)
                addView(statusPill, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(11) })
                // The current heart rate is always shown, next to the reading's age; the track is extra.
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    addView(age)
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        setPadding(0, dp(3), 0, 0)
                        addView(heartIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
                        addView(heartValue)
                        contentDescription = "Heart rate"
                        setOnClickListener { connectHeartRate() }
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { bottomMargin = dp(9); leftMargin = dp(6) })
            })
            addView(sourceLine)
        }

        chart = GlucoseChartView(this).apply {
            photoLoader = ::loadPhoto
            onWindowChanged = { live, hours -> onChartMoved(live, hours) }
        }
        nowChip = TextView(this).apply {
            text = "Now ›"; textSize = 12f; setTextColor(Brand.TEXT); typeface = Typeface.DEFAULT_BOLD
            background = rounded(Brand.ROW, 12)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            visibility = View.GONE
            setOnClickListener { chart.goLive() }
        }
        insulinLine = hint("").apply { setPadding(0, dp(2), 0, 0) }
        chartUnit = TextView(this).apply { textSize = 12f; setTextColor(Brand.MUTED); gravity = Gravity.END }
        val chartHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(cardTitle("Glucose history"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nowChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
            addView(chartUnit)
        }

        rangeValue = metricValue()
        changeValue = metricValue()
        forecastValue = metricValue()
        val metrics = LinearLayout(this).apply {
            setPadding(0, dp(10), 0, dp(14))
            addView(metricColumn("Time in range", rangeValue))
            addView(divider())
            addView(metricColumn("30 min change", changeValue))
            addView(divider())
            addView(metricColumn("Forecast", forecastValue))
        }

        mealSummary = hint("")
        heartLabel = TextView(this).apply { textSize = 16f; setTextColor(Brand.TEXT) }
        heartState = TextView(this).apply { textSize = 14f; setTextColor(Brand.RED); gravity = Gravity.END }
        val heartRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Brand.ROW, 14)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            isClickable = true
            addView(icon(R.drawable.ic_heart, Brand.RED, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
            addView(icon(R.drawable.ic_plus, Brand.RED, 14), LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(12) })
            addView(heartLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(heartState)
            addView(icon(R.drawable.ic_chevron, Brand.MUTED, 13), LinearLayout.LayoutParams(dp(13), dp(13)).apply { leftMargin = dp(8) })
            setOnClickListener { toggleHeartTrack() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        mealSummary.setOnClickListener { showMeals() }

        listOf(hero, chartHeader, chart, periodPicker(), insulinLine, metrics,
            actionRow(R.drawable.ic_camera, "Photograph food", Brand.TEXT) { logMeal() },
            actionRow(R.drawable.ic_plus, "Log insulin", Brand.TEXT) { askInsulin() },
            mealSummary, heartRow,
            hint("For information only. ${getString(R.string.app_name)} is not a medical device; keep using your CGM's official alerts."))
            .forEach(today::addView)
    }

    private fun periodPicker(): View {
        periodRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) }
        PERIODS.forEach { hours ->
            periodRow.addView(TextView(this).apply {
                text = "${hours}h"; tag = hours; gravity = Gravity.CENTER; textSize = 13f
                setOnClickListener { chart.hours = hours.toDouble() }
            }, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        highlightPeriod(chart.hours)
        return periodRow
    }

    /** Lights the period nearest the chart's window, which pinching moves between the presets. */
    private fun highlightPeriod(hours: Double) {
        val nearest = PERIODS.minByOrNull { kotlin.math.abs(it - hours) }
        for (i in 0 until periodRow.childCount) {
            val child = periodRow.getChildAt(i) as TextView
            val on = child.tag == nearest && kotlin.math.abs(hours - nearest!!) < 0.5
            child.setTextColor(if (on) Brand.TEXT else Brand.MUTED)
            child.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun onChartMoved(live: Boolean, hours: Double) {
        nowChip.visibility = if (live) View.GONE else View.VISIBLE
        highlightPeriod(hours)
        updateMealSummary()
    }

    // ---------------------------------------------------------------- meals

    private fun logMeal() {
        val photo = foodLog.newPhotoFile()
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.photos", photo) }.getOrNull()
        if (uri == null || capture.resolveActivity(packageManager) == null) {
            // No camera app, or the photo file could not be prepared: carbs alone are still useful.
            askCarbs(null)
            return
        }
        capture.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        pendingPhoto = photo
        @Suppress("DEPRECATION")
        startActivityForResult(capture, REQUEST_FOOD_PHOTO)
    }

    /** Asks for the carbs that go with a meal, and only then keeps the photo. */
    private fun askCarbs(photo: File?) {
        val grams = EditText(this).apply {
            hint = "Carbohydrates in grams"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val note = EditText(this).apply { hint = "Note (optional)"; isSingleLine = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(grams); addView(note)
        }
        AlertDialog.Builder(this)
            .setTitle(if (photo != null) "Log this meal" else "Log a meal")
            .setView(body)
            .setPositiveButton("Save") { _, _ ->
                val carbs = grams.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (carbs == null || carbs < 0 || carbs > 1000) {
                    photo?.delete()
                    mealSummary.text = "Enter the carbohydrates as a number of grams, up to 1000."
                    mealSummary.visibility = View.VISIBLE
                    return@setPositiveButton
                }
                foodLog.add(carbs, note.text.toString(), photo)
                render(repository.state())
            }
            .setNegativeButton("Cancel") { _, _ -> photo?.delete() }
            .setOnCancelListener { photo?.delete() }
            .show()
    }

    /** Insulin the user gave, for a source such as Dexcom Share that carries glucose only. */
    private fun askInsulin() {
        val kind = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply { id = View.generateViewId(); text = "Bolus" })
            addView(RadioButton(this@MainActivity).apply { id = View.generateViewId(); text = "Basal" })
            check(getChildAt(0).id)
        }
        val units = EditText(this).apply {
            hint = "Insulin in units"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val note = EditText(this).apply { hint = "Note (optional)"; isSingleLine = true }
        AlertDialog.Builder(this)
            .setTitle("Log insulin")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(8), dp(22), 0)
                addView(kind); addView(units); addView(note)
            })
            .setPositiveButton("Save") { _, _ ->
                val dose = units.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (dose == null || !dose.isFinite() || dose <= 0 || dose > 100) {
                    mealSummary.text = "Enter the insulin as a number of units, up to 100."
                    return@setPositiveButton
                }
                foodLog.add(carbs = 0.0, note = note.text.toString(), photo = null, insulin = dose,
                    insulinKind = if (kind.checkedRadioButtonId == kind.getChildAt(1).id) InsulinKind.BASAL else InsulinKind.BOLUS)
                render(repository.state())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMeals() {
        val meals = foodLog.since(chartStart()).asReversed()
        if (meals.isEmpty()) return
        val clock = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val labels = meals.map { entry ->
            listOfNotNull(
                clock.format(Date(entry.timeMillis)),
                entry.carbs.takeIf { it > 0 }?.let { "${formatAmount(it, 0)} g" },
                entry.insulin.takeIf { it > 0 }?.let { "${if (entry.insulinKind == InsulinKind.BASAL) "Basal" else "Bolus"} ${formatAmount(it, 1)} U" },
                entry.note.takeIf { it.isNotEmpty() },
            ).joinToString("  ·  ")
        }
        AlertDialog.Builder(this)
            .setTitle("Logged meals and insulin")
            .setItems(labels.toTypedArray()) { _, index ->
                AlertDialog.Builder(this)
                    .setTitle("Remove ${labels[index]}?")
                    .setPositiveButton("Remove") { _, _ ->
                        foodLog.remove(meals[index].timeMillis)
                        render(repository.state())
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadPhoto(name: String) = foodLog.photoFile(name)?.let { BitmapFactory.decodeFile(it.path) }

    // ---------------------------------------------------------------- connect, model, watch

    private fun buildConnect(connect: LinearLayout, s: PhoneSettings) {
        source = radios(LinkSource.entries.map { it.name to PhoneSettings(source = it).sourceLabel }, s.source.name)
        username = field("Dexcom username / email", s.username, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = field("Password", s.password, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        region = radios(Region.entries.map { it.name to it.label }, s.region.name)
        nightscoutUrl = field("https://your-site.example", s.nightscoutUrl, InputType.TYPE_TEXT_VARIATION_URI)
        nightscoutToken = field("optional", s.nightscoutToken, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        nightscoutApi = radios(NightscoutApi.entries.map { it.name to it.label }, s.nightscoutApi.name)
        carelinkStatus = hint("")
        carelinkCountry = field("Two-letter country, e.g. DE", PhoneCareLinkStore(this).load()?.country ?: "DE", InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        PhoneSettings.THERAPY_SOURCES.forEach { of ->
            therapySources[of] = CheckBox(this).apply {
                text = s.label(of); setTextColor(Brand.TEXT); isChecked = of in s.alsoFrom
                buttonTintList = android.content.res.ColorStateList.valueOf(Brand.TEXT)
                setOnCheckedChangeListener { _, _ -> if (::carelinkFields.isInitialized) updateVisibility() }
            }
        }
        unit = radios(GlucoseUnit.entries.map { it.name to it.label }, s.unit.name)
        result = TextView(this).apply { textSize = 14f; setTextColor(Brand.TEXT); setPadding(0, dp(10), 0, 0) }

        shareFields = listOf(label("Account"), username, password, label("Region"), region,
            hint("Share must be on in the Dexcom app, with at least one follower."))
        nightscoutFields = listOf(
            label("Nightscout address"), nightscoutUrl,
            label("Token or API secret"), nightscoutToken, hint("A token with the readable role is safer. v3 needs a token."),
            label("API"), nightscoutApi,
        )
        carelinkFields = listOf(
            label("CareLink account"), carelinkStatus, label("Country"), carelinkCountry,
            Button(this).apply {
                text = "Sign in to CareLink"; Brand.style(this, true)
                setOnClickListener {
                    saveConnectionSettings()
                    startActivity(Intent(this@MainActivity, CareLinkSignInActivity::class.java)
                        .putExtra("country", carelinkCountry.text.toString().trim().uppercase()))
                }
            },
            hint("Sign in with a CareLink care partner account linked to your MiniMed pump. To share this combined chart, choose Phone app on the watch."),
        )
        connect.addView(card().apply {
            addView(cardTitle("Glucose source"))
            addView(hint("Choose a source. Your login stays in private app storage."))
            addView(source)
            addView(label("Additional insulin sources"))
            addView(hint("For example: Dexcom for glucose, CareLink for pump insulin. Basal, bolus and carbs appear on the same timeline."))
            therapySources.values.forEach(::addView)
            shareFields.forEach(::addView)
            nightscoutFields.forEach(::addView)
            carelinkFields.forEach(::addView)
            addView(label("Units")); addView(unit)
            addView(Button(this@MainActivity).apply { text = "Save & test"; Brand.style(this, true); setOnClickListener { saveAndTest() } })
            addView(result)
        })
    }

    private fun buildModel(model: LinearLayout, s: PhoneSettings) {
        predictor = radios(Predictors.all.map { it.id to it.displayName } + (OnnxPredictor.ID to "Imported ONNX"), s.predictorId)
        predictor.setOnCheckedChangeListener { _, _ -> saveModelChoice() }
        modelInput = field("owner/model  ·  or an .onnx link", "", InputType.TYPE_TEXT_VARIATION_URI)
        modelFiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modelStatus = hint("")

        model.addView(card().apply {
            addView(cardTitle("Glucose prediction"))
            addView(hint("The phone forecasts with this model, for itself and for a paired watch. " +
                "The watch must choose “Phone app model” for its forecast."))
            addView(predictor)
            addView(modelStatus)
        })
        model.addView(card().apply {
            addView(cardTitle("Your own ONNX model"))
            addView(hint("Name a Hugging Face model repo and pick one of its .onnx files, paste a link to a " +
                "single .onnx file, or import one from this phone. Up to 10 MB. Nothing is downloaded until you choose a file."))
            addView(label("Hugging Face repo or .onnx link"))
            addView(modelInput)
            addView(LinearLayout(this@MainActivity).apply {
                addView(Button(this@MainActivity).apply {
                    text = "Find .onnx files"; Brand.style(this, true); setOnClickListener { findModels() }
                }, weight())
                addView(Button(this@MainActivity).apply {
                    text = "Import a file"; Brand.style(this, false); setOnClickListener { chooseModelFile() }
                }, weight())
            })
            addView(modelFiles)
            addView(hint("The model takes float32 [1,12] or [1,24] five-minute mg/dL values, oldest first, and " +
                "returns float32 [1,1–24] future mg/dL values, one every five minutes. Dense layers and simple " +
                "activations are supported; other graphs are refused on import."))
            removeButton = Button(this@MainActivity).apply {
                text = "Remove imported model"; Brand.style(this, false); setOnClickListener { removeModel() }
            }
            addView(removeButton)
        })
    }

    private fun buildWatch(watch: LinearLayout) {
        watchList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pairButton = Button(this).apply { text = "Pair a watch"; Brand.style(this, true); setOnClickListener { startPairing() } }
        pairingText = TextView(this).apply { textSize = 14f; setTextColor(Brand.TEXT); setPadding(0, dp(8), 0, 0) }
        pairingCode = TextView(this).apply {
            textSize = 34f; gravity = Gravity.CENTER; setTextColor(Brand.TEXT); typeface = Typeface.DEFAULT_BOLD
        }
        pairingButtons = LinearLayout(this).apply {
            addView(Button(this@MainActivity).apply { text = "Codes match"; Brand.style(this, true); setOnClickListener { confirmPairing() } }, weight())
            addView(Button(this@MainActivity).apply {
                text = "Cancel"; Brand.style(this, false)
                setOnClickListener { PairingWindow.close(); LinkService.update(this@MainActivity) }
            }, weight())
        }
        watch.addView(card().apply {
            addView(cardTitle("Paired watches"))
            addView(hint("Your watch asks this phone for fresh readings and a forecast over the encrypted Bluetooth link."))
            addView(watchList); addView(pairButton); addView(pairingText); addView(pairingCode); addView(pairingButtons)
        })
    }

    // ---------------------------------------------------------------- model import

    private fun findModels() {
        val typed = modelInput.text.toString()
        modelFiles.removeAllViews()
        modelStatus.text = "Looking up $typed…"
        scope.launch {
            runCatching { modelStore.listHuggingFace(typed) }.fold(
                onSuccess = { listing -> showFiles(listing) },
                onFailure = { modelStatus.text = it.message ?: "Could not read that repo" },
            )
        }
    }

    private fun showFiles(listing: PhoneModelStore.Listing) {
        modelStatus.text = "${listing.model.label}: ${listing.files.size} .onnx file" +
            (if (listing.files.size == 1) "" else "s") + ". Tap one to import it."
        listing.files.take(MAX_LISTED_FILES).forEach { file ->
            modelFiles.addView(actionRow(R.drawable.ic_model, file, Brand.TEXT) {
                modelStatus.text = "Downloading and checking $file…"
                modelFiles.removeAllViews()
                scope.launch { installModel { modelStore.importHuggingFace(listing.model, file) } }
            })
        }
    }

    private fun chooseModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/onnx", "*/*"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_MODEL)
    }

    private suspend fun installModel(action: suspend () -> String) {
        val outcome = runCatching { action() }
        modelStatus.text = outcome.fold(
            onSuccess = { "Ready: $it" },
            onFailure = { "Could not use this model: ${it.message ?: it.javaClass.simpleName}" },
        )
        removeButton.visibility = if (modelStore.available) View.VISIBLE else View.GONE
        if (outcome.isSuccess) {
            for (i in 0 until predictor.childCount) {
                val radio = predictor.getChildAt(i) as RadioButton
                if (radio.tag == OnnxPredictor.ID) radio.isChecked = true
            }
            store.save(store.load().copy(predictorId = OnnxPredictor.ID))
            render(repository.state())
        }
    }

    private fun saveModelChoice() {
        val selected = selected(predictor)
        if (selected == OnnxPredictor.ID && !modelStore.available) {
            modelStatus.text = "Import a compatible ONNX file first."
            (predictor.getChildAt(0) as RadioButton).isChecked = true
            return
        }
        store.save(store.load().copy(predictorId = selected))
        render(repository.state())
    }

    private fun updateModelStatus() {
        modelStatus.text = modelStore.origin?.let { "Imported from $it" }
            ?: "No ONNX model imported. Linear trend is ready to use."
        removeButton.visibility = if (modelStore.available) View.VISIBLE else View.GONE
    }

    private fun removeModel() {
        modelStore.remove()
        (predictor.getChildAt(0) as RadioButton).isChecked = true
        store.save(store.load().copy(predictorId = Predictors.all.first().id))
        modelFiles.removeAllViews()
        updateModelStatus()
        render(repository.state())
    }

    // ---------------------------------------------------------------- lifecycle and results

    override fun onStart() {
        super.onStart()
        foregroundRefresh = scope.launch {
            while (isActive) {
                render(repository.refresh(maxAgeMs = 60_000))
                delay(60_000)
            }
        }
    }

    override fun onStop() {
        foregroundRefresh?.cancel()
        foregroundRefresh = null
        super.onStop()
    }

    override fun onDestroy() {
        PairingWindow.listener = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_HEART) { refreshHeartRate(); return }
        if (requestCode != REQUEST_BLUETOOTH) return
        val action = afterBluetooth
        afterBluetooth = null
        if (LinkService.hasPermission(this)) action?.invoke()
        else result.text = "⚠ The watch can only connect with the Nearby devices permission"
    }

    @Deprecated("The platform result API is enough for these two one-off imports")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_MODEL -> {
                val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
                modelStatus.text = "Checking model…"
                modelFiles.removeAllViews()
                scope.launch { installModel { modelStore.importFile(uri) } }
            }
            REQUEST_FOOD_PHOTO -> {
                val photo = pendingPhoto
                pendingPhoto = null
                if (resultCode == RESULT_OK && photo != null && photo.isFile) askCarbs(photo)
                else photo?.delete()
            }
        }
    }

    /** The hero's heart: asks for access when it is missing, otherwise explains where it comes from. */
    private fun connectHeartRate() {
        when {
            store.load().source == LinkSource.DEMO -> startActivity(Intent(this, HealthPrivacyActivity::class.java))
            !heartSource.supported() -> AlertDialog.Builder(this)
                .setTitle("Heart rate")
                .setMessage("Health Connect heart rate is available on Android 14 and later.")
                .setPositiveButton("Close", null).show()
            !heartSource.permitted() -> if (Build.VERSION.SDK_INT >= 34)
                requestPermissions(arrayOf(HealthPermissions.READ_HEART_RATE), REQUEST_HEART)
            else -> startActivity(Intent(this, HealthPrivacyActivity::class.java))
        }
    }

    /** Adds or removes the heart-rate track on the chart; asks for access first if it is missing. */
    private fun toggleHeartTrack() {
        val demo = store.load().source == LinkSource.DEMO
        if (!demo && heartSource.supported() && !heartSource.permitted()) {
            connectHeartRate()
            return
        }
        val settings = store.load()
        store.save(settings.copy(heartTrack = !settings.heartTrack))
        chart.showHeart = !settings.heartTrack
        updateHeartRow()
    }

    private fun updateHeartRow() {
        val on = store.load().heartTrack
        heartLabel.text = if (on) "Heart rate track" else "Add heart rate track"
        heartState.text = if (on) "On" else "Off"
        heartState.setTextColor(if (on) Brand.TEXT else Brand.RED)
    }

    /**
     * Loads heart rate for the value in the hero and the chart's track. Demo data has its own,
     * so the demo shows both with no watch and no Health Connect.
     */
    private fun refreshHeartRate() {
        updateHeartRow()
        val settings = store.load()
        chart.showHeart = settings.heartTrack
        val now = System.currentTimeMillis()
        if (settings.source == LinkSource.DEMO) {
            showHeart(DemoData.heartRate(now, HEART_HOURS))
            return
        }
        if (!heartSource.supported() || !heartSource.permitted()) {
            showHeart(emptyList())
            return
        }
        scope.launch {
            showHeart(runCatching { heartSource.samples(now - HEART_HOURS * 3_600_000L) }.getOrDefault(emptyList()))
        }
    }

    private fun showHeart(samples: List<HeartSample>) {
        heartSamples = samples
        chart.heart = samples
        val summary = HeartRateSource.summarize(samples)
        // A value more than 30 minutes old is not "current"; show it greyed rather than as now.
        val fresh = summary != null && summary.ageMinutes <= 30
        heartValue.text = summary?.bpm?.toString() ?: "—"
        heartValue.setTextColor(if (fresh) Brand.TEXT else Brand.MUTED)
        heartIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (fresh) Brand.HEART else Brand.MUTED)
    }

    // ---------------------------------------------------------------- rendering

    private fun render(state: PhoneState) {
        val latest = state.latest
        val u = state.settings.unit
        val now = System.currentTimeMillis()
        chart.readings = state.readings
        chart.unit = u
        chart.forecast = repository.forecast(state, 30)
        val logged = foodLog.all()
        chart.food = logged.filter { it.isMeal }
        // Insulin logged on the phone joins what Nightscout reported, so a Share user has some too.
        chart.treatments = (state.treatments + logged.filter { it.insulin > 0 }
            .map { Treatment(it.timeMillis, insulin = it.insulin, insulinKind = it.insulinKind) }).sortedBy { it.timeMillis }
        updateInsulinLine(state, now)
        chartUnit.text = u.label
        valueUnit.text = u.label

        updateMealSummary()

        if (latest == null) {
            value.text = "—"
            value.setTextColor(Brand.MUTED)
            trendArrow.text = ""
            age.text = ""
            statusPill.visibility = View.GONE
            sourceLine.text = if (state.settings.source == LinkSource.DEMO) "Demo data" else "${state.settings.sourceLabel} · No glucose readings yet"
            rangeValue.text = "—"
            changeValue.text = "—"
            forecastValue.text = "—"
        } else {
            val minutes = ((now - latest.timeMillis) / 60_000).coerceAtLeast(0)
            // Match the watch's ten-minute freshness limit.
            val stale = now - latest.timeMillis > 10 * 60_000L
            val readingColor = if (stale) Brand.MUTED else Brand.glucoseColor(latest.mgdl)
            value.text = u.format(latest.mgdl.toDouble())
            value.setTextColor(readingColor)
            trendArrow.text = if (stale) "" else latest.trend.arrow
            trendArrow.setTextColor(readingColor)
            age.text = if (minutes == 0L) "just now" else "${formatAge(minutes)} ago"
            statusPill.visibility = View.VISIBLE
            val status = when {
                stale -> "STALE"
                latest.mgdl < Brand.TARGET_LOW -> "LOW"
                latest.mgdl > Brand.TARGET_HIGH -> "HIGH"
                else -> "IN RANGE"
            }
            statusPill.text = status
            statusPill.setTextColor(readingColor)
            statusPill.background = outline(readingColor)
            sourceLine.text = if (stale) "${state.settings.sourceLabel} · Last known glucose\n" +
                "${state.settings.label(state.settings.source)} has no recent glucose readings."
            else "${state.settings.sourceLabel}  ·  ${latest.trend.description.ifEmpty { "trend unavailable" }}" +
                (state.readings.lastDelta()?.let { "  ·  ${u.formatDelta(it)} in 5 min" } ?: "")

            val day = state.readings.filter { it.timeMillis >= now - 24 * 3_600_000L }
            rangeValue.text = if (day.isEmpty()) "—"
                else "${100 * day.count { it.mgdl in Brand.TARGET_LOW..Brand.TARGET_HIGH } / day.size}%"
            rangeValue.setTextColor(Brand.TEXT)
            val before = state.readings.asReversed().firstOrNull { latest.timeMillis - it.timeMillis >= 25 * 60_000L }
            val change = before?.let { (latest.mgdl - it.mgdl).toDouble() }
            changeValue.text = if (stale) "—" else change?.let(u::formatDelta) ?: "—"
            changeValue.setTextColor(Brand.TEXT)
            val forecast = chart.forecast?.points?.lastOrNull()
            forecastValue.text = forecast?.let { u.format(it.mgdl) } ?: "—"
            forecastValue.setTextColor(forecast?.let { Brand.glucoseColor(it.mgdl.toInt()) } ?: Brand.TEXT)
        }
        if (state.settings.predictorId == OnnxPredictor.ID && !modelStore.available) {
            modelStatus.text = "Import a compatible ONNX model to forecast with it."
        }
        state.lastError?.takeIf { state.settings.source != LinkSource.DEMO }?.let { sourceLine.append("\n⚠ $it") }
    }

    /** The start of what the chart shows, wherever it has been dragged. */
    private fun chartStart() = System.currentTimeMillis() - (chart.hours * 3_600_000).toLong()

    private fun updateMealSummary() {
        val entries = foodLog.since(chartStart())
        val carbs = entries.sumOf { it.carbs }
        val bolus = entries.filter { it.insulinKind == InsulinKind.BOLUS }.sumOf { it.insulin }
        val basal = entries.filter { it.insulinKind == InsulinKind.BASAL }.sumOf { it.insulin }
        val span = formatAmount(chart.hours, 0)
        mealSummary.text = if (entries.isEmpty()) "Nothing logged on this phone in the last $span h."
            else listOfNotNull(
                carbs.takeIf { it > 0 }?.let { "${formatAmount(it, 0)} g" },
                bolus.takeIf { it > 0 }?.let { "bolus ${formatAmount(it, 1)} U" },
                basal.takeIf { it > 0 }?.let { "basal ${formatAmount(it, 1)} U" },
            ).joinToString(" · ") + " logged in the last $span h · tap to edit"
    }

    /**
     * What the loop last reported, and the last bolus: Nightscout's, or one logged here. Hidden when
     * there is none, as with Dexcom Share and nothing logged.
     */
    private fun updateInsulinLine(state: PhoneState, now: Long) {
        val loop = state.loop?.takeUnless { it.isStale(now) }
        val bolus = chart.treatments.lastManualBolus(now)
        val parts = listOfNotNull(
            loop?.iob?.let { "Insulin on board ${formatAmount(it, 1)} U" },
            loop?.cob?.let { "carbs on board ${formatAmount(it, 0)} g" },
            bolus?.let { "last bolus ${formatAmount(it.insulin, 1)} U, ${formatAge((now - it.timeMillis) / 60_000)} ago" },
            chart.treatments.lastBasal(now)?.let { "${it.basalDescription()}, ${formatAge((now - it.timeMillis) / 60_000)} ago" },
        )
        insulinLine.text = parts.joinToString("  ·  ").replaceFirstChar { it.uppercase() }
        insulinLine.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- source, pairing

    private fun updateVisibility() {
        val chosen = LinkSource.valueOf(selected(source))
        fun used(of: LinkSource) = chosen == of || (chosen != LinkSource.DEMO && therapySources[of]?.isChecked == true)
        therapySources.forEach { (of, checkbox) -> checkbox.isEnabled = chosen != LinkSource.DEMO && chosen != of }
        shareFields.forEach { it.visibility = if (chosen == LinkSource.SHARE) View.VISIBLE else View.GONE }
        nightscoutFields.forEach { it.visibility = if (used(LinkSource.NIGHTSCOUT)) View.VISIBLE else View.GONE }
        carelinkFields.forEach { it.visibility = if (used(LinkSource.CARELINK)) View.VISIBLE else View.GONE }
        carelinkStatus.text = PhoneCareLinkStore(this).load()?.let { "Signed in (${it.country})" } ?: "Not signed in"
    }

    private fun saveConnectionSettings(): PhoneSettings {
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
            alsoFrom = therapySources.filterValues { it.isChecked }.keys.toSet(),
        )
        if (new.accountKey != old.accountKey) repository.clearCache()
        store.save(new)
        // Demo data brings its own heart rate; a real source reads Health Connect.
        if (new.source != old.source) refreshHeartRate()
        return new
    }

    private fun saveAndTest() {
        val new = saveConnectionSettings()
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
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply { text = watch.name; textSize = 16f; setTextColor(Brand.TEXT) }, weight())
                addView(Button(this@MainActivity).apply {
                    text = "Forget"; Brand.style(this, false)
                    setOnClickListener {
                        watches.remove(watch.id)
                        LinkService.update(this@MainActivity)
                        updateWatches()
                    }
                })
            })
        }
    }

    // ---------------------------------------------------------------- small builders

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

    private fun icon(drawable: Int, color: Int, size: Int) = ImageView(this).apply {
        setImageResource(drawable)
        imageTintList = android.content.res.ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** A tappable row with an icon and a label, used for the food button and the found models. */
    private fun actionRow(drawable: Int, text: String, color: Int, action: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Brand.ROW, 14)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        isClickable = true
        addView(icon(drawable, color, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) })
        addView(TextView(this@MainActivity).apply { this.text = text; textSize = 15f; setTextColor(color) }, weight())
        addView(icon(R.drawable.ic_chevron, Brand.MUTED, 13))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Brand.TEXT); setPadding(0, dp(14), 0, 0)
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Brand.MUTED); setPadding(0, dp(6), 0, 0)
    }

    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun metricValue() = TextView(this).apply {
        textSize = 24f; setTextColor(Brand.TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, 0)
    }

    private fun metricColumn(title: String, number: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 12f; setTextColor(Brand.MUTED); gravity = Gravity.CENTER
        })
        addView(number)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Brand.MUTED and 0x38FFFFFF)
        layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun outline(color: Int) = GradientDrawable().apply {
        setColor(0)
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1).coerceAtLeast(1), color)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Brand.SURFACE, 20)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun cardTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 17f; setTextColor(Brand.TEXT); typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val REQUEST_BLUETOOTH = 1
        const val REQUEST_MODEL = 2
        const val REQUEST_HEART = 3
        const val REQUEST_FOOD_PHOTO = 4

        /** The period presets under the chart; pinching moves freely between them. */
        val PERIODS = listOf(3, 6, 12, 24)

        /** How much heart rate to load: enough for the widest window the chart can be pinched to. */
        const val HEART_HOURS = 48

        const val MENU_REFRESH = 1
        const val MENU_PRIVACY = 2
        const val MENU_ABOUT = 3

        /** A repo can hold dozens of exports; more than this is noise in a phone-sized list. */
        const val MAX_LISTED_FILES = 12
    }
}
