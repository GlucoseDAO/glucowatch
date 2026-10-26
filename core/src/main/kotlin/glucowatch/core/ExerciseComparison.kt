package glucowatch.core

import java.time.ZoneId
import kotlin.math.sqrt

object ExerciseComparison {
    data class Period(val from: Long, val until: Long, val readings: List<GlucoseReading>, val mean: Double?, val sd: Double?,
        val ranges: RangeCounts, val bolus: Double?, val basal: ReportBasalEstimate, val carbs: Double?, val pumpEvents: Int)
    fun period(glucose: List<GlucoseReading>, rows: List<ExportRow>, zone: ZoneId, from: Long, until: Long): Period {
        require(until > from)
        val readings = glucose.filter { it.timeMillis in from until until }.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
        val mean = readings.takeIf { it.isNotEmpty() }?.map { it.mgdl }?.average()
        val sd = mean?.let { avg -> if (readings.size > 1) sqrt(readings.sumOf { (it.mgdl - avg) * (it.mgdl - avg) } / (readings.size - 1)) else null }
        val selected = rows.filter { it.timeMillis in from until until }
        val pump = selected.count { it.source == ExportRow.SOURCE_CARELINK && it.kind !in setOf(ExportRow.KIND_GLUCOSE, ExportRow.KIND_CALIBRATION, ExportRow.KIND_METER) }
        return Period(from, until, readings, mean, sd, readings.fold(RangeCounts()) { a, b -> a + RangeCounts.of(b.mgdl) },
            if (pump > 0) BolusLedger.unitsBetween(BolusLedger.records(rows), from, until) else null,
            ReportBasalEstimate.build(rows, zone, from, until),
            if (pump > 0) ReportMeal.read(selected).sumOf { it.grams } else null, pump)
    }
}
