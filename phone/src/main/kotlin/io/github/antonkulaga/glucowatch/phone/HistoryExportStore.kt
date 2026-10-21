package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.util.AtomicFile
import glucowatch.core.*
import glucowatch.core.link.LinkSource
import org.json.JSONObject
import java.time.LocalDate

/** A browser can evict the phone process. Keep only the active export in private, unbacked storage. */
class HistoryExportStore(context: Context) {
    data class NativeFile(val text: String, val order: HistoricalCsv.DateOrder, val index: Int)
    data class Saved(val window: ExportWindow, val pdf: Boolean, val createdAt: Long, val files: Map<ExportPortal, List<NativeFile>>,
        val includeActivities: Boolean = false, val eventWorkoutIds: List<String> = emptyList(), val csv: Boolean = true)
    private val folder = context.filesDir.resolve("pending-history-export")
    private var sessionId: String? = null
    private val metadata get() = folder.resolve("session.json")
    private val preferences = context.getSharedPreferences("export-date-format", Context.MODE_PRIVATE)
    private fun account(settings: PhoneSettings) = DebugDefaults.fingerprint(
        "${settings.configurationKey}|${settings.region}|${settings.username}|${settings.keyOf(LinkSource.CARELINK)}|${settings.carelinkCountry}")
    private fun write(file: java.io.File, bytes: ByteArray) {
        folder.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun begin(window: ExportWindow, pdf: Boolean, settings: PhoneSettings, createdAt: Long,
              includeActivities: Boolean = false, eventWorkoutIds: List<String> = emptyList(), csv: Boolean = true) = synchronized(lock) {
        clear()
        sessionId = java.util.UUID.randomUUID().toString()
        val data = JSONObject().put("id", sessionId).put("account", account(settings)).put("created", createdAt)
            .put("device", window.device.name).put("since", window.deviceSince.toString())
            .put("start", window.start.toString()).put("end", window.end.toString())
            .put("requestedStart", window.requestedStart.toString()).put("content", window.content.name)
            .put("incomplete", window.allowIncomplete).put("pdf", pdf).put("csv", csv).put("sources", JSONObject())
            .put("activities", includeActivities).put("workouts", org.json.JSONArray(eventWorkoutIds))
        write(metadata, data.toString().toByteArray(Charsets.UTF_8))
    }
    fun record(portal: ExportPortal, text: String, order: HistoricalCsv.DateOrder, index: Int = 0) = synchronized(lock) {
        require(index in 0..2)
        require(text.toByteArray(Charsets.UTF_8).size <= HistoricalCsv.MAX_BYTES)
        val data = JSONObject(metadata.readText())
        require(sessionId != null && data.getString("id") == sessionId) { "The export session changed. Start again." }
        val name = "${portal.name}-$index.csv"
        val sources = data.getJSONObject("sources")
        val entries = sources.optJSONArray(portal.name) ?: org.json.JSONArray().apply {
            if (sources.has(portal.name)) put(JSONObject().put("file", "${portal.name}.csv")
                .put("order", sources.getString(portal.name)).put("index", 0))
        }
        require((0 until entries.length()).none { entries.getJSONObject(it).getInt("index") == index }) { "This download interval is already saved." }
        write(folder.resolve(name), text.toByteArray(Charsets.UTF_8))
        entries.put(JSONObject().put("file", name).put("order", order.name).put("index", index))
        sources.put(portal.name, entries)
        write(metadata, data.toString().toByteArray(Charsets.UTF_8))
    }
    fun load(settings: PhoneSettings): Saved? = synchronized(lock) { runCatching {
        if (!metadata.isFile) return null
        val data = JSONObject(metadata.readText())
        require(data.getString("account") == account(settings))
        require(System.currentTimeMillis() - data.getLong("created") in 0..86_400_000)
        val window = ExportWindow(LatestDevice.valueOf(data.getString("device")), LocalDate.parse(data.getString("since")),
            LocalDate.parse(data.getString("start")), LocalDate.parse(data.getString("end")),
            LocalDate.parse(data.getString("requestedStart")), ExportContent.valueOf(data.getString("content")), data.getBoolean("incomplete"))
        require(!window.start.isAfter(window.end) && java.time.temporal.ChronoUnit.DAYS.between(window.start,window.end) < ExportWindow.MAX_DAYS)
        sessionId = data.getString("id")
        val sources = data.getJSONObject("sources")
        val files = ExportPortal.entries.filter { sources.has(it.name) }.associateWith { portal ->
            val entries = sources.optJSONArray(portal.name) ?: org.json.JSONArray().put(JSONObject()
                .put("file", "${portal.name}.csv").put("order", sources.getString(portal.name)).put("index", 0))
            require(entries.length() in 1..3)
            (0 until entries.length()).map { i ->
                val entry = entries.getJSONObject(i); val index = entry.getInt("index")
                require(index in 0..2)
                val name = entry.getString("file")
                require(name == "${portal.name}-$index.csv" || name == "${portal.name}.csv")
                val file = folder.resolve(name)
                require(file.length() in 1..HistoricalCsv.MAX_BYTES.toLong())
                NativeFile(file.readText(), HistoricalCsv.DateOrder.valueOf(entry.getString("order")), index)
            }.also { require(it.map { f -> f.index }.distinct().size == it.size) }
        }
        Saved(window, data.getBoolean("pdf"), data.getLong("created"), files,
            data.optBoolean("activities"), (data.optJSONArray("workouts")?.let { array -> (0 until array.length()).map { array.getString(it) } }
                ?: listOfNotNull(data.optString("workout").takeIf { it.isNotEmpty() })).also { ids ->
                    require(ids.size <= 50 && ids.distinct() == ids && ids.all { it.matches(Regex("[0-9a-f]{64}")) })
                }, data.optBoolean("csv", !data.getBoolean("pdf")))
    }.getOrElse { clear(); null } }
    fun learnedOrder(settings: PhoneSettings): HistoricalCsv.DateOrder? = runCatching {
        HistoricalCsv.DateOrder.valueOf(preferences.getString(account(settings), "").orEmpty())
    }.getOrNull()
    fun learn(settings: PhoneSettings, order: HistoricalCsv.DateOrder) {
        preferences.edit().putString(account(settings), order.name).apply()
    }
    fun clear() = synchronized(lock) {
        sessionId = null
        (listOf("session.json", "CARELINK.csv", "CLARITY.csv") + ExportPortal.entries.flatMap { p -> (0..2).map { "${p.name}-$it.csv" } }).forEach { name ->
            AtomicFile(folder.resolve(name)).delete()
        }
    }
    private companion object { val lock = Any() }
}
