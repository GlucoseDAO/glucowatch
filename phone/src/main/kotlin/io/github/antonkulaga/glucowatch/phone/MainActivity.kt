package io.github.antonkulaga.glucowatch.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ContentValues
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
import android.text.method.PasswordTransformationMethod
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
import glucowatch.core.ExportCollection
import glucowatch.core.ExportContent
import glucowatch.core.ExportNames
import glucowatch.core.InsulinAnalytics
import glucowatch.core.MergedHealthExport
import glucowatch.core.WorkoutAnnotations
import glucowatch.core.ExportDates
import glucowatch.core.ExportRow
import glucowatch.core.ExportCsv
import glucowatch.core.HistoricalCsv
import glucowatch.core.ExportPortal
import glucowatch.core.ExportRequest
import glucowatch.core.ExportWindow
import glucowatch.core.GlucoseUnit
import glucowatch.core.LatestDevice
import glucowatch.core.NightscoutApi
import glucowatch.core.OnnxPredictor
import glucowatch.core.PredictionChecks
import glucowatch.core.Predictors
import glucowatch.core.HuggingFaceModel
import glucowatch.core.WorkoutReportRange
import glucowatch.core.DemoData
import glucowatch.core.HeartSample
import glucowatch.core.Region
import glucowatch.core.Treatment
import glucowatch.core.formatAge
import glucowatch.core.lastManualBolus
import glucowatch.core.lastBasal
import glucowatch.core.InsulinKind
import glucowatch.core.CareLinkToken
import glucowatch.core.ImportedConfiguration
import glucowatch.core.formatAmount
import glucowatch.core.lastDelta
import glucowatch.core.link.LinkSource
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
    private val historyStore by lazy { HistoryExportStore(this) }
    private val workoutStore by lazy { WorkoutStore(this) }
    private val exportDownloadFolder by lazy { ExportDownloadFolder(this) }
    private var folderWatch: Job? = null
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
    private lateinit var changeNote: TextView
    private lateinit var forecastValue: TextView
    private lateinit var forecastNote: TextView
    private lateinit var mealSummary: TextView
    private lateinit var heartState: TextView
    private lateinit var heartLabel: TextView
    private lateinit var heartValue: TextView
    private lateinit var heartIcon: ImageView
    private lateinit var nowChip: TextView
    private lateinit var periodRow: LinearLayout
    private lateinit var insulinLine: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelForecastNote: TextView
    private lateinit var modelInput: EditText
    private lateinit var modelToken: EditText
    private lateinit var otherModelToken: EditText
    private lateinit var modelMenu: LinearLayout
    private var modelPage = "menu"
    private var modelInstalling = false
    private var modelImport: Job? = null
    private var modelLookup: Job? = null
    private var configurationImport: Job? = null
    private lateinit var configUrl: EditText
    private lateinit var configStatus: TextView
    private lateinit var modelFiles: LinearLayout
    private lateinit var ownFiles: LinearLayout
    private lateinit var importCard: LinearLayout
    private lateinit var catalogPanel: LinearLayout
    private lateinit var ownPanel: LinearLayout
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
    private lateinit var latestDevice: RadioGroup
    private lateinit var deviceSince: EditText
    private lateinit var predictor: RadioGroup
    private lateinit var result: TextView
    private lateinit var shareFields: List<View>
    private lateinit var dexcomNotifications: CheckBox
    private lateinit var notificationFields: List<View>
    private lateinit var notificationAccess: Button
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
    private lateinit var confirmPairButton: Button

    /** What to do once the user allows Nearby devices. */
    private var afterBluetooth: (() -> Unit)? = null

    /** Where the camera app is writing the meal photo being taken, if any. */
    private var pendingPhoto: File? = null

    /** Bytes waiting for the system save dialog, and the sentence to show once they are written. */
    private var pendingExport: ByteArray? = null
    private var pendingExportNote: String = ""
    private data class HistoryExport(val window: ExportWindow, val pdf: Boolean, val settings: PhoneSettings,
        var dexcom: List<ExportRow>? = null, var carelink: List<ExportRow>? = null,
        val startedAt: Long = System.currentTimeMillis(), val includeActivities: Boolean = false, val eventWorkoutIds: List<String> = emptyList(), val csv: Boolean = true) {
        val formatLabel get() = if (pdf && csv) "PDF and CSV files" else if (pdf) "PDF report" else "CSV"
        val collection = ExportCollection(window.content, settings.latestDevice ?: window.device, window)
        var pumpConfirmed = false
        var confirmingPump = false
    }
    private var historyExport: HistoryExport? = null
    private var restoringHistory = false
    private var queuedWorkoutReport: Intent? = null
    private var queuedHistoryUri: android.net.Uri? = null
    private var historyInputPlan: HistoryExport? = null
    private var historyPortalSource: String? = null
    private var historyStatus: TextView? = null
    private var historyDialog: AlertDialog? = null
    private var historyActions: View? = null
    private var historyWriting = false
    private val historyLookups = mutableMapOf<String, Job>()
    private val historyTempFiles = mutableSetOf<File>()

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
            if (intent.hasExtra("dexcomNotifications")) {
                val old = store.load()
                val enabled = intent.getBooleanExtra("dexcomNotifications", old.dexcomNotifications)
                if (old.dexcomNotifications != enabled) {
                    repository.clearCache()
                    store.save(old.copy(dexcomNotifications = enabled))
                }
            }
            intent.getStringExtra("also")?.let { names ->
                store.save(store.load().copy(alsoFrom = names.split(',')
                    .mapNotNull { runCatching { LinkSource.valueOf(it.uppercase()) }.getOrNull() }.toSet()))
            }
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("importDebugModel", false)) {
            runCatching { modelStore.importDebugBundle() }.onSuccess {
                store.save(store.load().copy(predictorId = OnnxPredictor.ID))
            }.onFailure { android.util.Log.w("GlucoPhone", "Debug model import failed", it) }
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
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            android.window.OnBackInvokedCallback { if (!navigateModelBack()) finish() },
        )
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
        if (intent.action == CsvImportActivity.ACTION_IMPORT) {
            queuedHistoryUri = intent.data
            setIntent(Intent(intent).setAction(Intent.ACTION_MAIN).setData(null))
        }
        restoreHistoryExport()
        if (intent.action == WorkoutImportActivity.ACTION_REPORT) {
            queuedWorkoutReport = Intent(intent)
            setIntent(Intent(intent).setAction(Intent.ACTION_MAIN))
        }
        refreshHeartRate()
        scope.launch {
            try { withContext(Dispatchers.IO) {
                ReportFiles.ensureLayout(this@MainActivity)
                ReportFiles.migrateLegacy(this@MainActivity)
            } } catch(e: CancellationException) {throw e}
            catch(e: Exception) { exportError("Could not prepare Downloads/GlucoPhone: ${e.message}") }
        }
        // Ask for Nearby devices only once a watch is paired; pairing asks too.
        if (watches.all().isNotEmpty()) withBluetooth { LinkService.update(this) }
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("exportPortalPreview", false)) {
            next.putExtra("exportPortalPreview", true).putExtra("externalFixture", intent.getBooleanExtra("externalFixture", false))
                .putExtra("fixtureBase", intent.getStringExtra("fixtureBase"))
        }
        setIntent(next)
        if (next.action == WorkoutImportActivity.ACTION_REPORT) {
            beginWorkoutReport(next)
            setIntent(Intent(next).setAction(Intent.ACTION_MAIN))
        }
        if (next.action == CsvImportActivity.ACTION_IMPORT) {
            queuedHistoryUri = next.data
            setIntent(Intent(next).setAction(Intent.ACTION_MAIN).setData(null))
            if (historyExport == null) restoreHistoryExport() else consumeSharedHistoryCsv()
        }
    }

    private fun restoreHistoryExport() {
        if (restoringHistory || historyExport != null) return
        restoringHistory = true
        scope.launch {
            try {
                val settings = store.load()
                val restored = withContext(Dispatchers.IO) {
                    historyStore.load(settings)?.let { saved ->
                        val rows = saved.files.mapValues { (portal, files) ->
                            files.flatMap { file -> HistoricalCsv.parse(file.text, portal.source, java.time.ZoneId.systemDefault(), file.order) }
                                .filter { saved.window.contains(it.timeMillis, java.time.ZoneId.systemDefault()) }
                                .distinctBy { it.identity() }.sortedBy { it.timeMillis }
                        }
                        saved to rows
                    }
                }
                if (restored != null && historyExport == null && !isFinishing) {
                    beginHistoryExport(restored.first.window, restored.first.pdf, restored.second, resumeOnly = true, startedAt = restored.first.createdAt,
                        includeActivities = restored.first.includeActivities, eventWorkoutIds = restored.first.eventWorkoutIds, csv = restored.first.csv, restoredChunks = restored.first.files.mapValues { (_, files) -> files.map { it.index } })
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { historyStore.clear() }
            finally {
                restoringHistory = false
                if (isActive) {
                    queuedWorkoutReport?.let { id -> queuedWorkoutReport = null; beginWorkoutReport(id) }
                    if (queuedHistoryUri != null) consumeSharedHistoryCsv()
                    else if (historyExport != null) continueHistoryDownload()
                }
            }
        }
    }

    private fun consumeSharedHistoryCsv() {
        val uri = queuedHistoryUri ?: return
        queuedHistoryUri = null
        if (historyExport == null) { exportError("Start an export before opening or sharing a portal CSV."); return }
        historyInputPlan = null; historyPortalSource = null
        importHistoryCsv({ contentResolver.openInputStream(uri)?.use { readExportBytes(it).toString(Charsets.UTF_8) }
            ?: error("Could not open CSV") })
    }

    private fun openDownloads() {
        val folder = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FGlucoPhone")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(folder, "vnd.android.document/directory")) }
            .recoverCatching { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            .onFailure { exportError("No file manager can open Downloads on this device.") }
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
        addView(Button(this@MainActivity).apply {
            text = "Downloads"; textSize = 12f; contentDescription = "Open Downloads folder"
            Brand.style(this, false); setOnClickListener { openDownloads() }
        }, LinearLayout.LayoutParams(-2, dp(44)))
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
        menu.add(0, MENU_EXPORT, 1, "Export data")
        menu.add(0, MENU_WORKOUT, 2, "Workouts")
        menu.add(0, MENU_INSULIN_ANALYSIS, 3, "Insulin analysis")
        menu.add(0, MENU_CHECK, 2, "Check prediction")
        menu.add(0, MENU_HISTORY, 3, "Prediction history")
        menu.add(0, MENU_PRIVACY, 4, "Heart-rate privacy")
        menu.add(0, MENU_ABOUT, 5, "About ${getString(R.string.app_name)}")
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REFRESH -> scope.launch { render(repository.refresh()) }
                MENU_EXPORT -> exportData()
                MENU_INSULIN_ANALYSIS -> showInsulinAnalysis()
                MENU_WORKOUT -> startActivityForResult(Intent(this@MainActivity, WorkoutImportActivity::class.java), REQUEST_WORKOUT)
                MENU_CHECK -> checkPrediction()
                MENU_HISTORY -> startActivity(Intent(this@MainActivity, PredictionHistoryActivity::class.java))
                MENU_PRIVACY -> startActivity(Intent(this@MainActivity, HealthPrivacyActivity::class.java))
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("About ${getString(R.string.app_name)}")
                    .setMessage(
                        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n" +
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

    private fun showInsulinAnalysis() {
        startActivity(Intent(this, InsulinAnalysisActivity::class.java))
    }

    private fun exportData() {
        val today = ExportNames.lastCompletedDay(LocalDate.now())
        val draft = getSharedPreferences("export-draft", MODE_PRIVATE)
        val savedDays = draft.getInt("days", 14).coerceIn(1, 90)
        val oldAnchor = runCatching { LocalDate.parse(draft.getString("anchor", null)) }.getOrDefault(today)
        val oldEnd = if (draft.getString("anchorKind", "end") == "start") oldAnchor.plusDays(savedDays - 1L) else oldAnchor
        val savedEnd = runCatching { LocalDate.parse(draft.getString("end", null)) }.getOrDefault(oldEnd).coerceAtMost(today)
        var dates = runCatching {
            ExportDates(LocalDate.parse(draft.getString("start", null)).also { require(it <= savedEnd) }, savedEnd)
        }.getOrElse { ExportDates.endingOn(savedEnd, savedDays) }
        var keepStart = draft.getBoolean("keepStart", false)
        val days = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(dates.days.toString()); hint = "1 to 90 days"
            contentDescription = "Number of export days"
        }
        val startDate = Button(this).apply { Brand.style(this, false); contentDescription = "Choose export start date" }
        val endDate = Button(this).apply { Brand.style(this, false); contentDescription = "Choose export end date" }
        val span = hint("")
        var updatingDays = false
        fun renderDates(updateDays: Boolean = false) {
            startDate.text = dates.start.toString(); endDate.text = dates.end.toString()
            span.text = "${dates.start} to ${dates.end} · ${dates.days} ${if (dates.days == 1) "day" else "days"}, including both dates"
            if (updateDays) {
                updatingDays = true
                days.setText(dates.days.toString()); days.error = null
                updatingDays = false
            }
        }
        startDate.setOnClickListener {
            pickDate(dates.start, dates.end.minusDays(89), dates.end.coerceAtMost(today)) {
                dates = dates.withStart(it); keepStart = true; renderDates(updateDays = true)
            }
        }
        endDate.setOnClickListener {
            pickDate(dates.end, dates.start, dates.start.plusDays(89).coerceAtMost(today)) {
                dates = dates.withEnd(it); keepStart = false; renderDates(updateDays = true)
            }
        }
        days.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (updatingDays) return
                val countDays = s?.toString()?.toIntOrNull()
                if (countDays == null || countDays !in 1..90) {
                    span.text = "Enter 1 to 90 days, or choose the dates on the calendars."
                    return
                }
                dates = dates.withDays(countDays, keepStart); renderDates()
                days.error = if (dates.end.isAfter(today)) "This interval ends after the last completed day. Choose fewer days or an earlier start." else null
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val draftContent = runCatching { ExportContent.valueOf(draft.getString("content", "").orEmpty()) }.getOrDefault(ExportContent.SENSOR_AND_PUMP)
        val content = radios(ExportContent.entries.map { it.name to it.label }, draftContent.name)
        val pdfFormat = CheckBox(this).apply { text = "PDF report"; setTextColor(Brand.TEXT); isChecked = draft.getBoolean("pdf", false) }
        val csvFormat = CheckBox(this).apply { text = "CSV"; setTextColor(Brand.TEXT); isChecked = !draft.getBoolean("independentFormats", false) || draft.getBoolean("csv", true) }
        val format = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(pdfFormat); addView(csvFormat) }
        val incomplete = CheckBox(this).apply {
            text = "Allow incomplete data before the latest device started"
            setTextColor(Brand.TEXT)
            isChecked = draft.getBoolean("incomplete", false)
            visibility = if (draftContent == ExportContent.SENSOR_AND_PUMP) View.VISIBLE else View.GONE
        }
        val activities = CheckBox(this).apply {
            text = "Include imported activities"; setTextColor(Brand.TEXT); isChecked = draft.getBoolean("activities", true)
        }
        content.setOnCheckedChangeListener { _, _ -> incomplete.visibility = if (selected(content) == ExportContent.SENSOR_AND_PUMP.name) View.VISIBLE else View.GONE }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(8))
            addView(hint("Exports end on a completed day. CareLink does not export today; the latest selectable date is $today."))
            addView(label("Days (1 to 90)")); addView(days)
            addView(Button(this@MainActivity).apply {
                text = "Three months (90 days)"; Brand.style(this, false)
                setOnClickListener { dates = ExportDates.endingOn(dates.end, 90); keepStart = false; renderDates(updateDays = true) }
            })
            addView(hint("For more than 30 days, collect each displayed Medtronic interval before Dexcom opens. All files are merged into one export."))
            addView(hint("Changing Days keeps the date you chose most recently and moves the other. Both dates are included."))
            addView(label("Start date")); addView(startDate)
            addView(label("End date")); addView(endDate); addView(span)
            addView(label("Data")); addView(content); addView(incomplete)
            addView(label("Format")); addView(format)
            addView(hint("Select PDF, CSV, or both. CSV contains the merged native events and calculated response points."))
            addView(activities)
            addView(hint("Uses saved FIT workouts overlapping these dates. Workouts are not downloaded during export."))
        }
        renderDates()
        val dialog = AlertDialog.Builder(this).setTitle("Export data").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Continue", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = days.text.toString().toIntOrNull()
                if (n == null || n !in 1..90) { days.error = "Enter 1 to 90 whole days"; return@setOnClickListener }
                val end = dates.end
                if (end.isAfter(today)) { days.error = "The interval ends after the last completed day"; return@setOnClickListener }
                val chosen = ExportContent.valueOf(selected(content))
                val allow = incomplete.isChecked
                val pdf = pdfFormat.isChecked
                val csv = csvFormat.isChecked
                if (!pdf && !csv) { exportError("Select PDF, CSV, or both formats."); return@setOnClickListener }
                draft.edit().putString("start", dates.start.toString()).putString("end", dates.end.toString())
                    .putBoolean("keepStart", keepStart)
                    .remove("anchor").remove("anchorKind")
                    .putInt("days", n).putString("content", chosen.name).putBoolean("pdf", pdf).putBoolean("csv", csv).putBoolean("independentFormats", true)
                    .putBoolean("incomplete", allow).putBoolean("activities", activities.isChecked).apply()
                dialog.dismiss()
                val saved = store.load()
                fun proceed(device: LatestDevice, since: LocalDate) {
                    when (val request = ExportWindow.resolve(device, since, end, n, today, chosen, allow)) {
                        is ExportRequest.Refused -> exportError(request.message)
                        is ExportRequest.Ready -> {
                            if (chosen == ExportContent.SENSOR_AND_PUMP && !allow) rememberDevice(device, since)
                            beginHistoryExport(request.window, pdf, includeActivities = activities.isChecked, csv = csv)
                        }
                    }
                }
                if (chosen != ExportContent.SENSOR_AND_PUMP || allow) {
                    proceed(saved.latestDevice ?: LatestDevice.MINIMED_PUMP, saved.latestDeviceSince ?: today)
                } else if (saved.latestDevice != null && saved.latestDeviceSince != null) {
                    proceed(saved.latestDevice, saved.latestDeviceSince)
                } else {
                    askLatestDevice(saved.latestDevice) { device -> askDeviceStart(device, saved.latestDeviceSince) { proceed(device, it) } }
                }
            }
        }
        dialog.show()
    }

    private fun askLatestDevice(current: LatestDevice?, onDevice: (LatestDevice) -> Unit) {
        AlertDialog.Builder(this).setTitle("Most recently started device")
            .setSingleChoiceItems(LatestDevice.entries.map { it.label }.toTypedArray(), LatestDevice.entries.indexOf(current)) { d, which ->
                d.dismiss(); onDevice(LatestDevice.entries[which])
            }.setNegativeButton("Cancel", null).show()
    }

    private fun askDeviceStart(device: LatestDevice, current: LocalDate?, onSince: (LocalDate) -> Unit) {
        pickDate(current ?: LocalDate.now(), onPicked = onSince)
    }

    private fun beginWorkoutReport(request: Intent) {
        if (historyExport != null || restoringHistory) { exportError("Finish or cancel the current export before generating a workout report."); return }
        scope.launch {
            try {
                val ids = (request.getStringArrayListExtra("workoutIds") ?: listOfNotNull(request.getStringExtra("workoutId"))).distinct()
                require(ids.size in 1..50 && ids.all { it.matches(Regex("[0-9a-f]{64}")) }) { "Select 1 to 50 saved activities." }
                val workouts = withContext(Dispatchers.IO) { ids.map(workoutStore::load) }
                val range = WorkoutReportRange.covering(workouts, java.time.ZoneId.systemDefault())
                val start = request.getStringExtra("reportStart")?.let(LocalDate::parse) ?: range.first
                val end = request.getStringExtra("reportEnd")?.let(LocalDate::parse) ?: range.last
                range.validate(start, end)
                require(end <= ExportNames.lastCompletedDay(LocalDate.now())) { "CareLink exports completed days only. Import today’s FIT now and generate its combined report tomorrow." }
                val window = ExportWindow(LatestDevice.MINIMED_PUMP, start, start, end, start,
                    ExportContent.SENSOR_AND_PUMP, allowIncomplete = true)
                beginHistoryExport(window, true, includeActivities = true, eventWorkoutIds = ids)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { exportError(e.message ?: "Could not open selected activities.") }
        }
    }

    private fun beginHistoryExport(window: ExportWindow, pdf: Boolean,
                                   restored: Map<ExportPortal, List<ExportRow>>? = null, resumeOnly: Boolean = false, startedAt: Long = System.currentTimeMillis(),
                                   includeActivities: Boolean = false, eventWorkoutIds: List<String> = emptyList(), csv: Boolean = true, restoredChunks: Map<ExportPortal, List<Int>> = emptyMap()) {
        if (window.end > ExportNames.lastCompletedDay(LocalDate.now())) {
            historyStore.clear()
            exportError("Choose completed days for this export. CareLink cannot export today; the latest date is ${ExportNames.lastCompletedDay(LocalDate.now())}.")
            return
        }
        require(pdf || csv)
        if (historyExport != null) return
        // A killed process cannot run its normal cleanup. A new export owns no previous portal downloads.
        cacheDir.listFiles()?.filter { it.isFile && ExportPortalActivity.validFileName(it.name) }?.forEach { it.delete() }
        val plan = HistoryExport(window, pdf, store.load(), startedAt = startedAt, includeActivities = includeActivities, eventWorkoutIds = eventWorkoutIds, csv = csv)
        restored?.forEach { (portal, rows) ->
            if (portal == ExportPortal.CARELINK) plan.carelink = rows else plan.dexcom = rows
            restoredChunks[portal].orEmpty().ifEmpty { listOf(0) }.forEach { plan.collection.accept(portal, it) }
        }
        if (restored == null) historyStore.begin(window, pdf, plan.settings, plan.startedAt, includeActivities, eventWorkoutIds, csv)
        historyExport = plan
        val status = hint("Enter the login and selected dates yourself on each site, then download CSV. Only downloaded data is collected; no login fields are filled. Use data for the same person.")
        historyStatus = status
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionRow(R.drawable.ic_model, "Resume next download", Brand.TEXT) { continueHistoryDownload() })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                addView(actionRow(R.drawable.ic_model, "Automatic download folder", Brand.TEXT) { chooseExportDownloadFolder() })
        }
        historyActions = actions
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(8))
            addView(hint("${window.content.label}: ${window.start} to ${window.end}." +
                if (window.clipped) " Start adjusted to the latest device's first day (${window.deviceSince})." else ""))
            addView(actions)
            if (eventWorkoutIds.isNotEmpty()) addView(hint("Workout PDF: download sensor and pump data for these dates. Whole-day context comes first, followed by the exact activity interval."))
            else if (includeActivities) addView(hint("Imported workouts overlapping these dates will be included."))
            addView(status)
        }
        val d = AlertDialog.Builder(this).setTitle("Export sources").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Export", null).setNeutralButton("Use phone cache", null).setNegativeButton("Cancel", null).create()
        historyDialog = d
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { scope.launch { finishHistoryExport(useCache = false) } }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { scope.launch { finishHistoryExport(useCache = true) } }
        }
        d.setOnDismissListener {
            historyLookups.values.toList().forEach { it.cancel() }; historyLookups.clear()
            stopExportDownloadWatch()
            historyStore.clear()
            historyExport = null; historyStatus = null; historyDialog = null; historyActions = null
            historyInputPlan = null; historyPortalSource = null
            historyTempFiles.forEach { it.delete() }; historyTempFiles.clear()
        }
        d.show()
        watchExportDownloadFolder(plan)
        if (!resumeOnly) continueHistoryDownload()
    }

    private fun chooseExportDownloadFolder() {
        AlertDialog.Builder(this).setTitle("Automatic browser downloads")
            .setMessage("Choose Downloads/GlucoPhone/Raw in device storage. Its category subfolders are included. Set your browser to download there, or select its existing download folder. New CSVs will be checked and imported during an active export, then the next source opens. If Android cannot confirm a file is ready, use Open or Share in the browser to send it to GlucoPhone.")
            .setPositiveButton("Choose folder") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                        android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FGlucoPhone%2FRaw"))
                }, REQUEST_EXPORT_FOLDER)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun watchExportDownloadFolder(plan: HistoryExport) {
        stopExportDownloadWatch()
        // A restored export may already have every CSV. Starting and immediately
        // stopping a foreground service can leave Android's startup timeout armed.
        if (plan.collection.ready || exportDownloadFolder.tree() == null) return
        ExportDiagnostics.event(this, "folder_watch_started")
        runCatching { ExportDownloadService.start(this) }.onFailure {
            historyStatus?.text = "Keep GlucoPhone open or share the downloaded CSV if Android stops collection."
        }
        folderWatch = scope.launch {
            try {
                val observed = mutableMapOf<String, Long>()
                val handled = mutableSetOf<String>()
                while (isActive && validPlan(plan) && !plan.collection.ready) {
                    try {
                        val files = withContext(Dispatchers.IO) { exportDownloadFolder.csvFiles(plan.startedAt) }
                        val live = files.map { it.fingerprint }.toSet()
                        observed.keys.retainAll(live)
                        for (file in files) {
                            if (historyLookups.values.any { it.isActive } || queuedHistoryUri != null) break
                            if (file.fingerprint in handled) continue
                            val firstSeen = observed.getOrPut(file.fingerprint) { android.os.SystemClock.elapsedRealtime() }
                            if (android.os.SystemClock.elapsedRealtime() - firstSeen < 500) continue
                            // A stalled transfer can contain valid CSV rows. Require Android's
                            // indexed size/mtime before reading, as well as stable document metadata.
                            if (!withContext(Dispatchers.IO) { exportDownloadFolder.indexed(file) }) continue
                            ExportDiagnostics.event(this@MainActivity, "folder_candidate", bytes = file.size, fileKey = file.fingerprint)
                            var stage = "read"
                            val portal = try { withContext(Dispatchers.IO) {
                                val text = exportDownloadFolder.read(file)
                                stage = "source"
                                val source = HistoricalCsv.source(text)
                                val portal = ExportPortal.entries.single { it.source == source }
                                require(portal in plan.collection.portals)
                                stage = "parse"
                                val order = if (portal == ExportPortal.CARELINK)
                                    HistoricalCsv.inferDateOrder(text, plan.window.start, plan.window.end)
                                        ?: historyStore.learnedOrder(plan.settings) ?: HistoricalCsv.DateOrder.DAY_FIRST
                                    else HistoricalCsv.DateOrder.DAY_FIRST
                                val zone = java.time.ZoneId.systemDefault()
                                val rows = HistoricalCsv.parse(text, source, zone, order).filter { plan.window.contains(it.timeMillis, zone) }
                                val required = if (portal == ExportPortal.CARELINK) ExportContent.PUMP else ExportContent.SENSOR
                                stage = "selected_events"
                                require(rows.any(required::includes)) { "No selected events in the shared CSV." }
                                stage = "file_changed"
                                require(exportDownloadFolder.csvFiles(plan.startedAt).any { it.fingerprint == file.fingerprint } &&
                                    exportDownloadFolder.indexed(file))
                                portal
                            } } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                ExportDiagnostics.event(this@MainActivity, "folder_rejected", bytes = file.size,
                                    fileKey = file.fingerprint, stage = stage, error = e)
                                handled += file.fingerprint; continue
                            }
                            handled += file.fingerprint
                            if (!validPlan(plan) || plan.collection.contains(portal)) continue
                            queuedHistoryUri = file.uri
                            ExportDiagnostics.event(this@MainActivity, "folder_import_launch", portal, file.size, file.fingerprint)
                            try {
                                startActivity(Intent(this@MainActivity, MainActivity::class.java)
                                    .setAction(CsvImportActivity.ACTION_IMPORT).setData(file.uri)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            } catch (e: Exception) {
                                queuedHistoryUri = null
                                handled.remove(file.fingerprint)
                                throw e
                            }
                            break
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        ExportDiagnostics.event(this@MainActivity, "folder_watch_error", error = e)
                        // A growing file or a revoked folder can be retried; never mark a source complete.
                    }
                    delay(500)
                }
            } finally {
                if (folderWatch === kotlin.coroutines.coroutineContext[Job]) ExportDownloadService.stop(this@MainActivity)
            }
        }
    }

    private fun stopExportDownloadWatch() {
        folderWatch?.cancel(); folderWatch = null
        ExportDownloadService.stop(this)
    }

    private fun nextHistoryPortal(): ExportPortal? {
        val plan = historyExport ?: return null
        return plan.collection.next
    }

    private fun continueHistoryDownload() {
        if (historyWriting || historyInputPlan != null || historyLookups.values.any { it.isActive }) return
        val plan = historyExport ?: return
        if (!validPlan(plan)) { exportError("Sources changed. Start a new export."); return }
        val portal = nextHistoryPortal()
        if (portal != ExportPortal.CARELINK && plan.collection.count(ExportPortal.CARELINK) > 1 &&
            ExportPortal.CARELINK in plan.collection.portals && plan.collection.contains(ExportPortal.CARELINK) && !plan.pumpConfirmed) {
            if (plan.confirmingPump) return
            plan.confirmingPump = true
            AlertDialog.Builder(this).setTitle("All Medtronic downloads collected?")
                .setMessage("${plan.collection.collected(ExportPortal.CARELINK)} files collected for ${plan.window.start} to ${plan.window.end}. Continue to ${portal?.label ?: "the export"}? Overlapping events are merged once; missing days will be shown in coverage.")
                .setPositiveButton("Yes, continue") { _, _ -> plan.pumpConfirmed = true; continueHistoryDownload() }
                .setNegativeButton("Review intervals", null)
                .setOnDismissListener { plan.confirmingPump = false }.show()
            return
        }
        historyActions?.visibility = if (portal == null) View.GONE else View.VISIBLE
        historyDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = portal == null
        if (portal == null) {
            stopExportDownloadWatch()
            historyStatus?.text = "Downloads ready. Creating the ${plan.formatLabel}…"
            scope.launch { finishHistoryExport(useCache = false) }
            return
        }
        historyInputPlan = plan; historyPortalSource = portal.source
        ExportDiagnostics.event(this, "portal_open", portal)
        val target = plan.collection.target(portal) ?: ExportDates(plan.window.start, plan.window.end)
        val number = (plan.collection.pending(portal) ?: 0) + 1
        historyStatus?.text = "Waiting for ${portal.label} CSV $number/${plan.collection.count(portal)} (${target.start} to ${target.end})."
        val portalIntent = Intent(this, ExportPortalActivity::class.java)
            .putExtra("portal", portal.name).putExtra("country", plan.settings.carelinkCountry)
            .putExtra("region", plan.settings.region.name).putExtra("start", target.start.toString())
            .putExtra("end", target.end.toString()).putExtra("downloadNumber", number).putExtra("downloadCount", plan.collection.count(portal))
        if (BuildConfig.DEBUG && intent.getBooleanExtra("exportPortalPreview", false))
            portalIntent.setClassName(this, "$packageName.ExportPortalPreviewActivity")
        if (BuildConfig.DEBUG && intent.getBooleanExtra("externalFixture", false)) {
            portalIntent.putExtra("externalFixture", true).putExtra("fixtureBase", intent.getStringExtra("fixtureBase"))
        }
        startActivityForResult(portalIntent, REQUEST_HISTORY_PORTAL + portal.ordinal * 100)
    }

    private fun validPlan(plan: HistoryExport): Boolean = historyExport === plan &&
        store.load().let { current ->
            current.configurationKey == plan.settings.configurationKey &&
                current.region == plan.settings.region && current.username == plan.settings.username &&
                current.keyOf(LinkSource.CARELINK) == plan.settings.keyOf(LinkSource.CARELINK) &&
                current.carelinkCountry == plan.settings.carelinkCountry
        }

    private fun readExportBytes(stream: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer); if (n < 0) break
            require(output.size() + n <= HistoricalCsv.MAX_BYTES) { "CSV exceeds 20 MB" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private fun importHistoryCsv(read: () -> String, cleanup: () -> Unit = {}) {
        val plan = historyExport ?: run { cleanup(); return }
        if (historyLookups.values.any { it.isActive }) { cleanup(); return }
        historyStatus?.text = "Checking downloaded CSV…"
        ExportDiagnostics.event(this, "import_started")
        historyLookups["import"] = scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { read() }
                val source = withContext(Dispatchers.IO) { HistoricalCsv.source(text) }
                val portal = ExportPortal.entries.single { it.source == source }
                require(portal in plan.collection.portals) { "This source is not selected for export." }
                if (plan.collection.contains(portal)) {
                    historyStatus?.text = "${portal.label} is already imported. Continuing with the remaining source."
                    cleanup()
                    historyLookups.remove("import")
                    continueHistoryDownload()
                    return@launch
                }
                val inferred = withContext(Dispatchers.IO) {
                    if (portal == ExportPortal.CARELINK) HistoricalCsv.inspectDateOrder(text, plan.window.start, plan.window.end)
                    else HistoricalCsv.DateOrderInference(HistoricalCsv.DateOrder.DAY_FIRST, false)
                }
                val learned = historyStore.learnedOrder(plan.settings)
                val order = if (inferred.remember) inferred.order ?: learned else learned ?: inferred.order
                if (order == null) {
                    historyLookups.remove("import")
                    AlertDialog.Builder(this@MainActivity).setTitle("Confirm this account's date format once")
                        .setMessage("Every date in this CSV fits both formats, even within the selected interval. Choose once; the format will be remembered for this account. A longer export containing a day after the 12th can identify it automatically.")
                        .setPositiveButton("Day / month") { _, _ -> acceptHistoryCsv(plan, portal, text, HistoricalCsv.DateOrder.DAY_FIRST, cleanup) }
                        .setNegativeButton("Month / day") { _, _ -> acceptHistoryCsv(plan, portal, text, HistoricalCsv.DateOrder.MONTH_FIRST, cleanup) }
                        .setNeutralButton("Cancel") { _, _ -> cleanup() }.setOnCancelListener { cleanup() }.show()
                } else {
                    historyLookups.remove("import")
                    acceptHistoryCsv(plan, portal, text, order, cleanup, inferred.remember)
                }
            } catch (e: CancellationException) { cleanup(); throw e }
            catch (e: Exception) {
                ExportDiagnostics.event(this@MainActivity, "import_failed", error = e)
                cleanup(); if (validPlan(plan)) historyStatus?.text = e.message ?: "CSV import failed"
            }
        }
    }

    private fun acceptHistoryCsv(plan: HistoryExport, portal: ExportPortal, text: String,
                                 order: HistoricalCsv.DateOrder, cleanup: () -> Unit, rememberOrder: Boolean = true) {
        historyLookups["import"] = scope.launch {
            var accepted = false
            try {
                val zone = java.time.ZoneId.systemDefault()
                val rows = withContext(Dispatchers.IO) {
                    HistoricalCsv.parse(text, portal.source, zone, order).filter { plan.window.contains(it.timeMillis, zone) }
                }
                if (!validPlan(plan)) return@launch
                require(rows.isNotEmpty()) { "This CSV has no events in ${plan.window.start} to ${plan.window.end}. Download the selected dates." }
                val required = if (portal == ExportPortal.CARELINK) ExportContent.PUMP else ExportContent.SENSOR
                require(rows.any(required::includes)) { "This CSV has no ${required.label.lowercase()} events in the selected interval. Download the correct data export." }
                val previous = if (portal == ExportPortal.CARELINK) plan.carelink.orEmpty() else plan.dexcom.orEmpty()
                val index = plan.collection.validate(portal, rows, previous, zone)
                withContext(Dispatchers.IO) {
                    ReportFiles.archiveCsv(this@MainActivity, portal, text.toByteArray(Charsets.UTF_8),
                        synthetic = BuildConfig.DEBUG && intent.getBooleanExtra("exportPortalPreview", false), order = order)
                    historyStore.record(portal, text, order, index)
                }
                if (!validPlan(plan)) return@launch
                val merged = (previous + rows).distinctBy { it.identity() }.sortedBy { it.timeMillis }
                if (portal == ExportPortal.CARELINK) { plan.carelink = merged; if (rememberOrder) historyStore.learn(plan.settings, order) }
                else plan.dexcom = merged
                plan.collection.accept(portal, index)
                ExportDiagnostics.event(this@MainActivity, "import_accepted", portal)
                historyStatus?.text = "${portal.label} ready: ${rows.size} events. Times use ${zone.id}."
                accepted = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ExportDiagnostics.event(this@MainActivity, "import_rejected", portal, error = e)
                if (validPlan(plan)) historyStatus?.text = e.message ?: "CSV import failed"
            }
            finally {
                cleanup()
                historyLookups.remove("import")
                if (accepted && validPlan(plan)) continueHistoryDownload()
            }
        }
    }

    private suspend fun finishHistoryExport(useCache: Boolean) {
        if (historyWriting || historyInputPlan != null) return
        if (historyLookups.values.any { it.isActive }) {
            historyStatus?.text = "Wait for the current download or import to finish."
            return
        }
        val plan = historyExport ?: return
        if (!validPlan(plan)) { exportError("Sources changed. Start a new export."); return }
        if (!useCache && !plan.collection.ready) {
            historyStatus?.text = "Download the selected sources before exporting."
            return
        }
        historyWriting = true
        historyDialog?.setCancelable(false)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)
            .forEach { historyDialog?.getButton(it)?.isEnabled = false }
        try {
            historyStatus?.text = "Preparing the ${plan.formatLabel}…"
            val cached = if (useCache) repository.exportTables(plan.window) else PhoneExport.empty(java.time.ZoneId.systemDefault())
            val activities = withContext(Dispatchers.IO) {
                if (plan.eventWorkoutIds.isNotEmpty()) plan.eventWorkoutIds.map(workoutStore::load)
                else if (plan.includeActivities) workoutStore.inWindow(plan.window, cached.zone) else emptyList()
            }
            val tables = withContext(Dispatchers.Default) {
                cached.withHistory(plan.dexcom, plan.carelink).within(plan.window).select(plan.window.content)
            }
            if (!validPlan(plan)) return
            require(tables.rows.isNotEmpty()) { "No selected events in this interval." }
            require(plan.window.content != ExportContent.SENSOR_AND_PUMP ||
                (tables.dexcomRows.any(ExportContent.SENSOR::includes) && tables.carelinkRows.any(ExportContent.PUMP::includes))) {
                "Both sensor and pump data are required. Collect the missing source before exporting."
            }
            val knownActivities = if(plan.includeActivities) withContext(Dispatchers.IO) { workoutStore.inWindow(plan.window,tables.zone) } else emptyList()
            val contextRows=WorkoutAnnotations.context(tables.rows,knownActivities,activities)
            val coverage = tables.coverage(plan.window) + if (plan.includeActivities) " ${activities.size} imported workouts included." else ""
            if (!plan.window.allowIncomplete && tables.hasGaps(plan.window)) {
                ExportDiagnostics.event(this, "coverage_incomplete")
                val approved = suspendCancellableCoroutine<Boolean> { continuation ->
                    val dialog = AlertDialog.Builder(this).setTitle("Incomplete coverage")
                        .setMessage("$coverage\nSome days have no selected data. Export the available rows?")
                        .setPositiveButton("Export available data") { _, _ -> if (continuation.isActive) continuation.resume(true) }
                        .setNegativeButton("Back") { _, _ -> if (continuation.isActive) continuation.resume(false) }
                        .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }.show()
                    continuation.invokeOnCancellation { dialog.dismiss() }
                }
                if (!approved) { historyStatus?.text = "Downloads kept. Select Export to try again."; return }
            }
            historyStatus?.text = "Creating the ${plan.formatLabel}…"
            val prefix = if (BuildConfig.DEBUG && intent.getBooleanExtra("exportPortalPreview", false)) "synthetic-" else ""
            val baseName = prefix + ExportNames.report(plan.window.start, plan.window.end, plan.window.content, activities.size)
            withContext(Dispatchers.Default) {
                val analysisRows=WorkoutAnnotations.add(contextRows,activities)
                val analysisTime=plan.window.end.plusDays(1).atStartOfDay(tables.zone).toInstant().toEpochMilli()
                InsulinAnalysisStore(this@MainActivity).save(analysisRows,tables.glucose,tables.zone,analysisTime,plan.settings)
            }
            val artifacts = withContext(Dispatchers.Default) {
                buildList {
                    if (plan.pdf) {
                        val bytes = if (plan.eventWorkoutIds.isNotEmpty()) WorkoutReportPdf.render(activities, tables.glucose, contextRows, tables.zone, plan.window.start, plan.window.end, WorkoutZones.read(this@MainActivity), WorkoutZones.pace(this@MainActivity))
                        else ReportPdf.render(tables.report(plan.window).copy(events=contextRows), workouts = activities, zoneBoundaries = WorkoutZones.read(this@MainActivity), paceBoundaries = WorkoutZones.pace(this@MainActivity))
                        add(ExportArtifact("$baseName.pdf", "application/pdf", bytes))
                    }
                    if (plan.csv) {
                        val from = plan.window.start.atStartOfDay(tables.zone).toInstant().toEpochMilli()
                        val until = plan.window.end.plusDays(1).atStartOfDay(tables.zone).toInstant().toEpochMilli()
                        add(ExportArtifact("$baseName.csv", "text/csv", ExportCsv.bytes(ExportCsv.write(MergedHealthExport.rows(contextRows,tables.glucose,activities,from,until), tables.zone))))
                    }
                }
            }
            if (!validPlan(plan)) return
            pendingArtifacts.clear(); savedArtifacts.clear(); customSavedUris.clear()
            pendingArtifacts.addAll(artifacts); pendingExportNote = coverage
            saveNextArtifact()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (validPlan(plan)) {
                historyStatus?.text = "Export failed. Downloads kept; select Export to retry."
                exportError("Could not create the ${plan.formatLabel} (${e.javaClass.simpleName}). Your downloaded data is kept; select Export to retry.")
            }
        } finally {
            historyWriting = false
            if (validPlan(plan)) {
                historyDialog?.setCancelable(true)
                historyDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = plan.collection.ready
                historyDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
                historyDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
            }
        }
    }

    private data class ExportArtifact(val name: String, val mime: String, val bytes: ByteArray)
    private val pendingArtifacts = java.util.ArrayDeque<ExportArtifact>()
    private val savedArtifacts = mutableListOf<Pair<android.net.Uri, ExportArtifact>>()
    private val customSavedUris = mutableSetOf<android.net.Uri>()

    private suspend fun saveNextArtifact() {
        while (pendingArtifacts.isNotEmpty()) {
            val artifact = pendingArtifacts.first()
            val uri = try { withContext(Dispatchers.IO) {
                ReportFiles.publish(this@MainActivity, artifact.name, artifact.mime, artifact.bytes, ReportFiles.exports(artifact.mime))
            } } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                pendingExport = artifact.bytes
                historyStatus?.text = "Choose where to save ${artifact.name}. Remaining formats are saved next."
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = artifact.mime; putExtra(Intent.EXTRA_TITLE, artifact.name)
                }, REQUEST_EXPORT)
                return
            }
            pendingArtifacts.removeFirst(); savedArtifacts += uri to artifact
        }
        historyDialog?.dismiss()
        val files = savedArtifacts.toList()
        val message = pendingExportNote + "\n\n" + files.joinToString("\n") { (uri, artifact) ->
            if (uri in customSavedUris) "Selected location: ${artifact.name}"
            else "Downloads/${ReportFiles.exports(artifact.mime)}/${artifact.name}"
        }
        val choices = files.map { (_, artifact) -> "Open ${if (artifact.mime == "application/pdf") "PDF" else "CSV"}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Export saved").setMessage(message)
            .setPositiveButton(choices.firstOrNull() ?: "Close") { _, _ -> files.firstOrNull()?.let { openExport(it.first, it.second.mime) } }
            .setNeutralButton(if (files.size > 1) choices[1] else "Open Downloads") { _, _ ->
                if (files.size > 1) openExport(files[1].first, files[1].second.mime) else openDownloads()
            }.setNegativeButton("Close", null).show()
        savedArtifacts.clear(); customSavedUris.clear(); pendingExportNote = ""
    }
    private fun openExport(uri: android.net.Uri, mime: String) {
        if (mime == "application/pdf") {
            startActivity(Intent(this, PdfViewerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } else runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { openDownloads() }
    }
    private fun exportError(message: String) {
        AlertDialog.Builder(this).setTitle("Export").setMessage(message).setPositiveButton("Close", null).show()
    }

    private fun rememberDevice(device: LatestDevice, since: LocalDate) {
        store.save(store.load().copy(latestDevice = device, latestDeviceSince = since))
        if (::latestDevice.isInitialized) {
            LatestDevice.entries.forEachIndexed { index, item ->
                (latestDevice.getChildAt(index) as? RadioButton)?.isChecked = item == device
            }
            deviceSince.setText(since.toString())
        }
    }

    private fun pickDate(initial: LocalDate, minimum: LocalDate? = null,
                         maximum: LocalDate = LocalDate.now(), onPicked: (LocalDate) -> Unit) {
        val today = LocalDate.now()
        val day = if (initial.isAfter(today)) today else initial
        DatePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert, { _, year, month, dayOfMonth ->
            onPicked(LocalDate.of(year, month + 1, dayOfMonth))
        }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
            val cal = java.util.Calendar.getInstance()
            cal.set(maximum.year, maximum.monthValue - 1, maximum.dayOfMonth, 23, 59, 59)
            datePicker.maxDate = cal.timeInMillis
            minimum?.let {
                cal.set(it.year, it.monthValue - 1, it.dayOfMonth, 0, 0, 0)
                datePicker.minDate = cal.timeInMillis
            }
            show()
        }
    }

    private fun carelinkNote(exported: PhoneExport): String = when {
        !exported.hasCareLink -> "CareLink is not selected in Connect. The file has column headers only."
        exported.carelinkRows.isEmpty() -> "No CareLink data is cached yet. The file has column headers only."
        else -> "${exported.carelinkRows.size} CareLink rows. Times are UTC and this phone's time zone. A basal rate is a setting, not a dose."
    }

    private fun combinedNote(exported: PhoneExport): String = buildString {
        append("${exported.carelinkRows.size} CareLink rows, ${exported.dexcomRows.size} Dexcom rows.")
        if (!exported.hasCareLink) append(" CareLink is not selected.")
        if (!exported.hasDexcom) append(" Dexcom is not the glucose source.")
    }

    private data class ExportChoice(val name: String, val type: String, val bytes: ByteArray, val note: String)

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
        changeNote = hint("").apply { textSize = 10f; gravity = Gravity.CENTER }
        forecastValue = metricValue()
        forecastNote = hint("").apply { textSize = 10f; gravity = Gravity.CENTER }
        val metrics = LinearLayout(this).apply {
            setPadding(0, dp(10), 0, dp(14))
            addView(metricColumn("Time in range", rangeValue))
            addView(divider())
            addView(metricColumn("30 min change", changeValue, changeNote))
            addView(divider())
            addView(metricColumn("Forecast", forecastValue, forecastNote))
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
        source = radios(LinkSource.entries.map { it.name to if (it == LinkSource.SHARE) "Dexcom" else PhoneSettings(source = it).sourceLabel }, s.source.name)
        dexcomNotifications = CheckBox(this).apply {
            text = "Use G6 notifications instead of Dexcom Share"
            setTextColor(Brand.TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(Brand.TEXT)
            isChecked = s.dexcomNotifications
            setOnCheckedChangeListener { _, _ -> updateVisibility() }
        }
        notificationAccess = Button(this).apply {
            Brand.style(this, false)
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        notificationFields = listOf(
            hint("Reads the official G6 app's ongoing Quick Glance notification on this phone. Enable Quick Glance in G6, allow notification access below, then tap Save & test. No Dexcom login or internet is needed for glucose. New readings can take five minutes to arrive."),
            hint("Android grants access to notifications from all apps; GlucoPhone only processes G6. It stores glucose locally and shares it with your paired watch. Choose Phone app as the watch's source. History starts here; notification times may differ from sensor times."),
            notificationAccess,
        )
        username = field("Dexcom username / email", s.username, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = field("Password", s.password, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val showPassword = passwordToggle(password, "Show password")
        region = radios(Region.entries.map { it.name to it.label }, s.region.name)
        nightscoutUrl = field("https://your-site.example", s.nightscoutUrl, InputType.TYPE_TEXT_VARIATION_URI)
        nightscoutToken = field("optional", s.nightscoutToken, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        nightscoutApi = radios(NightscoutApi.entries.map { it.name to it.label }, s.nightscoutApi.name)
        carelinkStatus = hint("")
        carelinkCountry = field("Two-letter country, e.g. DE", PhoneCareLinkStore(this).load()?.country ?: s.carelinkCountry, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        PhoneSettings.THERAPY_SOURCES.forEach { of ->
            therapySources[of] = CheckBox(this).apply {
                text = s.label(of); setTextColor(Brand.TEXT); isChecked = of in s.alsoFrom
                buttonTintList = android.content.res.ColorStateList.valueOf(Brand.TEXT)
                setOnCheckedChangeListener { _, _ -> if (::carelinkFields.isInitialized) updateVisibility() }
            }
        }
        unit = radios(GlucoseUnit.entries.map { it.name to it.label }, s.unit.name)
        latestDevice = radios(LatestDevice.entries.map { it.name to it.label }, s.latestDevice?.name.orEmpty())
        deviceSince = field("yyyy-MM-dd", s.latestDeviceSince?.toString().orEmpty(), 0)
        result = TextView(this).apply { textSize = 14f; setTextColor(Brand.TEXT); setPadding(0, dp(10), 0, 0) }

        shareFields = listOf(label("Account"), username, password, showPassword, label("Region"), region,
            hint("Share must be on in the Dexcom app, with at least one follower."))
        nightscoutFields = listOf(
            label("Nightscout address"), nightscoutUrl,
            label("Token or API secret"), nightscoutToken, hint("A token with the readable role is safer. v3 needs a token."),
            label("API"), nightscoutApi,
        )
        carelinkFields = listOf(
            label("CareLink account"), carelinkStatus, label("CareLink account country"), carelinkCountry,
            Button(this).apply {
                text = "Sign in to CareLink"; Brand.style(this, true)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, CareLinkSignInActivity::class.java)
                        .putExtra("country", carelinkCountry.text.toString().trim().uppercase()))
                }
            },
            hint(
                if (BuildConfig.DEBUG && BuildConfig.DEV_CARELINK_PASSWORD.isNotEmpty())
                    "This debug build fills the CareLink username and password on Medtronic's page. GlucoPhone does not save that password. Solve the captcha there. The country selects the account's regional login server."
                else
                    "Your CareLink username and password are entered only on Medtronic's browser page and are never stored by GlucoPhone. The country selects your account's regional login server. Sign in with a care partner account linked to the patient. To share the combined chart, choose Phone app on the watch.",
            ),
        )
        configUrl = field("https://your-site.example/config.yaml", "", InputType.TYPE_TEXT_VARIATION_URI)
        configUrl.isSaveEnabled = false
        configStatus = hint("")
        connect.addView(card().apply {
            addView(cardTitle("Import configuration"))
            addView(hint("Set up sources and prediction from a .env or YAML file. A model address downloads and selects its ONNX model automatically. Omitted settings keep their current values."))
            addView(Button(this@MainActivity).apply {
                text = "Upload .env / YAML"; Brand.style(this, true)
                setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    }, REQUEST_CONFIG)
                }
            })
            addView(label("Configuration URL")); addView(configUrl)
            addView(Button(this@MainActivity).apply {
                text = "Import from URL"; Brand.style(this, false)
                setOnClickListener {
                    val address = configUrl.text.toString()
                    importConfiguration { PhoneConfigurationImport(this@MainActivity).url(address) }
                }
            })
            addView(configStatus)
        })
        connect.addView(card().apply {
            addView(cardTitle("Glucose source"))
            addView(hint("Choose a source. Your login stays in private app storage."))
            addView(source)
            addView(dexcomNotifications)
            notificationFields.forEach(::addView)
            addView(label("Additional insulin sources"))
            addView(hint("For example: Dexcom for glucose, CareLink for pump insulin. Basal, bolus and carbs appear on the same timeline."))
            therapySources.values.forEach(::addView)
            shareFields.forEach(::addView)
            nightscoutFields.forEach(::addView)
            carelinkFields.forEach(::addView)
            addView(label("Units")); addView(unit)
            addView(label("Latest device"))
            addView(hint("Combined exports start on this day unless you allow incomplete data. Sensor-only or pump-only exports can use earlier dates."))
            addView(latestDevice)
            addView(label("Device start date")); addView(deviceSince)
            addView(Button(this@MainActivity).apply { text = "Save & test"; Brand.style(this, true); setOnClickListener { saveAndTest() } })
            addView(result)
        })
    }

    private fun buildModel(model: LinearLayout, s: PhoneSettings) {
        predictor = radios(Predictors.all.map { it.id to it.displayName } + (OnnxPredictor.ID to "Imported ONNX"), s.predictorId)
        modelInput = field("owner/model or Hugging Face link", s.modelAddress, InputType.TYPE_TEXT_VARIATION_URI)
        modelToken = field("Optional access token", s.huggingFaceToken, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        otherModelToken = field("Optional access token", s.otherModelToken, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        listOf(modelToken, otherModelToken).forEach {
            it.textSize = 16f; it.typeface = Typeface.DEFAULT
            it.isSaveEnabled = false
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        modelFiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ownFiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modelStatus = hint("")
        modelForecastNote = hint("")
        model.addView(card().apply {
            addView(cardTitle("Glucose prediction"))
            addView(hint("Choose Linear trend or an imported ONNX model. Paired watches can use this forecast by selecting Phone app model."))
            addView(predictor)
            addView(modelForecastNote)
        })
        modelMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionRow(R.drawable.ic_model, "GlucoseDao organization", Brand.TEXT) { showCatalog() })
            addView(actionRow(R.drawable.ic_model, "Import another model", Brand.TEXT) { showOwn() })
        }
        catalogPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(modelBackRow())
            addView(label("GlucoseDao access token (optional)"))
            addView(modelToken)
            addView(hint("Uses your existing HF token. Leave blank for public models. Change it, then refresh the list."))
            addView(Button(this@MainActivity).apply {
                text = "Refresh models"; Brand.style(this, false)
                setOnClickListener { listGlucoseDao() }
            })
            addView(modelFiles)
        }
        ownPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(modelBackRow())
            addView(label("Model address")); addView(modelInput)
            addView(label("Repository access token (optional)")); addView(otherModelToken)
            addView(hint("Separate from your GlucoseDao token. Leave blank for public repositories. Tokens stay in private phone storage and are sent only to huggingface.co."))
            addView(Button(this@MainActivity).apply {
                text = "Find ONNX model"; Brand.style(this, true); setOnClickListener { findModels() }
            })
            addView(ownFiles)
            addView(Button(this@MainActivity).apply {
                text = "Import downloaded ONNX file"; Brand.style(this, false); setOnClickListener { chooseModelFile() }
            })
            addView(hint("Repository downloads also import the model and its metadata/scalers. Local imports need no token."))
        }
        importCard = card().apply {
            addView(cardTitle("Imported ONNX"))
            addView(modelMenu); addView(catalogPanel); addView(ownPanel)
            addView(modelStatus)
            removeButton = Button(this@MainActivity).apply {
                text = "Remove imported model"; Brand.style(this, false); setOnClickListener { removeModel() }
            }
            addView(removeButton)
        }
        model.addView(importCard)
        predictor.setOnCheckedChangeListener { _, _ -> saveModelChoice() }
        importCard.visibility = if (s.predictorId == OnnxPredictor.ID) View.VISIBLE else View.GONE
    }

    private fun buildWatch(watch: LinearLayout) {
        watchList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pairButton = Button(this).apply { text = "Pair a watch"; Brand.style(this, true); setOnClickListener { startPairing() } }
        pairingText = TextView(this).apply { textSize = 14f; setTextColor(Brand.TEXT); setPadding(0, dp(8), 0, 0) }
        pairingCode = TextView(this).apply {
            textSize = 34f; gravity = Gravity.CENTER; setTextColor(Brand.TEXT); typeface = Typeface.DEFAULT_BOLD
        }
        confirmPairButton = Button(this).apply { text = "Codes match"; Brand.style(this, true); setOnClickListener { confirmPairing() } }
        pairingButtons = LinearLayout(this).apply {
            addView(confirmPairButton, weight())
            addView(Button(this@MainActivity).apply {
                text = "Cancel"; Brand.style(this, false)
                setOnClickListener { PairingWindow.close(); LinkService.update(this@MainActivity) }
            }, weight())
        }
        watch.addView(card().apply {
            addView(cardTitle("Paired watches"))
            addView(hint("Start pairing here or on your watch. The first device waits while you start the other; then confirm the matching code on both."))
            addView(watchList); addView(pairButton); addView(pairingText); addView(pairingCode); addView(pairingButtons)
        })
    }

    // ---------------------------------------------------------------- model import

    private fun showModelMenu() {
        if (modelInstalling) return
        saveModelSettings()
        modelLookup?.cancel()
        modelPage = "menu"
        modelMenu.visibility = View.VISIBLE
        catalogPanel.visibility = View.GONE
        ownPanel.visibility = View.GONE
        updateModelStatus()
    }

    private fun showCatalog() {
        if (modelInstalling) return
        modelPage = "catalog"
        modelMenu.visibility = View.GONE
        catalogPanel.visibility = View.VISIBLE
        ownPanel.visibility = View.GONE
        listGlucoseDao()
    }

    private fun showOwn() {
        if (modelInstalling) return
        modelLookup?.cancel()
        modelPage = "own"
        modelMenu.visibility = View.GONE
        catalogPanel.visibility = View.GONE
        ownPanel.visibility = View.VISIBLE
        modelStatus.text = modelStore.origin?.let { "Current model: $it" }.orEmpty()
    }

    private fun modelBackRow() = actionRow(R.drawable.ic_chevron, "Back to model sources", Brand.TEXT) { showModelMenu() }.apply {
        getChildAt(0).rotation = 180f
        getChildAt(childCount - 1).visibility = View.GONE
    }

    private fun navigateModelBack(): Boolean {
        if (modelInstalling) return true
        if (sections[2].visibility != View.VISIBLE || modelPage == "menu") return false
        showModelMenu()
        return true
    }

    @Deprecated("Legacy dispatch for Android 10-12; Android 13+ uses OnBackInvokedDispatcher")
    @android.annotation.SuppressLint("GestureBackNavigation") // Modern callback is registered in onCreate.
    override fun onBackPressed() {
        if (!navigateModelBack()) super.onBackPressed()
    }

    private fun findModels() {
        if (modelInstalling) return
        val settings = saveModelSettings()
        val typed = settings.modelAddress
        ownFiles.removeAllViews()
        if (typed.isBlank()) {
            modelStatus.text = "Enter a model address first."
            return
        }
        modelStatus.text = "Looking up $typed…"
        modelLookup?.cancel()
        modelLookup = scope.launch {
            try {
                if (HuggingFaceModel.isSearch(typed)) {
                    val models = modelStore.searchHuggingFace(typed, settings.otherModelToken)
                    if (!isActive) return@launch
                    ownFiles.removeAllViews()
                    modelStatus.text = if (models.isEmpty()) "No matching models. Try another name or enter owner/model."
                        else "Choose a repository to find its ONNX files."
                    models.forEach { model ->
                        ownFiles.addView(actionRow(R.drawable.ic_model, model.repo, Brand.TEXT) { findRepo(model.repo, ownFiles) })
                    }
                } else {
                    val listing = modelStore.listHuggingFace(typed, settings.otherModelToken)
                    if (isActive) showFiles(ownFiles, listing)
                }
            } catch (error: Exception) {
                if (isActive) {
                    modelStatus.text = error.message ?: "Could not find that model"
                }
            }
        }
    }

    private fun listGlucoseDao() {
        if (modelInstalling) return
        val settings = saveModelSettings()
        val known = HuggingFaceModel.glucoseDaoChoices()
        showGlucoseDao(known)
        modelStatus.text = "Choose a model. Nothing is downloaded until you tap its file."
        modelLookup?.cancel()
        modelLookup = scope.launch {
            try {
                val live = modelStore.listOrganization("GlucoseDao", settings.huggingFaceToken)
                if (!isActive || live.isEmpty()) return@launch
                val seen = live.map { it.repo }.toSet()
                showGlucoseDao(live + known.filter { it.repo !in seen })
            } catch (error: Exception) {
                if (isActive && known.isEmpty()) modelStatus.text = error.message ?: "Could not list GlucoseDao models"
            }
        }
    }

    private fun showGlucoseDao(models: List<HuggingFaceModel>) {
        modelFiles.removeAllViews()
        val counts = models.groupingBy { HuggingFaceModel.choiceLabel(it.repo) }.eachCount()
        models.forEach { model ->
            val label = HuggingFaceModel.choiceLabel(model.repo)
            val shown = if (counts[label] == 1) label else model.repo.substringAfter('/')
            modelFiles.addView(actionRow(R.drawable.ic_model, shown, Brand.TEXT) { findRepo(model.repo, modelFiles) })
        }
    }

    private fun findRepo(repo: String, target: LinearLayout) {
        if (target === ownFiles) modelInput.setText(repo)
        val settings = saveModelSettings()
        target.removeAllViews()
        modelStatus.text = "Looking up ${HuggingFaceModel.choiceLabel(repo)}…"
        modelLookup?.cancel()
        modelLookup = scope.launch {
            try {
                val listing = modelStore.listHuggingFace(repo, if (target === modelFiles) settings.huggingFaceToken else settings.otherModelToken)
                if (isActive) showFiles(target, listing)
            } catch (error: Exception) {
                if (isActive) {
                    modelStatus.text = error.message ?: "Could not find that model"
                }
            }
        }
    }

    private fun saveModelSettings(): PhoneSettings = store.load().copy(
        modelAddress = modelInput.text.toString().trim(),
        huggingFaceToken = modelToken.text.toString().trim(),
        otherModelToken = otherModelToken.text.toString().trim(),
    ).also(store::save)

    private fun showFiles(target: LinearLayout, listing: PhoneModelStore.Listing) {
        target.removeAllViews()
        modelStatus.text = "${HuggingFaceModel.choiceLabel(listing.model.repo)}: ${listing.files.size} .onnx file" +
            (if (listing.files.size == 1) "" else "s") + ". Tap one to import it."
        listing.files.take(MAX_LISTED_FILES).forEach { file ->
            target.addView(actionRow(R.drawable.ic_model, "Download & import $file", Brand.TEXT) {
                modelStatus.text = "Downloading and checking $file…"
                target.removeAllViews()
                val settings = saveModelSettings()
                modelImport = scope.launch { installModel { modelStore.importHuggingFace(listing.model, file, if (target === modelFiles) settings.huggingFaceToken else settings.otherModelToken) } }
            })
        }
    }

    private fun chooseModelFile() {
        if (modelInstalling) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/onnx", "*/*"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_MODEL)
    }

    private suspend fun installModel(action: suspend () -> String) {
        if (modelInstalling) return
        modelInstalling = true
        val outcome = try {
            runCatching { action() }.onFailure { if (it is CancellationException) throw it }
        } finally { modelInstalling = false }
        removeButton.visibility = if (modelStore.available) View.VISIBLE else View.GONE
        if (outcome.isSuccess) {
            for (i in 0 until predictor.childCount) {
                val radio = predictor.getChildAt(i) as RadioButton
                if (radio.tag == OnnxPredictor.ID) radio.isChecked = true
            }
            store.save(store.load().copy(predictorId = OnnxPredictor.ID))
            render(repository.state())
        }
        modelStatus.text = outcome.fold(
            onSuccess = { "Ready: $it" },
            onFailure = { "Could not use this model: ${it.message ?: it.javaClass.simpleName}" },
        )
    }

    private fun saveModelChoice() {
        val selected = selected(predictor)
        store.save(store.load().copy(predictorId = selected))
        val imported = selected == OnnxPredictor.ID
        val opening = imported && importCard.visibility != View.VISIBLE
        importCard.visibility = if (imported) View.VISIBLE else View.GONE
        if (!imported) { modelLookup?.cancel(); modelImport?.cancel() }
        if (opening) showModelMenu()
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
        ownFiles.removeAllViews()
        updateModelStatus()
        render(repository.state())
    }

    // ---------------------------------------------------------------- lifecycle and results

    override fun onStart() {
        super.onStart()
        updateVisibility()
        foregroundRefresh = scope.launch {
            while (isActive) {
                render(repository.refresh(maxAgeMs = 60_000))
                delay(if (store.load().usesDexcomNotifications) 5_000 else 60_000)
            }
        }
    }

    override fun onStop() {
        foregroundRefresh?.cancel()
        foregroundRefresh = null
        super.onStop()
    }

    override fun onDestroy() {
        historyDialog?.setOnDismissListener(null)
        stopExportDownloadWatch()
        PairingWindow.listener = null
        scope.cancel()
        historyTempFiles.forEach { it.delete() }; historyTempFiles.clear()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_HEART) { refreshHeartRate(); return }
        if (requestCode != REQUEST_BLUETOOTH) return
        val action = afterBluetooth
        afterBluetooth = null
        if (LinkService.hasPermission(this)) action?.invoke()
        else {
            val message = "Allow Nearby devices for GlucoPhone in the phone's app permissions, then tap Pair a watch again."
            result.text = "⚠ $message"
            pairingText.text = message
            pairingText.visibility = View.VISIBLE
        }
    }

    @Deprecated("The platform result API is enough for these two one-off imports")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_WORKOUT -> if (resultCode == RESULT_OK) data?.let(::beginWorkoutReport)
            REQUEST_EXPORT_FOLDER -> {
                val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
                runCatching {
                    exportDownloadFolder.remember(uri, data.flags)
                    historyExport?.let(::watchExportDownloadFolder)
                    historyStatus?.text = "Automatic import enabled. Save browser CSVs to this same folder."
                }.onFailure { exportError("Could not retain access to that folder. Choose Downloads/GlucoPhone again.") }
            }
            REQUEST_HISTORY_PORTAL, REQUEST_HISTORY_PORTAL + 100 -> {
                val expectedPortal = ExportPortal.entries[(requestCode - REQUEST_HISTORY_PORTAL) / 100]
                if (historyPortalSource != expectedPortal.source && resultCode != RESULT_OK) return
                val plan = historyInputPlan
                historyInputPlan = null; historyPortalSource = null
                if (resultCode != RESULT_OK) return
                val name = data?.getStringExtra("csvFile")?.takeIf(ExportPortalActivity::validFileName) ?: return
                val file = cacheDir.resolve(name)
                if (plan == null || !validPlan(plan)) { file.delete(); return }
                if (file.isFile) {
                    historyTempFiles += file
                    importHistoryCsv({ file.inputStream().use { readExportBytes(it).toString(Charsets.UTF_8) } }) {
                        file.delete(); historyTempFiles -= file
                    }
                }
            }
            REQUEST_CONFIG -> {
                val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
                importConfiguration { PhoneConfigurationImport(this@MainActivity).file(uri) }
            }
            REQUEST_MODEL -> {
                val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
                modelStatus.text = "Checking model…"
                val target = if (ownPanel.visibility == View.VISIBLE) ownFiles else modelFiles
                target.removeAllViews()
                modelImport = scope.launch { installModel { modelStore.importFile(uri) } }
            }
            REQUEST_FOOD_PHOTO -> {
                val photo = pendingPhoto
                pendingPhoto = null
                if (resultCode == RESULT_OK && photo != null && photo.isFile) askCarbs(photo)
                else photo?.delete()
            }
            REQUEST_EXPORT -> {
                val bytes = pendingExport; pendingExport = null
                val uri = data?.data?.takeIf { resultCode == RESULT_OK }
                if (bytes == null || uri == null) {
                    pendingArtifacts.clear(); savedArtifacts.clear(); customSavedUris.clear(); pendingExportNote = ""
                    historyStatus?.text = "Save cancelled. Downloads kept; select Export to save again."
                    return
                }
                scope.launch {
                    historyWriting = true
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not write the file")
                        }
                        val artifact = pendingArtifacts.removeFirst()
                        customSavedUris += uri
                        savedArtifacts += uri to artifact
                        saveNextArtifact()
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { exportError("Could not save the file. Downloads kept; select Export to retry.") }
                    finally { historyWriting = false }
                }
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
        val forecastMinutes = repository.defaultForecastMinutes(state)
        chart.forecast = repository.forecast(state, forecastMinutes)
        forecastNote.text = "$forecastMinutes min"
        if (BuildConfig.DEBUG && intent.getBooleanExtra("exportDebugSnapshot", false) && latest != null) {
            val json = org.json.JSONObject()
                .put("upstream", glucowatch.core.link.LinkCrypto.sha256(state.settings.configurationKey.toByteArray()).take(12).joinToString("") { "%02x".format(it) })
                .put("sourceLabel", state.settings.sourceLabel)
                .put("readings", glucowatch.core.CacheFormat.encodeReadings(state.readings.filter { now-it.timeMillis <= 24*3_600_000L }))
                .put("treatments", glucowatch.core.CacheFormat.encodeTreatments(state.treatments.filter { now-it.timeMillis <= 24*3_600_000L }))
                .put("loop", glucowatch.core.CacheFormat.encodeLoop(state.loop))
                .put("forecast", glucowatch.core.CacheFormat.encodePrediction(chart.forecast))
                .put("error", state.lastError.orEmpty())
            filesDir.resolve("debug-phone-snapshot.json").writeText(json.toString())
        }
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
            changeNote.text = "Need 30 min history"
            changeNote.setTextColor(Brand.MUTED)
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
            val recentDelta = state.readings.filter {
                it.timeMillis >= latest.timeMillis - glucowatch.core.GlucoseHistory.GAP_MS
            }.lastDelta()
            sourceLine.text = if (stale) "${state.settings.sourceLabel} · Last known glucose\n" +
                "${state.settings.label(state.settings.source)} has no recent glucose readings."
            else "${state.settings.sourceLabel}  ·  ${latest.trend.description.ifEmpty { "trend unavailable" }}" +
                (recentDelta?.let { "  ·  ${u.formatDelta(it)} in 5 min" } ?: "")

            val day = state.readings.filter { it.timeMillis >= now - 24 * 3_600_000L }
            rangeValue.text = if (day.isEmpty()) "—"
                else "${100 * day.count { it.mgdl in Brand.TARGET_LOW..Brand.TARGET_HIGH } / day.size}%"
            rangeValue.setTextColor(Brand.TEXT)
            val change = glucowatch.core.GlucoseHistory.change30Minutes(state.readings, now)
            changeValue.text = change?.let { u.formatDelta(it.mgdl) } ?: "—"
            changeValue.setTextColor(Brand.TEXT)
            changeNote.text = when {
                stale -> "No recent data"
                change == null -> "Need 30 min history"
                change.hasGap -> "Gap in data"
                kotlin.math.abs(change.elapsedMillis - 30 * 60_000L) >= 60_000L -> "${change.elapsedMillis / 60_000} min apart"
                else -> u.label
            }
            changeNote.setTextColor(if (change?.hasGap == true) Brand.RED else Brand.MUTED)
            val forecast = chart.forecast?.points?.lastOrNull()
            forecastValue.text = forecast?.let { u.format(it.mgdl) } ?: "—"
            forecastValue.setTextColor(forecast?.let { Brand.glucoseColor(it.mgdl.toInt()) } ?: Brand.TEXT)
        }
        modelForecastNote.text = if (state.settings.predictorId == OnnxPredictor.ID) {
            val model = modelStore.predictor() as? glucowatch.core.GlucoseDaoPredictor
            when {
                model != null -> listOfNotNull(model.displayName,
                    "Supports ${model.contract.horizonSteps * 5} min · showing ${model.defaultHorizonMinutes} min",
                    if (model.contract.channels.any { it.contains("carb") }) "Inputs: glucose, basal U/h, bolus U, carbs g"
                    else "Inputs: glucose, basal U/h, bolus U (no carb channel)",
                    model.lastProblem).joinToString(" · ")
                modelStore.available -> "Imported ONNX is ready. Inputs: glucose only; this model has no pump channels."
                else -> "Imported ONNX stays selected. Choose a GlucoseDao model or your own file."
            }
        } else ""
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
        dexcomNotifications.visibility = if (chosen == LinkSource.SHARE) View.VISIBLE else View.GONE
        val notifications = chosen == LinkSource.SHARE && dexcomNotifications.isChecked
        notificationFields.forEach { it.visibility = if (notifications) View.VISIBLE else View.GONE }
        notificationAccess.text = if (DexcomNotificationService.hasAccess(this)) "Notification access allowed · Manage" else "Allow notification access"
        shareFields.forEach { it.visibility = if (chosen == LinkSource.SHARE && !notifications) View.VISIBLE else View.GONE }
        nightscoutFields.forEach { it.visibility = if (used(LinkSource.NIGHTSCOUT)) View.VISIBLE else View.GONE }
        carelinkFields.forEach { it.visibility = if (used(LinkSource.CARELINK)) View.VISIBLE else View.GONE }
        carelinkStatus.text = PhoneCareLinkStore(this).load()?.let { "Signed in (${it.country})" } ?: "Not signed in"
    }

    private fun connectionSettings(): PhoneSettings? {
        val old = store.load()
        val sinceText = deviceSince.text.toString().trim()
        val since = if (sinceText.isEmpty()) null else runCatching { LocalDate.parse(sinceText) }.getOrElse {
            result.text = "Device start date must be yyyy-MM-dd."
            return null
        }
        val deviceName = latestDevice.findViewById<RadioButton>(latestDevice.checkedRadioButtonId)?.tag as? String
        return old.copy(
            source = LinkSource.valueOf(selected(source)),
            dexcomNotifications = dexcomNotifications.isChecked,
            username = username.text.toString().trim(),
            password = password.text.toString(),
            region = Region.valueOf(selected(region)),
            nightscoutUrl = nightscoutUrl.text.toString().trim(),
            nightscoutToken = nightscoutToken.text.toString().trim(),
            nightscoutApi = NightscoutApi.valueOf(selected(nightscoutApi)),
            unit = GlucoseUnit.valueOf(selected(unit)),
            latestDevice = deviceName?.let { runCatching { LatestDevice.valueOf(it) }.getOrNull() },
            latestDeviceSince = since,
            alsoFrom = therapySources.filterValues { it.isChecked }.keys.toSet(),
            carelinkCountry = carelinkCountry.text.toString().trim().uppercase(),
        )
    }

    private fun saveAndTest() {
        val old = store.load()
        val new = connectionSettings() ?: return
        val providerChanged = old.glucoseProvider != new.glucoseProvider &&
            old.source != LinkSource.DEMO && new.source != LinkSource.DEMO
        if (providerChanged) {
            AlertDialog.Builder(this)
                .setTitle("Change glucose source?")
                .setMessage("You are changing from ${old.sourceLabel} to ${new.sourceLabel}. " +
                    "Keep the glucose history already on the chart, or clear it and start with the new source?")
                .setPositiveButton("Keep history") { _, _ -> applyConnectionSettings(old, new, keepHistory = true) }
                .setNegativeButton("Clear history") { _, _ -> applyConnectionSettings(old, new, keepHistory = false) }
                .setNeutralButton("Cancel", null)
                .show()
            return
        }
        applyConnectionSettings(old, new, keepHistory = false)
    }

    /** Keep the forecast that is on screen now, so later readings can be laid over it. */
    private fun checkPrediction() {
        scope.launch {
            val state = repository.state()
            val minutes = repository.defaultForecastMinutes(state)
            val prediction = (repository.forecast(state, minutes) ?: chart.forecast?.let(repository::present))
            val message = if (prediction == null || prediction.points.isEmpty()) {
                "No forecast to store. Pick a model, then check."
            } else {
                PredictionChecks.record(File(filesDir, PredictionChecks.FILE_NAME), prediction)
                val stored = prediction.points.lastOrNull()?.let { point ->
                    state.latest?.let { ((point.timeMillis - it.timeMillis) / 60_000L).toInt() }
                } ?: minutes
                "Stored this forecast, $stored min. Prediction history compares it with later readings."
            }
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun applyConnectionSettings(old: PhoneSettings, new: PhoneSettings, keepHistory: Boolean) {
        result.text = "Testing…"
        scope.launch {
            repository.changeSettings(new, keepHistory)
            // Demo data brings its own heart rate; a real source reads Health Connect.
            if (new.source != old.source) refreshHeartRate()
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

    private fun importConfiguration(read: suspend () -> ImportedConfiguration) {
        if (configurationImport?.isActive == true) return
        modelLookup?.cancel()
        fun enabled(view: View, value: Boolean) {
            view.isEnabled = value
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enabled(view.getChildAt(i), value)
        }
        sections.drop(1).take(2).forEach { enabled(it, false) }
        configStatus.text = "Reading configuration…"
        configurationImport = scope.launch {
            try {
                val imported = read() // Parse and validate the entire document before changing anything.
                val previous = store.load()
                var settings = imported.applyTo(previous)
                // A different glucose account must not silently inherit another person's pump.
                if (settings.accountKey != previous.accountKey && imported.alsoFrom == null)
                    settings = settings.copy(alsoFrom = emptySet())
                val carelinkStore = PhoneCareLinkStore(this@MainActivity)
                val clearCarelink = imported.carelinkToken == null && imported["CARELINK_COUNTRY"] != null &&
                    carelinkStore.load()?.country?.equals(settings.carelinkCountry, true) == false
                if (clearCarelink) {
                    settings = settings.copy(carelinkAccount = "")
                }
                repository.changeSettings(settings, keepGlucoseHistory = false) {
                    imported.carelinkToken?.let(carelinkStore::save)
                    if (clearCarelink) carelinkStore.save(null)
                }
                fillConfigurationFields(settings)
                var modelOutcome = ""
                if (!imported["HF_MODEL_ADDRESS"].isNullOrBlank()) {
                    configStatus.text = "Settings imported. Downloading and checking prediction model…"
                    try {
                        val address = settings.modelAddress
                        val model = if (HuggingFaceModel.isSearch(address)) {
                            val matches = modelStore.searchHuggingFace(address, settings.huggingFaceToken)
                            require(matches.size == 1) { "Use owner/model when a model name matches multiple repositories" }
                            matches.single().repo
                        } else address
                        val listing = modelStore.listHuggingFace(model, settings.huggingFaceToken)
                        val file = when {
                            listing.files.size == 1 -> listing.files.single()
                            "onnx/model.onnx" in listing.files -> "onnx/model.onnx"
                            "model.onnx" in listing.files -> "model.onnx"
                            else -> error("Repository has multiple ONNX variants; supply a link to the desired file")
                        }
                        modelStore.importHuggingFace(listing.model, file, settings.huggingFaceToken)
                        // Merge with current preferences rather than overwrite changes from another activity.
                        settings = store.load().copy(predictorId = OnnxPredictor.ID)
                        repository.changeSettings(settings, keepGlucoseHistory = false)
                        checkRadio(predictor, settings.predictorId)
                        modelOutcome = " Prediction model downloaded and selected."
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        modelOutcome = " Model setup failed; the previous predictor was kept. Check the model address/access and try again on the Model tab."
                    }
                }
                updateModelStatus()
                configStatus.text = "Configuration imported.$modelOutcome Testing connections…"
                val state = repository.refresh()
                render(state)
                configStatus.text = "Configuration imported.$modelOutcome " + when {
                    state.lastError != null -> "Some source data is unavailable; see Today."
                    state.latest == null -> "Connected; no recent glucose readings."
                    else -> "Source test passed."
                }
                val usesCarelink = settings.source == LinkSource.CARELINK || LinkSource.CARELINK in settings.extras
                if (usesCarelink && carelinkStore.load() == null) {
                    configStatus.append(" Complete CareLink browser sign-in to connect the pump.")
                    startActivity(Intent(this@MainActivity, CareLinkSignInActivity::class.java)
                        .putExtra("country", settings.carelinkCountry))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { configStatus.text = "Could not import configuration: ${e.message ?: "invalid file"}" }
            finally {
                sections.drop(1).take(2).forEach { enabled(it, true) }
                updateVisibility()
            }
        }
    }

    private fun checkRadio(group: RadioGroup, selected: String) {
        for (i in 0 until group.childCount) {
            val radio = group.getChildAt(i) as RadioButton
            if (radio.tag == selected) group.check(radio.id)
        }
    }

    private fun fillConfigurationFields(s: PhoneSettings) {
        username.setText(s.username); password.setText(s.password)
        nightscoutUrl.setText(s.nightscoutUrl); nightscoutToken.setText(s.nightscoutToken)
        carelinkCountry.setText(s.carelinkCountry)
        modelInput.setText(s.modelAddress); modelToken.setText(s.huggingFaceToken); otherModelToken.setText(s.otherModelToken)
        checkRadio(source, s.source.name); checkRadio(region, s.region.name)
        checkRadio(nightscoutApi, s.nightscoutApi.name); checkRadio(unit, s.unit.name)
        dexcomNotifications.isChecked = s.dexcomNotifications
        therapySources.forEach { (of, checkbox) -> checkbox.isChecked = of in s.alsoFrom }
        updateVisibility()
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
        PairingWindow.open(this)
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
            open -> listOfNotNull(LinkService.problem,
                "Waiting for watch…\nOn the watch: GlucoWatch → Settings → Pair with phone. If it is already searching, leave it there; it will connect automatically. This phone waits for 3 minutes.").joinToString("\n")
            PairingWindow.expired -> "Pairing timed out. Start again here or on the watch, then start the other device."
            else -> ""
        }
        pairingText.visibility = if (pairingText.text.isEmpty()) View.GONE else View.VISIBLE
        pairingCode.text = pending?.keys?.displayCode.orEmpty()
        pairingCode.visibility = if (pending != null) View.VISIBLE else View.GONE
        pairingButtons.visibility = if (pending != null || open) View.VISIBLE else View.GONE
        confirmPairButton.visibility = if (pending != null) View.VISIBLE else View.GONE
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

    /** An explicit, accessible password control; the eye reinforces what the checkbox reveals. */
    private fun passwordToggle(field: EditText, text: String) = CheckBox(this).apply {
        this.text = text
        setTextColor(Brand.MUTED)
        buttonTintList = android.content.res.ColorStateList.valueOf(Brand.MUTED)
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_visibility, 0)
        compoundDrawablePadding = dp(8)
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Brand.MUTED)
        setOnCheckedChangeListener { _, visible ->
            field.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
            field.setSelection(field.text.length)
        }
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

    private fun metricColumn(title: String, number: TextView, note: TextView? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 12f; setTextColor(Brand.MUTED); gravity = Gravity.CENTER
        })
        addView(number)
        note?.let(::addView)
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
        const val REQUEST_CONFIG = 5
        const val REQUEST_EXPORT = 6
        const val REQUEST_EXPORT_FOLDER = 7
        const val REQUEST_HISTORY_PORTAL = 8
        const val REQUEST_WORKOUT = 9

        /** The period presets under the chart; pinching moves freely between them. */
        val PERIODS = listOf(3, 6, 12, 24)

        /** How much heart rate to load: enough for the widest window the chart can be pinched to. */
        const val HEART_HOURS = 48

        const val MENU_REFRESH = 1
        const val MENU_EXPORT = 6
        const val MENU_WORKOUT = 7
        const val MENU_INSULIN_ANALYSIS = 8
        const val MENU_CHECK = 2
        const val MENU_HISTORY = 3
        const val MENU_PRIVACY = 4
        const val MENU_ABOUT = 5

        /** A repo can hold dozens of exports; more than this is noise in a phone-sized list. */
        const val MAX_LISTED_FILES = 12
    }
}
