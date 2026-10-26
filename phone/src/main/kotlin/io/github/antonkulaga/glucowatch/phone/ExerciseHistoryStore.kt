package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.util.AtomicFile
import glucowatch.core.*
import glucowatch.core.link.LinkSource
import java.time.ZoneId

/** Durable account-bound provider rows, separate from the short live cache and pending exports. */
class ExerciseHistoryStore(private val context: Context) {
    private val folder = context.filesDir.resolve("exercise-history")
    private fun key(s: PhoneSettings) = DebugDefaults.fingerprint("${s.configurationKey}|${s.keyOf(LinkSource.CARELINK)}|${s.carelinkCountry}")
    fun save(rows: List<ExportRow>, settings: PhoneSettings) = synchronized(lock) {
        check(PhoneSettingsStore(context).load().configurationKey == settings.configurationKey) { "Source changed during import." }
        if (rows.isEmpty()) return@synchronized
        folder.mkdirs()
        val groups = rows.groupBy { java.time.Instant.ofEpochMilli(it.timeMillis).atZone(ZoneId.of("UTC")).toLocalDate() }
        groups.forEach { (day, values) ->
            val file = folder.resolve("${key(settings)}-$day.json")
            val previous = if (file.isFile) ExportLog.decode(file.readText()) else emptyList()
            val combined = (previous + values).distinctBy { it.source + it.identity() }.sortedBy { it.timeMillis }
            val atomic = AtomicFile(file); val stream = atomic.startWrite()
            try { stream.write(ExportLog.encode(combined).toByteArray()); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
    }
    fun load(settings: PhoneSettings, from: Long, until: Long): List<ExportRow> = synchronized(lock) {
        val prefix = key(settings) + "-"
        // Date filenames allow years of history without decoding unrelated days.
        val first = java.time.Instant.ofEpochMilli(from).atZone(ZoneId.of("UTC")).toLocalDate().toString()
        val last = java.time.Instant.ofEpochMilli(until).atZone(ZoneId.of("UTC")).toLocalDate().toString()
        folder.listFiles().orEmpty().filter { f -> f.name.startsWith(prefix) && f.name.removePrefix(prefix).removeSuffix(".json") in first..last }
            .flatMap { ExportLog.decode(it.readText()) }.filter { it.timeMillis in from until until }
            .distinctBy { it.source + it.identity() }.sortedBy { it.timeMillis }
    }
    companion object { private val lock = Any() }
}
