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
        consumeReady(intent)
    }
    private fun button(label: String, action: () -> Unit) { column.addView(Button(this).apply {
        text = label; Brand.style(this, false); setOnClickListener { if (!busy) action() }
    }) }
    private fun text(label: String) = TextView(this).apply { text = label; textSize = 15f; setTextColor(Brand.TEXT); setPadding(4, 12, 4, 12) }.also(column::addView)
    private fun render() {
        val generation = ++renderGeneration
        column.removeAllViews()
        text("Workout imports").textSize = 24f
        text("Open your workout on Strava and choose Export Original. Sign in with Google in the browser if needed. Import saves a dated FIT copy; it does not generate a report unless you ask.")
        button("Import workout from Strava") { openStrava(false) }
        button("Import and generate event report") { openStrava(true) }
        button("Choose downloaded FIT") { chooseFit() }
        button("Heart-rate zones") {
            val input = EditText(this).apply {
                hint = "Four boundaries in bpm, e.g. 115,143,157,171"
                setText(WorkoutZones.read(this@WorkoutImportActivity).joinToString(","))
            }
            val dialog = AlertDialog.Builder(this).setTitle("Your heart-rate zones")
                .setMessage("Enter your own four increasing bpm boundaries for five zones. Leave blank to omit zone calculations.")
                .setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString().trim()
                val boundaries = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (raw.isNotEmpty() && (boundaries.size != 4 || raw.split(',').size != 4 || boundaries != boundaries.sorted().distinct() || boundaries.any { it !in 1..250 }))
                    input.error = "Enter four increasing whole bpm values, separated by commas."
                else { WorkoutZones.save(this, if (raw.isEmpty()) emptyList() else boundaries); dialog.dismiss() }
            } }
            dialog.show()
        }
        button("Pace zones") {
            val input=EditText(this).apply {
                hint="6:45,7:11,7:40,8:33,9:56"
                setText(WorkoutZones.pace(this@WorkoutImportActivity).joinToString(",") { "${it/60}:${(it%60).toString().padStart(2,'0')}" })
            }
            val dialog=AlertDialog.Builder(this).setTitle("Your pace zones")
                .setMessage("Five increasing boundaries in min:sec per km, fastest to slowest. These apply to estimated grade-adjusted pace.")
                .setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values=input.text.toString().split(',').mapNotNull { word->
                    val parts=word.trim().split(':');val minutes=parts.getOrNull(0)?.toIntOrNull();val seconds=parts.getOrNull(1)?.toIntOrNull()
                    if(parts.size==2&&minutes!=null&&minutes in 0..120&&seconds!=null&&seconds in 0..59)minutes*60+seconds else null
                }
                if(input.text.toString().split(',').size!=5||values.size!=5||values!=values.sorted().distinct()||values.firstOrNull()==0)
                    input.error="Enter five increasing min:sec values separated by commas."
                else { WorkoutZones.savePace(this,values);dialog.dismiss() }
            } }
            dialog.show()
        }
        if (Build.VERSION.SDK_INT >= 31) button("Automatic download folder") {
            AlertDialog.Builder(this).setTitle("Workout download folder")
                .setMessage("Choose Downloads/GlucoPhone in device storage, and set Chrome to save downloads there. GlucoPhone checks completed FIT files while this import is active. You can always choose a file manually.")
                .setPositiveButton("Choose folder") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), FOLDER) }
                .setNegativeButton("Cancel", null).show()
        }
        status = text("Archived copies use the workout start date and time, not the download date.")
        button("Stop waiting for workout") {
            pending.edit().clear().apply(); watch?.cancel(); ExportDownloadService.stop(this); status.text = "Import cancelled. Downloaded files are kept."
        }
        scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { store.entries() }
                if (generation != renderGeneration) return@launch
                if (entries.isNotEmpty()) text("Saved workouts")
                val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                entries.forEach { e -> button("${format.format(Instant.ofEpochMilli(e.start))} · ${e.name}") {
                    AlertDialog.Builder(this@WorkoutImportActivity).setTitle(e.name).setMessage("Saved as ${e.file}\n\nGenerate a PDF for this workout using sensor and pump data?")
                        .setPositiveButton("Event report") { _, _ -> report(e.id) }.setNegativeButton("Close", null).show()
                } }
            } catch (e: Exception) { status.text = e.message ?: "Could not read workout archive." }
        }
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
            if (!ExportBrowser.open(this@WorkoutImportActivity, "https://www.strava.com/athlete/training", BROWSER, preferChromium = true)) {
                status.text = "Install a browser to sign in to Strava, or choose an existing FIT file."; return@launch
            }
            watchFolder()
        }
    }
    private fun chooseFit() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
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
        pending.edit().clear().apply()
        ExportDownloadService.stop(this)
        if (generate) { report(entry.id); return }
        render()
        status.text = (if (fresh) "Workout imported" else "Already imported") + ": ${entry.file}." +
            if (entry.uri != null) " Copy saved in Downloads/GlucoPhone/Strava." else " Saved in the private workout archive; Downloads copy unavailable."
        AlertDialog.Builder(this).setTitle(if (fresh) "Workout imported" else "Already imported")
            .setMessage("${entry.name}\n${entry.file}")
            .setPositiveButton("Done", null).setNeutralButton("Generate event report") { _, _ -> report(entry.id) }.show()
    }
    private fun report(id: String) {
        val result = Intent().putExtra("workoutId", id)
        if (callingActivity != null) setResult(RESULT_OK, result)
        else startActivity(Intent(this, MainActivity::class.java).setAction(ACTION_REPORT).putExtra("workoutId", id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    @Deprecated("Platform result API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) return
        val uri = data?.data ?: return
        if (request == FIT) import(uri)
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
