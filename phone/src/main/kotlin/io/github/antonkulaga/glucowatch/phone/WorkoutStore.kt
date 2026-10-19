package io.github.antonkulaga.glucowatch.phone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.AtomicFile
import glucowatch.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/** Persistent, local, unbacked originals. The small index is queried before any FIT is decoded. */
class WorkoutStore(private val context: Context) {
    data class Entry(val id: String, val name: String, val file: String, val start: Long, val end: Long, val zone: String, val uri: String?) {
        fun overlaps(start: Long, endExclusive: Long) = this.start < endExclusive && end > start
    }
    private val folder = context.filesDir.resolve("workouts/strava")
    private val index get() = folder.resolve("index.json")
    fun entries(): List<Entry> = synchronized(lock) {
        if (!index.isFile) return emptyList()
        val array = JSONArray(index.readText())
        (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            Entry(o.getString("id"), o.getString("name"), o.getString("file"), o.getLong("start"), o.getLong("end"), o.getString("zone"), o.optString("uri").takeIf { it.isNotBlank() })
        } }.onEach { require(it.id.matches(Regex("[0-9a-f]{64}")) && it.file == java.io.File(it.file).name) }
            .sortedByDescending { it.start }
    }
    fun load(id: String): Workout {
        val entry = entries().single { it.id == id }
        val bytes = folder.resolve(entry.file).inputStream().use(::readBytes)
        require(Workout.digest(bytes) == entry.id) { "Stored workout checksum changed." }
        return FitWorkoutReader.read(bytes, entry.name)
    }
    fun inWindow(window: ExportWindow, zone: ZoneId): List<Workout> {
        val from = window.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = window.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return entries().filter { it.overlaps(from, until) }.map { load(it.id) }
    }
    fun import(uri: Uri): Pair<Entry, Boolean> {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Workout.fit"
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBytes) ?: error("Could not open the FIT file.")
        return import(bytes, name)
    }
    fun import(bytes: ByteArray, originalName: String): Pair<Entry, Boolean> = synchronized(lock) {
        val old = entries()
        val id = Workout.digest(bytes)
        old.firstOrNull { it.id == id }?.let { return it to false }
        val workout = FitWorkoutReader.read(bytes, originalName)
        val zone = ZoneId.systemDefault()
        val filename = workout.fileName(zone)
        folder.mkdirs()
        write(folder.resolve(filename), bytes)
        // MediaStore publishes only after writing. Browser originals remain untouched.
        val uri = runCatching { publish(filename, bytes) }.getOrNull()
        val entry = Entry(workout.id, workout.name, filename, workout.startMillis, workout.endMillis, zone.id, uri?.toString())
        val array = JSONArray()
        (old + entry).forEach { e -> array.put(JSONObject().put("id", e.id).put("name", e.name).put("file", e.file)
            .put("start", e.start).put("end", e.end).put("zone", e.zone).put("uri", e.uri.orEmpty())) }
        write(index, array.toString().toByteArray())
        entry to true
    }
    private fun publish(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/GlucoPhone/Strava")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads unavailable")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not save FIT")
            require(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            return uri
        } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
    }
    private fun write(file: java.io.File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    companion object {
        private val lock = Any()
        fun readBytes(input: java.io.InputStream): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                require(output.size() + count <= FitWorkoutReader.MAX_BYTES) { "FIT exceeds 32 MB." }
                output.write(buffer, 0, count)
            }
        }
    }
}
