package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.CheckBox
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Browser-owned Google sign-in. Only a user-downloaded file is imported; no site scripts. */
class WorkoutImportActivity : Activity() {
    private val scope = MainScope()
    private val store by lazy { WorkoutStore(this) }
    private val folder by lazy { ExportDownloadFolder(this) }
    private val pending by lazy { getSharedPreferences("workout-import", MODE_PRIVATE) }
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private var watch: Job? = null
    private var busy = false
    private var restored = false
    private var renderGeneration = 0
    private val heartInputs = mutableListOf<EditText>()
    private val paceInputs = mutableListOf<EditText>()
    private lateinit var generateReport: CheckBox
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Brand.BACKGROUND) }
        setContentView(ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        })
        render()
        scope.launch {
            try { withContext(Dispatchers.IO) { ReportFiles.ensureLayout(this@WorkoutImportActivity) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Could not prepare download folders: ${e.message}" }
        }
        consumeReady(intent)
    }
    private fun button(label: String, parent: LinearLayout = column, action: () -> Unit) { parent.addView(Button(this).apply {
        text = label; Brand.style(this, false); setOnClickListener { if (!busy) action() }
    }) }
    private fun text(label: String, parent: LinearLayout = column) = TextView(this).apply { text = label; textSize = 15f; setTextColor(Brand.TEXT); setPadding(4, 12, 4, 12) }.also(parent::addView)
    private fun render() {
        val generation = ++renderGeneration
        column.removeAllViews()
        text("Workouts").textSize = 24f
        text("Pace zones · estimated grade-adjusted pace (min:sec/km)").textSize = 18f
        paceInputs.clear(); heartInputs.clear()
        val pace = WorkoutZones.pace(this)
        pace.forEachIndexed { index, value -> zoneField("Zone ${6 - index} · ${if (index == 0) "faster than" else "up to"}",
            "${value / 60}:${(value % 60).toString().padStart(2, '0')}", paceInputs, pace = true) }
        text("Zone 1 · slower than the last pace boundary")
        text("Heart-rate zones · bpm").textSize = 18f
        val heart = WorkoutZones.read(this)
        repeat(4) { index -> zoneField("Zone ${index + 1} · below", heart.getOrNull(index)?.toString().orEmpty(), heartInputs, pace = false) }
        text("Zone 5 · at or above the last heart-rate boundary. Clear all heart-rate fields to omit HR zones.")
        text("Zone edits are saved automatically when all boundaries are valid.")
        column.addView(CheckBox(this).apply {
            text="Pump disconnected during swimming";setTextColor(Brand.TEXT);isChecked=store.swimPumpDisconnected()
            setOnCheckedChangeListener { _,checked->store.setSwimPumpDisconnected(checked) }
        })
        text("Disconnection is user-reported. Reports use the swim's FIT session bounds as approximate timing and retain raw pump records.")
        generateReport = CheckBox(this).apply {
            text = "Generate report per activity"; setTextColor(Brand.TEXT)
            isChecked = pending.getBoolean("generateReport", false)
            setOnCheckedChangeListener { _, checked -> pending.edit().putBoolean("generateReport", checked).apply() }
        }
        column.addView(generateReport)
        button("Import workout from Strava") { if (saveZones(showErrors = true)) openStrava(generateReport.isChecked) }
        button("Generate report from downloaded FIT files") {
            if (saveZones(showErrors = true)) {
                pending.edit().putBoolean("report", true).apply(); chooseFit()
            }
        }
        if (Build.VERSION.SDK_INT >= 31) button("Select download folder") {
            AlertDialog.Builder(this).setTitle("Workout download folder")
                .setMessage("Select GlucoPhone/Raw to include its Dexcom, Medtronic and Strava subfolders, or choose a browser download folder. Untouched downloads keep their names in Downloads/GlucoPhone/Raw/Strava/Unedited. Dated, arranged copies are in Raw/Strava.")
                .setPositiveButton("Choose folder") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FGlucoPhone%2FRaw")), FOLDER) }
                .setNegativeButton("Cancel", null).show()
        }
        val savedColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(column::addView)
        status = text("Archived copies use the workout start date and time, not the download date.")
        button("Stop waiting for workout") {
            pending.edit().remove("since").remove("report").apply(); watch?.cancel(); ExportDownloadService.stop(this); status.text = "Import cancelled. Downloaded files are kept."
        }
        scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { store.entries() }
                if (generation != renderGeneration) return@launch
                if (entries.isNotEmpty()) text("Saved workouts", savedColumn)
                val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                entries.forEach { e -> button("${format.format(Instant.ofEpochMilli(e.start))} · ${e.name}", savedColumn) {
                    AlertDialog.Builder(this@WorkoutImportActivity).setTitle(e.name).setMessage("Saved as ${e.file}\n\nGenerate a PDF for this workout using sensor and pump data?")
                        .setPositiveButton("Event report") { _, _ -> report(e.id) }.setNegativeButton("Close", null).show()
                } }
            } catch (e: Exception) { status.text = e.message ?: "Could not read workout archive." }
        }
    }
    private fun zoneField(label: String, value: String, fields: MutableList<EditText>, pace: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = label; setTextColor(Brand.TEXT) }, LinearLayout.LayoutParams(0, -2, 1f))
        val field = EditText(this).apply {
            setText(value); setTextColor(Brand.TEXT); setSingleLine()
            inputType = if (pace) InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME else InputType.TYPE_CLASS_NUMBER
            contentDescription = "${if (pace) "Pace" else "Heart rate"} $label"
        }
        fields += field; row.addView(field, LinearLayout.LayoutParams(180, -2)); column.addView(row)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { saveZones(showErrors = false) }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun saveZones(showErrors: Boolean): Boolean {
        if (paceInputs.size != 5 || heartInputs.size != 4) return false
        val pace = paceInputs.map { input ->
            val parts = input.text.toString().trim().split(':')
            val minutes = parts.getOrNull(0)?.toIntOrNull(); val seconds = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && minutes != null && minutes in 0..120 && seconds != null && seconds in 0..59) minutes * 60 + seconds else null
        }
        val heart = heartInputs.map { it.text.toString().trim().toIntOrNull() }
        val heartEmpty = heartInputs.all { it.text.isBlank() }
        val paceValues = pace.filterNotNull(); val heartValues = heart.filterNotNull()
        val paceValid = paceValues.size == 5 && paceValues.first() > 0 && paceValues == paceValues.sorted().distinct()
        val heartValid = heartEmpty || (heartValues.size == 4 && heartValues.all { it in 1..250 } && heartValues == heartValues.sorted().distinct())
        if (paceValid) WorkoutZones.savePace(this, paceValues)
        if (heartValid) WorkoutZones.save(this, if (heartEmpty) emptyList() else heartValues)
        if (showErrors) {
            paceInputs.forEach { it.error = if (paceValid) null else "Use five increasing min:sec boundaries" }
            heartInputs.forEach { it.error = if (heartValid) null else "Use four increasing bpm boundaries, or clear all four" }
        }
        return paceValid && heartValid
    }
    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next); setIntent(next); consumeReady(next)
    }
    private fun consumeReady(next: Intent) {
        if (next.action != READY) return
        val id = next.getStringExtra("workoutId") ?: return
        scope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { store.entries().single { it.id == id } }
                accepted(entry to next.getBooleanExtra("fresh", false))
                setIntent(Intent(next).setAction(Intent.ACTION_MAIN))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Could not open the imported workout. Choose it from saved workouts." }
        }
    }
    override fun onResume() {
        super.onResume()
        if (!restored) {
            restored = true
            if (intent.action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) {
                @Suppress("DEPRECATION")
                val uri: Uri? = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM) else intent.data
                uri?.takeIf { it.scheme == "content" }?.let { import(it); return }
            }
        }
        watchFolder()
    }
    private fun openStrava(eventReport: Boolean) {
        scope.launch {
            watch?.cancelAndJoin(); watch = null
            pending.edit().putLong("since", System.currentTimeMillis()).putBoolean("report", eventReport).apply()
            if (!ExportBrowser.openChrome(this@WorkoutImportActivity, "https://www.strava.com/athlete/training", BROWSER)) {
                pending.edit().remove("since").remove("report").apply()
                status.text = "Chrome is required for this sign-in. Install or enable Chrome, then tap Import workout from Strava again."
                return@launch
            }
            watchFolder()
        }
    }
    private fun chooseFit() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary:Download"))
        }, FIT)
    }
    private fun watchFolder() {
        if (watch?.isActive == true || folder.tree() == null || busy) return
        val since = pending.getLong("since", 0)
        if (since <= 0 || System.currentTimeMillis() - since > 86_400_000) return
        runCatching { ExportDownloadService.start(this, workout = true) }
        watch = scope.launch {
            val seen = mutableMapOf<String, Long>()
            val rejected = mutableSetOf<String>()
            try {
                status.text = "Waiting for a completed FIT in the selected download folder."
                while (isActive && !busy && pending.getLong("since", 0) == since) {
                    val files = withContext(Dispatchers.IO) { folder.fitFiles(since) }
                    for (f in files) {
                        if (f.fingerprint in rejected) continue
                        val first = seen.getOrPut(f.fingerprint) { android.os.SystemClock.elapsedRealtime() }
                        if (android.os.SystemClock.elapsedRealtime() - first < 1500 || !withContext(Dispatchers.IO) { folder.indexed(f) }) continue
                        // FIT CRC/length validation also prevents accepting an incomplete download.
                        val result = runCatching { withContext(Dispatchers.IO) { store.import(f.uri) } }
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()
                            if (error is CancellationException) throw error
                            rejected += f.fingerprint; status.text = error?.message; continue
                        }
                        if (pending.getLong("since", 0) != since) return@launch
                        val (entry, fresh) = result.getOrThrow()
                        startActivity(Intent(this@WorkoutImportActivity, WorkoutImportActivity::class.java).setAction(READY)
                            .putExtra("workoutId", entry.id).putExtra("fresh", fresh)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        return@launch
                    }
                    delay(500)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Collection stopped. Choose downloaded FIT to continue." }
            finally { ExportDownloadService.stop(this@WorkoutImportActivity) }
        }
    }
    private fun import(uri: Uri) {
        if (busy) return
        val name = runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty()
        if (name.endsWith(".zip", true)) {
            startActivity(Intent(this, ExerciseActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)
                .apply { clipData = android.content.ClipData.newRawUri("Strava ZIP", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            finish(); return
        }
        watch?.cancel(); busy = true; status.text = "Validating and saving FIT…"
        scope.launch {
            try { accepted(withContext(Dispatchers.IO) { store.import(uri) }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = e.message ?: "Could not import FIT." }
            finally { busy = false }
        }
    }
    private fun accepted(result: Pair<WorkoutStore.Entry, Boolean>) {
        val (entry, fresh) = result
        val generate = pending.getBoolean("report", false)
        pending.edit().remove("since").remove("report").apply()
        ExportDownloadService.stop(this)
        if (generate) { report(entry.id); return }
        render()
        status.text = (if (fresh) "Workout imported" else "Already imported") + ": ${entry.file}." +
            if (entry.uri != null) " Original saved in Raw/Strava/Unedited; dated copy in Raw/Strava." else " Saved in the private workout archive; Downloads copy unavailable."
        AlertDialog.Builder(this).setTitle(if (fresh) "Workout imported" else "Already imported")
            .setMessage("${entry.name}\n${entry.file}")
            .setPositiveButton("Done", null).setNeutralButton("Generate event report") { _, _ -> report(entry.id) }.show()
    }
    private fun report(id: String) = report(listOf(id))
    private fun report(ids: List<String>) {
        if (!saveZones(showErrors = true)) return
        scope.launch {
            try {
                val workouts = withContext(Dispatchers.IO) { ids.distinct().map(store::load) }
                val range = glucowatch.core.WorkoutReportRange.covering(workouts, ZoneId.systemDefault())
                require(range.last < java.time.LocalDate.now()) {
                    "Activity files are saved. CareLink can export only completed days; generate reports for today's activities tomorrow."
                }
                val box = LinearLayout(this@WorkoutImportActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 12, 36, 12) }
                box.addView(TextView(this@WorkoutImportActivity).apply {
                    text = "${workouts.size} selected activities require sensor and pump data from ${range.first} to ${range.last}, including both dates. You can extend this interval."
                })
                var start = range.first; var end = range.last
                val from = Button(this@WorkoutImportActivity).apply { text = "Start: $start" }
                val until = Button(this@WorkoutImportActivity).apply { text = "End: $end" }
                fun pick(initial: java.time.LocalDate, action: (java.time.LocalDate) -> Unit) {
                    android.app.DatePickerDialog(this@WorkoutImportActivity, { _, year, month, day -> action(java.time.LocalDate.of(year, month + 1, day)) },
                        initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                        datePicker.maxDate = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()-1
                    }.show()
                }
                from.setOnClickListener { pick(start) { start = it; from.text = "Start: $start" } }
                until.setOnClickListener { pick(end) { end = it; until.text = "End: $end" } }
                box.addView(from); box.addView(until)
                val error = TextView(this@WorkoutImportActivity).apply { setTextColor(Brand.RED) }; box.addView(error)
                val dialog = AlertDialog.Builder(this@WorkoutImportActivity).setTitle("Confirm report dates")
                    .setView(box).setPositiveButton("Use these dates", null).setNegativeButton("Cancel", null).create()
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        range.validate(start, end)
                        require(end < java.time.LocalDate.now()) { "CareLink exports completed days only. Today’s activity is saved; generate its combined report tomorrow." }
                        val result = Intent().putStringArrayListExtra("workoutIds", ArrayList(ids.distinct()))
                            .putExtra("reportStart", start.toString()).putExtra("reportEnd", end.toString())
                        if (callingActivity != null) setResult(RESULT_OK, result)
                        else startActivity(Intent(this@WorkoutImportActivity, MainActivity::class.java).setAction(ACTION_REPORT)
                            .putExtras(result).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        dialog.dismiss(); finish()
                    } catch (failure: IllegalArgumentException) { error.text = failure.message }
                } }
                dialog.show()
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { status.text = failure.message ?: "Could not prepare activity selection." }
        }
    }
    private fun importSelection(uris: List<Uri>) {
        if (busy) return
        if (uris.size !in 1..50) { status.text = "Select 1 to 50 FIT files."; return }
        if (!saveZones(showErrors = true)) return
        busy = true; watch?.cancel(); status.text = "Validating ${uris.size} FIT files…"
        scope.launch {
            val entries = mutableListOf<WorkoutStore.Entry>()
            val failures = mutableListOf<String>()
            try {
                uris.forEachIndexed { index, uri ->
                    status.text = "Importing activity ${index + 1} of ${uris.size}…"
                    try { entries += withContext(Dispatchers.IO) { store.import(uri).first } }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { failures += "File ${index + 1}: ${failure.message ?: "Could not import FIT"}" }
                }
                pending.edit().remove("since").remove("report").apply(); ExportDownloadService.stop(this@WorkoutImportActivity)
                val ids = entries.map { it.id }.distinct()
                if (failures.isNotEmpty()) {
                    status.text = "${ids.size} activities imported; ${failures.size} files could not be imported."
                    AlertDialog.Builder(this@WorkoutImportActivity).setTitle("FIT import results").setMessage(failures.joinToString("\n"))
                        .setPositiveButton(if (ids.isEmpty()) "Close" else "Report valid activities") { _, _ -> if (ids.isNotEmpty()) report(ids) }
                        .setNegativeButton("Cancel", null).show()
                } else report(ids)
            } finally { busy = false }
        }
    }
    @Deprecated("Platform result API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) {
            if (request == FIT && pending.getLong("since", 0) == 0L) pending.edit().remove("report").apply()
            return
        }
        if (request == FIT) {
            val uris = data?.clipData?.let { clips -> (0 until clips.itemCount).map { clips.getItemAt(it).uri } }
                ?: listOfNotNull(data?.data)
            importSelection(uris.distinct()); return
        }
        val uri = data?.data ?: return
        if (request == FOLDER) runCatching { folder.remember(uri, data.flags); watchFolder() }
            .onFailure { status.text = "Could not retain folder access. Choose a FIT file instead." }
    }
    override fun onDestroy() { watch?.cancel(); scope.cancel(); ExportDownloadService.stop(this); super.onDestroy() }
    companion object {
        const val ACTION_REPORT = "io.github.antonkulaga.glucowatch.phone.WORKOUT_REPORT"
        private const val FIT = 1
        private const val FOLDER = 2
        private const val BROWSER = 3
        private const val READY = "io.github.antonkulaga.glucowatch.phone.WORKOUT_IMPORTED"
    }
}

object WorkoutZones {
    fun read(context: android.content.Context): List<Int> = context.getSharedPreferences("workout-zones", android.content.Context.MODE_PRIVATE)
        .getString("boundaries", glucowatch.core.WorkoutAnalysis.defaultHeartBoundaries.joinToString(",")).orEmpty().split(',').mapNotNull(String::toIntOrNull)
        .takeIf { it.size == 4 && it == it.sorted().distinct() && it.all { b -> b in 1..250 } } ?: emptyList()
    fun save(context: android.content.Context, values: List<Int>) {
        context.getSharedPreferences("workout-zones", android.content.Context.MODE_PRIVATE).edit().putString("boundaries", values.joinToString(",")).apply()
    }
    fun pace(context: android.content.Context): List<Int> = context.getSharedPreferences("workout-zones",android.content.Context.MODE_PRIVATE)
        .getString("paceBoundaries",null)?.split(',')?.mapNotNull(String::toIntOrNull)
        ?.takeIf { it.size==5&&it==it.sorted().distinct()&&it.all { b->b>0 } }
        ?: glucowatch.core.WorkoutAnalysis.defaultPaceBoundaries
    fun savePace(context: android.content.Context,values: List<Int>) {
        context.getSharedPreferences("workout-zones",android.content.Context.MODE_PRIVATE).edit().putString("paceBoundaries",values.joinToString(",")).apply()
    }
}
