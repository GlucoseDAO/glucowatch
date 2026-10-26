package glucowatch.core

import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** User-requested portability import. Reads one recording at a time; never extracts ZIP paths. */
object StravaArchive {
    const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_ACTIVITIES = 20_000
    data class Recording(val workout: Workout, val bytes: ByteArray, val filename: String, val note: String? = null)
    data class Result(val imported: Int, val issues: List<String>)
    private data class Summary(val workout: Workout, val filename: String)

    fun read(file: File, accept: (Recording) -> Unit): Result = ZipFile(file).use { zip ->
        require(file.length() <= MAX_ARCHIVE_BYTES) { "Strava ZIP exceeds 2 GB." }
        require(zip.size() <= 100_000) { "Too many ZIP entries." }
        val entries = zip.entries().asSequence().toList()
        require(entries.size <= 100_000) { "Too many ZIP entries." }
        require(entries.map { it.name }.distinct().size == entries.size) { "ZIP contains duplicate paths." }
        require(entries.all { safePath(it.name) }) { "ZIP contains an unsafe path." }
        val csv = entries.singleOrNull { it.name.substringAfterLast('/') == "activities.csv" }
        val summaries = csv?.let { summary(bounded(zip.getInputStream(it), HistoricalCsv.MAX_BYTES).toString(Charsets.UTF_8)) }.orEmpty()
        val byPath = summaries.filter { it.filename.isNotBlank() }.associateBy { it.filename }
        val recordings = entries.filter { !it.isDirectory && it.name.substringBeforeLast('/').substringAfterLast('/') == "activities" && supported(it.name) }
        require(recordings.size <= MAX_ACTIVITIES && summaries.size <= MAX_ACTIVITIES) { "Import at most $MAX_ACTIVITIES activities at once." }
        require(recordings.isNotEmpty() || summaries.isNotEmpty()) { "No Strava activities found in this ZIP." }
        val seen = mutableSetOf<String>(); val issues = mutableListOf<String>(); var count = 0; var expanded = 0L
        fun deliver(recording: Recording) {
            accept(recording) // Storage errors must stop the import, rather than masquerading as bad recordings.
            count++
            recording.note?.let { issues += "${recording.filename}: $it" }
        }
        recordings.forEach { entry ->
            val meta = byPath[entry.name] ?: byPath.entries.firstOrNull { entry.name.endsWith("/" + it.key) }?.value
            var original: Pair<String, ByteArray>? = null
            val parsed = runCatching {
                val packed = bounded(zip.getInputStream(entry), FitWorkoutReader.MAX_BYTES)
                val bytes = if (entry.name.endsWith(".gz", true)) bounded(GZIPInputStream(packed.inputStream()), FitWorkoutReader.MAX_BYTES) else packed
                expanded += bytes.size
                require(expanded <= MAX_ARCHIVE_BYTES) { "Expanded activities exceed 2 GB." }
                val name = if (entry.name.endsWith(".gz", true)) entry.name.dropLast(3) else entry.name
                original = name to bytes
                val w = WorkoutRecording.read(bytes, name)
                val summaryDistance = meta?.workout?.distanceM.takeIf { !name.endsWith(".fit", true) }
                Recording(w.copy(name = meta?.workout?.name ?: w.name, sport = meta?.workout?.sport ?: w.sport,
                    distanceM = summaryDistance ?: w.distanceM ?: meta?.workout?.distanceM, ascentM = w.ascentM ?: meta?.workout?.ascentM,
                    descentM = w.descentM ?: meta?.workout?.descentM,
                    summaryHeartRateMean = meta?.workout?.summaryHeartRateMean,
                    summaryHeartRateMax = meta?.workout?.summaryHeartRateMax), bytes, name)
            }
            if (expanded > MAX_ARCHIVE_BYTES) error("Expanded activities exceed 2 GB. Earlier activities were saved; import a smaller archive.")
            if (meta != null) seen += meta.workout.id
            parsed.onSuccess(::deliver).onFailure { error ->
                if (meta != null) {
                    val note = "Recording unavailable (${error.message}); summary only."
                    deliver(original?.let { (name, bytes) -> Recording(meta.workout.copy(id = Workout.digest(bytes)), bytes, name, note) } ?: fallback(meta, note))
                }
                else issues += "${entry.name}: ${error.message ?: "Could not read recording"}"
            }
        }
        summaries.filter { it.workout.id !in seen }.forEach { deliver(fallback(it, "Summary only; no supported recording in archive.")) }
        Result(count, issues)
    }

