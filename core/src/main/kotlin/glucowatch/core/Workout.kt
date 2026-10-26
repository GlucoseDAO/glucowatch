package glucowatch.core

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Native observations stay sparse. A FIT file may have several messages at the same instant. */
data class WorkoutSample(
    val timeMillis: Long,
    val heartRate: Int? = null,
    val speedMps: Double? = null,
    val elevationM: Double? = null,
    val distanceM: Double? = null,
)

/** Native pool length, including rest lengths; no per-second distance is invented. */
data class WorkoutLength(val startMillis: Long, val endMillis: Long, val timerSeconds: Double?,
                         val speedMps: Double?, val active: Boolean)

data class Workout(
    val id: String,
    val name: String,
    val sport: String,
    val startMillis: Long,
    val endMillis: Long,
    val elapsedSeconds: Double?,
    val timerSeconds: Double?,
    val distanceM: Double?,
    val ascentM: Double?,
    val descentM: Double?,
    val samples: List<WorkoutSample>,
    val poolLengthM: Double? = null,
    val lengths: List<WorkoutLength> = emptyList(),
    val excludedSampleCount: Int = 0,
    val pumpDisconnected: Boolean = false,
    val summaryHeartRateMean: Double? = null,
    val summaryHeartRateMax: Double? = null,
) {
    val isSwim get() = sport == "Swim"
    val usesRunningGap get() = sport in setOf("Run", "Walk", "Hike")
    fun overlaps(start: Long, endExclusive: Long) = startMillis < endExclusive && endMillis > start

    fun fileName(zone: ZoneId): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmssXX").format(Instant.ofEpochMilli(startMillis).atZone(zone))
        val end = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmssXX").format(Instant.ofEpochMilli(endMillis).atZone(zone))
        val label = name.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(64).ifEmpty { "workout" }
        return "${stamp}_to_${end}-workout-${sport.lowercase()}_${label}_${id.take(12)}.fit"
    }

    /** Calculate only time supported by HR observations; never bridge a gap longer than 15 s. */
    fun zoneSeconds(boundaries: List<Int>): List<Double> {
        require(boundaries == boundaries.sorted().distinct() && boundaries.all { it in 1..250 })
        val result = MutableList(boundaries.size + 1) { 0.0 }
        val heart = samples.filter { it.heartRate != null }
        heart.zipWithNext().forEach { (a, b) ->
            val span = b.timeMillis - a.timeMillis
            if (span in 1..15_000) result[boundaries.count { a.heartRate!! >= it }] += span / 1000.0
        }
        return result
    }

    /** Exercise is a separate channel in CSV, using existing append-only event columns. */
    fun exportRows(start: Long, endExclusive: Long): List<ExportRow> = buildList {
        addAll(WorkoutAnnotations.rows(listOf(this@Workout),start,endExclusive))
        if (startMillis in start until endExclusive) add(ExportRow(startMillis, "strava_fit", "workout_start",
            durationMin = (endMillis - startMillis) / 60_000.0, markerType = sport, detail = name))
        samples.filter { it.timeMillis in start until endExclusive }.forEach { sample ->
            fun addValue(kind: String, value: Double?, unit: String) {
                if (value != null) add(ExportRow(sample.timeMillis, "strava_fit", kind, value = value, valueUnit = unit, markerType = id))
            }
            addValue("workout_heart_rate", sample.heartRate?.toDouble(), "bpm")
            addValue("workout_speed", sample.speedMps, "m/s")
            addValue("workout_elevation", sample.elevationM, "m")
            addValue("workout_distance", sample.distanceM, "m")
        }
        if (endMillis in start until endExclusive) add(ExportRow(endMillis, "strava_fit", "workout_end", markerType = id))
    }

    companion object {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** User statement, not a device suspend event. FIT bounds are an explicit timing proxy. */
object WorkoutAnnotations {
    fun rows(workouts: List<Workout>, from: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE) = workouts
        .filter { it.pumpDisconnected && it.overlaps(from,until) }.distinctBy { it.id }.flatMap { w ->
            val a=maxOf(from,w.startMillis);val b=minOf(until,w.endMillis)
            listOf(ExportRow(a,"user_reported","pump_disconnected",durationMin=(b-a)/60_000.0,markerType=w.id,
                detail="User reports pump disconnected during swimming; FIT session bounds approximate timing. Raw pump records retained."),
            ).let { startRows -> startRows+if(w.endMillis in from until until) listOf(ExportRow(w.endMillis,"user_reported","pump_reconnected",markerType=w.id,
                    detail="End of user-reported disconnection interval; timing uses FIT session end.")) else emptyList() }
        }
    fun add(events: List<ExportRow>, workouts: List<Workout>) =
        (events+rows(workouts)+workouts.map { ExportRow(it.startMillis,"strava_fit","workout_start",durationMin=(it.endMillis-it.startMillis)/60_000.0,markerType=it.id,detail=it.sport) })
            .distinctBy { it.source+it.identity() }.sortedBy { it.timeMillis }
    fun disconnected(rows: List<ExportRow>, from: Long, until: Long) = rows.filter { it.kind=="pump_disconnected"&&it.durationMin!=null }
        .any { it.timeMillis<until&&it.timeMillis+(it.durationMin!!*60_000).toLong()>from }
    fun context(events: List<ExportRow>, known: List<Workout>, selected: List<Workout>): List<ExportRow> {
        val selectedIds=selected.map {it.id}.toSet()
        val context=known.filter {it.id !in selectedIds}.map { ExportRow(it.startMillis,"activity_context","workout_start",
            durationMin=(it.endMillis-it.startMillis)/60_000.0,markerType=it.id,
            detail="${it.sport}: known archived activity used to exclude confounded response windows; detailed FIT stream not selected.") }
        return (events+context+rows(known.filter {it.id !in selectedIds})).distinctBy {it.source+it.identity()}
    }
}
