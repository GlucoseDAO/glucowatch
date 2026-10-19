package glucowatch.core

import java.time.LocalDate
import java.time.ZoneId

/** A food log, with provenance retained when the identical log appears in both exports. */
data class ReportMeal(val timeMillis: Long, val grams: Double, val sources: Set<String>) {
    val sourceLabel get() = sources.map { if (it == ExportRow.SOURCE_CARELINK) "P" else "S" }.distinct().sorted().joinToString("+")
    companion object {
        fun read(rows: List<ExportRow>): List<ReportMeal> = rows
            .filter { it.kind == ExportRow.KIND_MEAL && it.carbsG?.let { g -> g.isFinite() && g > 0 } == true }
            .groupBy { it.timeMillis to it.carbsG!! }
            .map { (key, copies) -> ReportMeal(key.first, key.second, copies.map { it.source }.toSet()) }
            .sortedBy { it.timeMillis }
    }
}

data class ReportInsulinHour(val hour: Int, val bolus: Double?, val basal: Double?, val scheduled: Double?)

/** Same day/hour selection for glucose overlays and their two insulin panels. */
data class ReportDayTimeline(val date: LocalDate, val estimate: ReportBasalEstimate,
                             val hours: List<ReportInsulinHour>, val basalMeasured: Boolean) {
    companion object {
        fun build(report: GlucoseReport, date: LocalDate, generated: Long): ReportDayTimeline {
            val zone = ZoneId.of(report.zoneId)
            val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), generated)
            val rows = report.events.filter { it.source == ExportRow.SOURCE_CARELINK && it.kind !in
                setOf(ExportRow.KIND_GLUCOSE, ExportRow.KIND_METER, ExportRow.KIND_CALIBRATION, ExportRow.KIND_DEVICE) }
                .distinctBy { it.identity() }.sortedBy { it.timeMillis }
            val estimate = ReportBasalEstimate.build(rows+report.events.filter { it.kind=="pump_disconnected" }, zone, from, maxOf(from + 1, until))
            val present = rows.any { it.timeMillis in from until until }
            val measured = rows.any { it.kind == ExportRow.KIND_BASAL_DELIVERY && it.insulinU != null }
            val first = rows.firstOrNull()?.timeMillis
            val last = rows.lastOrNull()?.timeMillis
            val hours = (0..23).map { h ->
                val a = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
                val b = date.atTime(h, 0).atZone(zone).plusHours(1).toInstant().toEpochMilli()
                val observed = present && first != null && last != null && a >= first && b <= last && b <= until
                fun doses(kind: String) = if (!observed) null else rows.filter { it.kind == kind && it.timeMillis in a until b }.sumOf { it.insulinU ?: 0.0 }
                val intervals = estimate.intervals.filter { it.start < b && it.end > a }
                val complete = b <= until && intervals.sumOf { minOf(b, it.end) - maxOf(a, it.start) } == b - a
                fun units(scheduled: Boolean) = if (!complete) null else intervals.sumOf {
                    (if (scheduled) it.scheduledRate else it.programmedRate) * (minOf(b, it.end) - maxOf(a, it.start)) / 3_600_000.0
                }
                ReportInsulinHour(h, doses(ExportRow.KIND_BOLUS), if (measured) doses(ExportRow.KIND_BASAL_DELIVERY) else units(false), if (measured) null else units(true))
            }
            return ReportDayTimeline(date, estimate, hours, measured)
        }
    }
}

object ReportExercise {
    fun onDay(workouts: List<Workout>, date: LocalDate, zone: ZoneId): List<Workout> {
        val a = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val b = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return workouts.filter { it.overlaps(a, b) }.distinctBy { it.id }.sortedBy { it.startMillis }
    }

    /** Union prevents duplicate imports/overlapping activities from darkening the grey shade. */
    fun spans(workouts: List<Workout>, from: Long, until: Long): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        workouts.map { maxOf(from, it.startMillis) to minOf(until, it.endMillis) }.filter { it.second > it.first }
            .sortedBy { it.first }.forEach { next ->
                val previous = result.lastOrNull()
                if (previous != null && previous.second >= next.first) result[result.lastIndex] = previous.first to maxOf(previous.second, next.second)
                else result += next
            }
        return result
    }
}
