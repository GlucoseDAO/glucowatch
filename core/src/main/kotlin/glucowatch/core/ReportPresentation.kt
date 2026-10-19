package glucowatch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ReportRange(val minimum: Double, val median: Double, val maximum: Double, val count: Int) {
    companion object {
        fun of(values: List<Double>): ReportRange? = values.takeIf { it.isNotEmpty() }?.sorted()?.let {
            ReportRange(it.first(), GlucoseReport.percentile(it, .5)!!, it.last(), it.size)
        }
    }
}

data class ReportCoverage(val sensorDays: Int, val pumpDays: Int, val bothDays: Int, val requestedDays: Int)
data class ReportGlucoseSlot(val mean: Double, val p15: Double, val p75: Double)
data class ReportBasalStep(val minuteOfDay: Int, val rate: Double)
enum class ReportBasalChange { SCHEDULED, TEMPORARY, SUSPENDED, DISCONNECTED }
data class ReportBasalInterval(
    val start: Long, val end: Long, val scheduledRate: Double, val programmedRate: Double,
    val change: ReportBasalChange,
)

/** Drawing data only. Inferred settings never become Treatment doses or exported measurements. */
class ReportPresentation private constructor(
    val report: GlucoseReport,
    val zone: ZoneId,
    val coverage: ReportCoverage,
    val pumpRows: List<ExportRow>,
    val boluses: List<ExportRow>,
    val basalPulses: List<ExportRow>,
    val schedule: List<ReportBasalStep>,
    val basalTimeline: List<ReportBasalInterval>,
    val pumpStart: Long?, val pumpEnd: Long?,
    val glucoseProfile: List<ReportGlucoseSlot?>,
    val bolusProfile: List<ReportRange?>,
    val basalProfile: List<ReportRange?>,
) {
    val programmedBasal get() = basalTimeline.isNotEmpty()
    val basalAvailable get() = programmedBasal || basalPulses.isNotEmpty()
    val scheduledDailyUnits get() = schedule.mapIndexed { i, step ->
        step.rate * ((schedule.getOrNull(i + 1)?.minuteOfDay ?: 1440) - step.minuteOfDay) / 60.0
    }.sum()

    fun scheduledRate(minute: Int): Double? = schedule.lastOrNull { it.minuteOfDay <= minute }?.rate
    fun scheduledUnits(hour: Int): Double? = schedule.takeIf { it.isNotEmpty() }?.mapIndexed { i, step ->
        val end = schedule.getOrNull(i + 1)?.minuteOfDay ?: 1440
        step.rate * (minOf((hour + 1) * 60, end) - maxOf(hour * 60, step.minuteOfDay)).coerceAtLeast(0) / 60.0
    }?.sum()
    fun programmedUnits(start: Long, end: Long): Double? = basalTimeline.filter { it.start < end && it.end > start }.takeIf { it.isNotEmpty() }?.sumOf {
        it.programmedRate * (minOf(end, it.end) - maxOf(start, it.start)).coerceAtLeast(0) / 3_600_000.0
    }

    /** Only the drawing boundaries snap. Numeric profiles and source/export timestamps stay exact. */
    fun displayBasal(day: LocalDate, minutes: Int = 5): List<ReportBasalInterval> {
        require(minutes in 1..60 && 60 % minutes == 0)
        val a = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val b = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), pumpEnd ?: a)
        if (b <= a) return emptyList()
        val quantum = minutes * 60_000L
        val states = sortedMapOf<Long, ReportBasalInterval>()
        basalTimeline.filter { it.start < b && it.end > a }.forEach {
            val relative = (it.start - a).coerceAtLeast(0)
            val snapped = minOf(b, a + ((relative + quantum / 2) / quantum) * quantum)
            if (snapped < b) states[snapped] = it
        }
        if (states.isEmpty()) return emptyList()
        val cuts = (states.keys + listOf(a, b) + (0..24).map {
            day.atStartOfDay(zone).plusHours(it.toLong()).toInstant().toEpochMilli()
        }.filter { it in a..b }).distinct().sorted()
        return cuts.zipWithNext().mapNotNull { (start, end) ->
            states.entries.lastOrNull { it.key <= start }?.value?.copy(start = start, end = end)
        }
    }

    companion object {
        private val sensorKinds = setOf(ExportRow.KIND_GLUCOSE, ExportRow.KIND_METER, ExportRow.KIND_CALIBRATION)
        private fun detail(row: ExportRow, key: String): String? = runCatching {
            ((Json.parseToJsonElement(row.detail ?: "{}") as? JsonObject)?.get(key) as? JsonPrimitive)?.content
        }.getOrNull()

        fun build(report: GlucoseReport): ReportPresentation {
            val zone = ZoneId.of(report.zoneId)
            fun day(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
            fun minute(time: Long) = Instant.ofEpochMilli(time).atZone(zone).let { it.hour * 60 + it.minute }
            val pump = report.events.filter { it.source == ExportRow.SOURCE_CARELINK && it.kind !in sensorKinds &&
                it.kind != ExportRow.KIND_DEVICE && detail(it, "Event Marker") !in setOf("Start of the day", "End of the day") }
                .distinctBy { it.identity() }.sortedBy { it.timeMillis }
            val sensorDays = report.readings.map { day(it.timeMillis) }.toSet()
            val pumpDays = pump.map { day(it.timeMillis) }.toSet()
            val coverage = ReportCoverage(sensorDays.size, pumpDays.size, (sensorDays intersect pumpDays).size,
                report.days.size.coerceAtLeast(report.summary.spanDays))
            val boluses = pump.filter { it.kind == ExportRow.KIND_BOLUS && it.insulinU != null }
            val pulses = pump.filter { it.kind == ExportRow.KIND_BASAL_DELIVERY && it.insulinU != null }
            val start = pump.firstOrNull()?.timeMillis
            val end = pump.lastOrNull()?.timeMillis

            // Repeated historical profile snapshots provide evidence a sparse live upload does not.
            val historicalRates = pump.filter { it.kind == ExportRow.KIND_REPORTED_BASAL && it.basalRateUPerH != null &&
                detail(it, "Date") != null && detail(it, "Time") != null }
                .distinctBy { it.timeMillis to it.basalRateUPerH }
            val rateDays = historicalRates.map { day(it.timeMillis) }.distinct().size
            val required = maxOf(2, ceil(rateDays / 2.0).toInt())
            val inferred = historicalRates.filter { Instant.ofEpochMilli(it.timeMillis).atZone(zone).second == 0 }
                .groupBy { minute(it.timeMillis) }.mapNotNull { (clock, rows) ->
                    if (rows.map { day(it.timeMillis) }.distinct().size < required) return@mapNotNull null
                    val votes = rows.groupingBy { it.basalRateUPerH!! }.eachCount().entries.sortedByDescending { it.value }
                    if (votes.first().value < required) return@mapNotNull null
                    if (votes.size > 1 && votes[0].value == votes[1].value) return@mapNotNull null
                    ReportBasalStep(clock, votes.first().key)
                }.sortedBy { it.minuteOfDay }
            val schedule = inferred.takeIf { it.firstOrNull()?.minuteOfDay == 0 }.orEmpty()
            val timeline = mutableListOf<ReportBasalInterval>()
            if (schedule.isNotEmpty() && start != null && end != null && end > start) {
                val temps = pump.filter { it.kind == ExportRow.KIND_TEMP_BASAL && it.durationMin != null }
                // Native rows can carry Suspend alongside a rate or in a dedicated state event.
                val states = pump.mapNotNull { row -> detail(row, "Suspend")?.let { row.timeMillis to it } }.distinct()
                val cuts = mutableSetOf(start, end)
                report.days.forEach { d -> schedule.forEach { step ->
                    cuts += d.date.atStartOfDay(zone).plusMinutes(step.minuteOfDay.toLong()).toInstant().toEpochMilli()
                } }
                temps.forEach { cuts += it.timeMillis; cuts += it.timeMillis + (it.durationMin!! * 60_000).toLong() }
                states.forEach { cuts += it.first }
                cuts.filter { it in start..end }.sorted().zipWithNext().forEach { (a, b) ->
                    if (day(a) !in pumpDays) return@forEach
                    val baseline = schedule.last { it.minuteOfDay <= minute(a) }.rate
                    var value = baseline; var change = ReportBasalChange.SCHEDULED
                    temps.lastOrNull { it.timeMillis <= a }?.let { temp ->
                        if (a < temp.timeMillis + (temp.durationMin!! * 60_000).toLong()) {
                            val setting = temp.basalPercent?.let { baseline * it / 100 } ?: temp.basalRateUPerH
                            if (setting != null) { value = setting; change = ReportBasalChange.TEMPORARY }
                        }
                    }
                    states.lastOrNull { it.first <= a }?.let { state ->
                        if (state.second != "NORMAL_PUMPING") { value = 0.0; change = ReportBasalChange.SUSPENDED }
                    }
                    timeline += ReportBasalInterval(a, b, baseline, value, change)
                }
            }
            val glucoseProfile = (0..95).map { slot ->
                report.readings.filter { minute(it.timeMillis) / 15 == slot }.map { it.mgdl.toDouble() }.sorted()
                    .takeIf { it.isNotEmpty() }?.let { ReportGlucoseSlot(it.average(), GlucoseReport.percentile(it, .15)!!, GlucoseReport.percentile(it, .75)!!) }
            }
            fun hourly(kind: String, programmed: Boolean) = (0..23).map { hour ->
                val values = report.days.mapNotNull { d ->
                    val a = d.date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                    val b = d.date.atTime(hour, 0).plusHours(1).atZone(zone).toInstant().toEpochMilli()
                    if (start == null || end == null || a < start || b > end || d.date !in pumpDays || b <= a) null
                    else if (programmed) timeline.sumOf { it.programmedRate * (minOf(b, it.end) - maxOf(a, it.start)).coerceAtLeast(0) / 3_600_000.0 }
                    else pump.filter { it.kind == kind && it.timeMillis >= a && it.timeMillis < b }.sumOf { it.insulinU ?: 0.0 }
                }
                ReportRange.of(values)
            }
            return ReportPresentation(report, zone, coverage, pump, boluses, pulses, schedule, timeline, start, end, glucoseProfile,
                hourly(ExportRow.KIND_BOLUS, false), if (timeline.isNotEmpty()) hourly("", true)
                else if (pulses.isNotEmpty()) hourly(ExportRow.KIND_BASAL_DELIVERY, false) else List(24) { null })
        }
    }
}
