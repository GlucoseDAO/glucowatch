package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowInsets
import android.widget.*
import glucowatch.core.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/** Phone-only retrospective exercise library. All parsing and history joins stay on device. */
class ExerciseActivity : Activity() {
    private val scope = MainScope()
    private val store by lazy { WorkoutStore(this) }
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var work: Job? = null
    private var entries = emptyList<WorkoutStore.Entry>()
    private var selected: String? = null
    private var detailGeneration = 0
    private var query = ""; private var sport = "All sports"; private var page = 0
    private val zone get() = ZoneId.systemDefault()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(18)); setBackgroundColor(Brand.BACKGROUND) }
        root.addView(Button(this).apply { text = "Back"; Brand.style(this, false); setOnClickListener { if (selected != null) { selected = null; library() } else finish() } })
        status = TextView(this).apply { setTextColor(Brand.MUTED); setPadding(0, dp(8), 0, dp(8)) }; root.addView(status)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) { val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); view.setPadding(dp(18)+b.left,b.top,dp(18)+b.right,b.bottom) }
            else { @Suppress("DEPRECATION") view.setPadding(dp(18)+insets.systemWindowInsetLeft,insets.systemWindowInsetTop,dp(18)+insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }; insets
        }
        setContentView(root)
        selected = state?.getString("selected") ?: intent.getStringExtra("workoutId")
        library()
        if (state == null && intent.action in listOf(Intent.ACTION_SEND, Intent.ACTION_VIEW)) {
            @Suppress("DEPRECATION")
            val uri: Uri? = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM) else intent.data
            uri?.takeIf { it.scheme == "content" }?.let { importActivities(it, true) }
        }
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("selected", selected); super.onSaveInstanceState(out) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun text(value: String, size: Float = 15f, parent: LinearLayout = body) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Brand.TEXT); setPadding(0, dp(7), 0, dp(7))
    }.also(parent::addView)
    private fun button(value: String, parent: LinearLayout = body, action: () -> Unit) { parent.addView(Button(this).apply { text = value; Brand.style(this, false); setOnClickListener { if (work?.isActive != true) action() } }) }
    private fun picker(request: Int, multiple: Boolean = false) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), request)
    }
    private fun library() {
        body.removeAllViews(); text("Exercise", 28f)
        button("Import Strava ZIP") { picker(ZIP) }
        button("Add activity · FIT, GPX or TCX") { picker(ACTIVITY) }
        button("Import historical glucose / pump CSV") { picker(HISTORY) }
        button("Workout zones, Strava downloads & PDF reports") { startActivity(Intent(this, WorkoutImportActivity::class.java)) }
        text("Choose a ZIP directly from Files, Downloads or a connected cloud drive. Originals stay private on this phone. Later imports add to this library; exact recording duplicates are reused.")
        val search = EditText(this).apply { hint = "Search activity or date"; setTextColor(Brand.TEXT); setHintTextColor(Brand.MUTED); setSingleLine(); setText(query) }; body.addView(search)
        val sports = Spinner(this); body.addView(sports)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; body.addView(list)
        fun show() {
            list.removeAllViews()
            val matches = entries.filter { (sport == "All sports" || it.sport == sport) && (query.isBlank() || (it.name + " " + stamp(it.start)).contains(query, true)) }
            val pages = ((matches.size + 39) / 40).coerceAtLeast(1); page = page.coerceIn(0, pages - 1)
            text("${matches.size} activities · page ${page + 1}/$pages", parent = list)
            if (matches.isEmpty()) text("No matching activities. Import your Strava archive or an individual activity.", parent = list)
            matches.drop(page * 40).take(40).forEach { e -> button("${e.name}\n${stamp(e.start)} · ${e.sport.ifBlank { "Activity" }}", list) { detail(e.id) } }
            if (page > 0) button("Previous activities", list) { page--; show() }
            if (page + 1 < pages) button("Next activities", list) { page++; show() }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); page = 0; show() }
        })
        scope.launch {
            try {
                entries = withContext(Dispatchers.IO) { store.entries() }
                val options = listOf("All sports") + entries.map { it.sport }.filter { it.isNotBlank() }.distinct().sorted()
                sports.adapter = ArrayAdapter(this@ExerciseActivity, android.R.layout.simple_spinner_dropdown_item, options)
                sports.setSelection(options.indexOf(sport).coerceAtLeast(0))
                sports.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { sport = options[position]; page = 0; show() }
                }
                show()
                selected?.let { detail(it) }
            } catch (e: Exception) { status.text = "Could not read exercise archive: ${e.message}" }
        }
    }
    private fun importActivities(uri: Uri, zip: Boolean) {
        if (work?.isActive == true) return
        status.text = "Reading activities… Keep this page open. Saved activities survive interruption."
        work = scope.launch {
            var fresh = 0; var reused = 0
            try {
                val issues = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    fun accept(r: StravaArchive.Recording) {
                        job.ensureActive()
                        if (store.import(r).second) fresh++ else reused++
                        if ((fresh + reused) % 10 == 0) runOnUiThread { status.text = "Imported $fresh · already saved $reused…" }
                    }
                    if (zip) {
                        val temporary = java.io.File.createTempFile("strava-", ".zip", cacheDir)
                        try {
                            contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024); var total = 0L
                                while (true) { job.ensureActive(); val n = input.read(buffer); if (n < 0) break; total += n
                                    require(total <= StravaArchive.MAX_ARCHIVE_BYTES) { "ZIP exceeds 2 GB." }; output.write(buffer, 0, n) }
                            } } ?: error("Could not open the ZIP.")
                            StravaArchive.read(temporary, ::accept).issues
                        } finally { temporary.delete() }
                    } else {
                        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("Missing activity filename.")
                        val bytes = contentResolver.openInputStream(uri)?.let { StravaArchive.bounded(it, FitWorkoutReader.MAX_BYTES) } ?: error("Could not open activity.")
                        val unpacked = if (name.endsWith(".gz", true)) StravaArchive.bounded(java.util.zip.GZIPInputStream(bytes.inputStream()), FitWorkoutReader.MAX_BYTES) else bytes
                        val filename = if (name.endsWith(".gz", true)) name.dropLast(3) else name
                        accept(StravaArchive.Recording(WorkoutRecording.read(unpacked, filename), unpacked, filename))
                        emptyList()
                    }
                }
                selected = null; library()
                status.text = "$fresh activities added · $reused already saved · ${issues.size} import notes."
                if (issues.isNotEmpty()) AlertDialog.Builder(this@ExerciseActivity).setTitle("Import notes")
                    .setMessage(issues.take(80).joinToString("\n") + if (issues.size > 80) "\n…${issues.size - 80} more notes." else "")
                    .setPositiveButton("Close", null).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Import stopped: ${e.message}. $fresh activities saved; retrying reuses duplicates." }
        }
    }
    private fun history(uri: Uri) {
        if (work?.isActive == true) return
        work = scope.launch {
            try {
                val settings = PhoneSettingsStore(this@ExerciseActivity).load()
                val raw = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.let { StravaArchive.bounded(it, HistoricalCsv.MAX_BYTES).toString(Charsets.UTF_8) } ?: error("Could not open CSV.") }
                val source = HistoricalCsv.source(raw)
                val inferred = HistoricalCsv.inferDateOrder(raw)
                val order = inferred ?: suspendCancellableCoroutine<HistoricalCsv.DateOrder> { continuation ->
                    val dialog = AlertDialog.Builder(this@ExerciseActivity).setTitle("CSV date format")
                        .setItems(arrayOf("Day / month / year", "Month / day / year")) { _, index -> continuation.resume(if (index == 0) HistoricalCsv.DateOrder.DAY_FIRST else HistoricalCsv.DateOrder.MONTH_FIRST) }
                        .setOnCancelListener { continuation.cancel() }.show()
                    continuation.invokeOnCancellation { dialog.dismiss() }
                }
                val rows = withContext(Dispatchers.IO) {
                    HistoricalCsv.parse(raw, source, zone, order).also { require(it.isNotEmpty()) { "No usable events in CSV." }; ExerciseHistoryStore(this@ExerciseActivity).save(it, settings) }
                }
                status.text = "Saved ${rows.size} historical events for exercise comparisons."
                selected?.let { detail(it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Historical import failed: ${e.message}" }
        }
    }
    private fun detail(id: String, day: Boolean = false) {
        selected = id
        val generation = ++detailGeneration
        scope.launch {
            try {
                val settings = PhoneSettingsStore(this@ExerciseActivity).load()
                val result = withContext(Dispatchers.IO) {
                    val workout = store.load(id)
                    val first = Instant.ofEpochMilli(workout.startMillis).atZone(zone).toLocalDate()
                    val last = Instant.ofEpochMilli(workout.endMillis).atZone(zone).toLocalDate()
                    val from = first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val until = last.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
                    val window = ExportWindow(LatestDevice.MINIMED_PUMP, first.minusDays(1), first.minusDays(1), last.plusDays(1), first.minusDays(1), allowIncomplete = true)
                    val repository = PhoneRepository(this@ExerciseActivity)
                    val live = repository.exportTables(window)
                    val state = repository.state()
                    val liveGlucose = if (settings.source != glucowatch.core.link.LinkSource.DEMO && state.settings.configurationKey == settings.configurationKey)
                        state.readings.filter { it.timeMillis in from until until }.map { ExportRow.fromReading(it, settings.source.name.lowercase()) } else emptyList()
                    val historical = ExerciseHistoryStore(this@ExerciseActivity).load(settings, from, until)
                    val rows = ExportLog.prefer(historical, ExportLog.prefer(live.rows, liveGlucose))
                    val glucose = rows.filter { it.source != ExportRow.SOURCE_CARELINK }.mapNotNull(ExportRow::toReading)
                        .ifEmpty { rows.mapNotNull(ExportRow::toReading) }.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
                    Triple(workout, rows, glucose)
                }
                check(PhoneSettingsStore(this@ExerciseActivity).load().configurationKey == settings.configurationKey) { "Source changed. Reopen this activity." }
                if (selected != id || generation != detailGeneration) return@launch
                val (w, raw, glucose) = result
                val rows = WorkoutAnnotations.add(raw, listOf(w))
                val from = if (day) Instant.ofEpochMilli(w.startMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() else w.startMillis
                val until = if (day) Instant.ofEpochMilli(w.endMillis).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() else maxOf(w.endMillis, w.startMillis + 1)
                val analysis = withContext(Dispatchers.Default) { WorkoutAnalysis.analyse(w, heartBoundaries = WorkoutZones.read(this@ExerciseActivity), paceBoundaries = WorkoutZones.pace(this@ExerciseActivity)) }
                if (selected != id || generation != detailGeneration) return@launch
                body.removeAllViews(); text(w.name, 26f); text("${w.sport} · ${stamp(w.startMillis)} to ${stamp(w.endMillis)}\n${zone.id}")
                button(if (day) "Activity interval" else "Whole-day context") { detail(id, !day) }
                val period = ExerciseComparison.period(glucose, rows, zone, from, until)
                text(if (day) "Whole day" else "During activity", 20f); summary(period)
                text("${w.distanceM?.let { "%.2f km".format(Locale.US, it / 1000) } ?: "Distance unavailable"} · elapsed ${(w.endMillis - w.startMillis) / 60_000} min\nAscent ${w.ascentM?.toInt() ?: "unavailable"} m · descent ${w.descentM?.toInt() ?: "unavailable"} m")
                if (w.samples.isEmpty()) text("Summary only: Strava supplied no timestamped observations. Heart-rate and motion traces are unavailable.")
                val inspect = text("Tap a trace to inspect values at one time. Drag to pan; pinch to zoom.")
                body.addView(ExerciseChartView(this@ExerciseActivity, w, analysis, glucose, rows, from, until) { inspect.text = it }, LinearLayout.LayoutParams(-1, dp(680)))
                text("Heart-rate zones", 20f); zones(analysis.heartZones, WorkoutZones.read(this@ExerciseActivity).map(Int::toString), "bpm")
                text("Heart rate: average ${(analysis.heartMean ?: w.summaryHeartRateMean)?.toInt() ?: "unavailable"} · maximum ${(analysis.heartMax ?: w.summaryHeartRateMax)?.toInt() ?: "unavailable"} bpm${if (analysis.heart.isEmpty() && w.summaryHeartRateMean != null) " (Strava summary)" else ""}")
                if (w.usesRunningGap) { text("Estimated GAP zones", 20f); zones(analysis.gapZones, WorkoutZones.pace(this@ExerciseActivity).map { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }, "min/km", reverse = true) }
                if (analysis.motionMean != null) text("${analysis.motionTitle}: %.2f ${analysis.motionUnit}".format(Locale.US, analysis.motionMean))
                if (w.endMillis == w.startMillis) text("Strava recorded no elapsed interval. Duration comparisons are unavailable.")
                text("Before / during / after", 20f)
                listOf("Hour before" to (w.startMillis - 3_600_000L to w.startMillis), "Activity" to (w.startMillis to maxOf(w.endMillis, w.startMillis + 1)), "Hour after" to (w.endMillis to w.endMillis + 3_600_000L)).forEach { (label, bounds) ->
                    text(label, 18f); summary(ExerciseComparison.period(glucose, rows, zone, bounds.first, bounds.second))
                }
                button("Add glucose / pump CSV for comparison") { picker(HISTORY) }
                text("Grey background = recorded elevation. Gaps stay visible. GPX distances are derived from recorded coordinates within continuous segments. Estimated GAP uses Minetti's model, not Strava's proprietary GAP. Basal is calculated from settings × time, not measured delivery. Zero logged events does not prove zero insulin or food.")
                status.text = if (period.readings.isEmpty()) "Activity saved. No matching glucose yet; import the relevant historical CSV." else "${period.readings.size} matching glucose readings. Comparisons update when more history is imported."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Could not open activity: ${e.message}" }
        }
    }
    private fun summary(p: ExerciseComparison.Period) {
        if (p.readings.isEmpty()) text("Glucose unavailable for this interval.")
        else {
            fun n(v: Double?) = v?.let { "%.1f".format(Locale.US, it) } ?: "unavailable"
            text("Mean ${n(p.mean)} mg/dL · SD ${n(p.sd)}\n${p.readings.size} readings · observed samples in 70–180: ${n(p.ranges.percent(p.ranges.inRange))}%\nVery low ${n(p.ranges.percent(p.ranges.veryLow))}% · low ${n(p.ranges.percent(p.ranges.low))}% · high ${n(p.ranges.percent(p.ranges.high))}% · very high ${n(p.ranges.percent(p.ranges.veryHigh))}%")
        }
        fun units(v: Double?) = v?.let { "%.2f U".format(Locale.US, it) } ?: "unavailable"
        text("Recorded bolus: ${units(p.bolus)}\nCalculated basal: ${units(p.basal.units)}${if (p.basal.units != null && !p.basal.complete) " (partial coverage)" else ""}\nLogged carbs: ${p.carbs?.let { "%.0f g".format(Locale.US, it) } ?: "unavailable"}")
    }
    private fun zones(seconds: List<Double>, boundaries: List<String>, unit: String, reverse: Boolean = false) {
        val total = seconds.sum()
        if (total == 0.0 || boundaries.isEmpty()) { text("Zone observations unavailable."); return }
        seconds.forEachIndexed { index, value ->
            val range = if (reverse) when (index) { 0 -> "${boundaries.last()}+"; boundaries.size -> "<${boundaries.first()}"; else -> "${boundaries[boundaries.lastIndex - index]}–${boundaries[boundaries.size - index]}" }
                else when (index) { 0 -> "<${boundaries.first()}"; boundaries.size -> "${boundaries.last()}+"; else -> "${boundaries[index - 1]}–${boundaries[index]}" }
            text("Z${index + 1}: ${"%.1f".format(Locale.US, value / total * 100)}% · ${(value / 60).toInt()} min · $range $unit")
        }
    }
    private fun stamp(time: Long) = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(time).atZone(zone))
    @Deprecated("Platform result API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) return
        val uri = data?.data ?: return
        when (request) { ZIP -> importActivities(uri, true); ACTIVITY -> importActivities(uri, false); HISTORY -> history(uri) }
    }
    companion object { private const val ZIP = 20; private const val ACTIVITY = 21; private const val HISTORY = 22 }
}
