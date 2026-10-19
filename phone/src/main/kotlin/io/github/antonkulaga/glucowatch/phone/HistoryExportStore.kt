package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.util.AtomicFile
import glucowatch.core.*
import glucowatch.core.link.LinkSource
import org.json.JSONObject
import java.time.LocalDate

/** A browser can evict the phone process. Keep only the active export in private, unbacked storage. */
class HistoryExportStore(context: Context) {
    data class Saved(val window: ExportWindow, val pdf: Boolean, val createdAt: Long, val files: Map<ExportPortal, Pair<String, HistoricalCsv.DateOrder>>,
        val includeActivities: Boolean = false, val eventWorkoutId: String? = null)
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
              includeActivities: Boolean = false, eventWorkoutId: String? = null) = synchronized(lock) {
        clear()
        sessionId = java.util.UUID.randomUUID().toString()
        val data = JSONObject().put("id", sessionId).put("account", account(settings)).put("created", createdAt)
            .put("device", window.device.name).put("since", window.deviceSince.toString())
            .put("start", window.start.toString()).put("end", window.end.toString())
            .put("requestedStart", window.requestedStart.toString()).put("content", window.content.name)
            .put("incomplete", window.allowIncomplete).put("pdf", pdf).put("sources", JSONObject())
            .put("activities", includeActivities).put("workout", eventWorkoutId.orEmpty())
        write(metadata, data.toString().toByteArray(Charsets.UTF_8))
    }
    fun record(portal: ExportPortal, text: String, order: HistoricalCsv.DateOrder) = synchronized(lock) {
        require(text.toByteArray(Charsets.UTF_8).size <= HistoricalCsv.MAX_BYTES)
        val data = JSONObject(metadata.readText())
        require(sessionId != null && data.getString("id") == sessionId) { "The export session changed. Start again." }
        write(folder.resolve("${portal.name}.csv"), text.toByteArray(Charsets.UTF_8))
        data.getJSONObject("sources").put(portal.name, order.name)
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
        require(!window.start.isAfter(window.end) && java.time.temporal.ChronoUnit.DAYS.between(window.start,window.end) < 90)
        sessionId = data.getString("id")
        val sources = data.getJSONObject("sources")
        val files = ExportPortal.entries.filter { sources.has(it.name) }.associateWith { portal ->
            val file = folder.resolve("${portal.name}.csv")
            require(file.length() in 1..HistoricalCsv.MAX_BYTES.toLong())
            file.readText() to HistoricalCsv.DateOrder.valueOf(sources.getString(portal.name))
        }
        Saved(window, data.getBoolean("pdf"), data.getLong("created"), files,
            data.optBoolean("activities"), data.optString("workout").takeIf { it.matches(Regex("[0-9a-f]{64}")) })
    }.getOrElse { clear(); null } }
    fun learnedOrder(settings: PhoneSettings): HistoricalCsv.DateOrder? = runCatching {
        HistoricalCsv.DateOrder.valueOf(preferences.getString(account(settings), "").orEmpty())
    }.getOrNull()
    fun learn(settings: PhoneSettings, order: HistoricalCsv.DateOrder) {
        preferences.edit().putString(account(settings), order.name).apply()
    }
    fun clear() = synchronized(lock) {
        sessionId = null
        listOf("session.json", "CARELINK.csv", "CLARITY.csv").forEach { name ->
            AtomicFile(folder.resolve(name)).delete()
        }
    }
    private companion object { val lock = Any() }
}