    fun supported(name: String) = name.lowercase().removeSuffix(".gz").let { it.endsWith(".fit") || it.endsWith(".gpx") || it.endsWith(".tcx") }
    private fun safePath(name: String) = !name.startsWith('/') && '\\' !in name && ':' !in name && '\u0000' !in name && name.split('/').none { it == ".." }
    fun bounded(input: InputStream, limit: Int): ByteArray = input.use { stream ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer); if (n < 0) break
            require(output.size().toLong() + n <= limit) { "Activity file exceeds ${limit / 1024 / 1024} MB." }
            output.write(buffer, 0, n)
        }
        output.toByteArray()
    }
    private fun fallback(s: Summary, note: String): Recording {
        val bytes = "strava-summary:${s.workout.id}".toByteArray()
        return Recording(s.workout.copy(id = Workout.digest(bytes)), bytes, "${s.workout.id}.strava", note)
    }
    private fun summary(text: String): List<Summary> {
        val records = HistoricalCsv.records(text.removePrefix("\uFEFF")); val header = records.firstOrNull() ?: return emptyList()
        require(listOf("Activity ID", "Activity Date", "Activity Name", "Activity Type").all { it in header }) { "Unrecognized Strava activities.csv." }
        return records.drop(1).filter { row -> row.any { it.isNotBlank() } }.mapIndexed { index, row ->
            fun field(key: String) = row.getOrNull(header.indexOfLast { it == key }).orEmpty().trim()
            fun number(key: String) = field(key).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val date = field("Activity Date")
            val start = runCatching { Instant.parse(date) }.getOrElse {
                LocalDateTime.parse(date, DateTimeFormatter.ofPattern("MMM d, uuuu, h:mm:ss a", Locale.ENGLISH)).toInstant(ZoneOffset.UTC)
            }.toEpochMilli()
            val elapsed = number("Elapsed Time") ?: error("Activity ${index + 1} has no elapsed time.")
            require(elapsed >= 0 && elapsed <= 7 * 86400) { "Activity ${index + 1} has an invalid duration." }
            // Strava repeats column names: the LAST Distance is metres; the first is display km.
            val distance = if (header.count { it == "Distance" } > 1) number("Distance") else number("Distance")?.times(1000)
            Summary(Workout(Workout.digest("strava:${field("Activity ID")}".toByteArray()), field("Activity Name"),
                sport(field("Activity Type")), start, start + (elapsed * 1000).toLong(), elapsed, number("Moving Time"),
                distance, number("Elevation Gain"), number("Elevation Loss"), emptyList(),
                summaryHeartRateMean = number("Average Heart Rate")?.takeIf { it in 1.0..250.0 },
                summaryHeartRateMax = number("Max Heart Rate")?.takeIf { it in 1.0..250.0 }), field("Filename"))
        }
    }
    internal fun sport(value: String) = when (value.lowercase()) {
        "running", "run", "trailrun", "virtualrun" -> "Run"
        "cycling", "biking", "ride", "virtualride", "mountainbikeride", "gravelride", "ebikeride" -> "Ride"
        "swimming", "swim" -> "Swim"
        "walking", "walk" -> "Walk"
        "hiking", "hike" -> "Hike"
        else -> value.ifBlank { "Exercise" }
    }
}
