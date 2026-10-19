package glucowatch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Dates describe data, never the day the download happened. Original archives keep their names. */
object ExportNames {
    fun lastCompletedDay(today: LocalDate) = today.minusDays(1)
    fun contents(content: ExportContent, workouts: Int = 0) = buildList {
        if (content.sensor) add("cgm")
        if (content.pump) add("pump")
        if (workouts > 0) add("workouts")
    }.joinToString("-")

    fun report(start: LocalDate, end: LocalDate, content: ExportContent, workouts: Int = 0) =
        "glucophone-${start}_to_${end}-${contents(content, workouts)}"

    fun interval(rows: List<ExportRow>, zone: ZoneId): String? = rows.takeIf { it.isNotEmpty() }?.let {
        val first = Instant.ofEpochMilli(it.minOf { row -> row.timeMillis }).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(it.maxOf { row -> row.timeMillis }).atZone(zone).toLocalDate()
        "${first}_to_${last}"
    }
}
