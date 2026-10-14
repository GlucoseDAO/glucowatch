package glucowatch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

/** Counts in the non-overlapping bins a Clarity-style report uses. 70 mg/dL belongs to in range. */
data class RangeCounts(
    val veryLow: Int = 0,
    val low: Int = 0,
    val inRange: Int = 0,
    val high: Int = 0,
    val veryHigh: Int = 0,
) {
    val n get() = veryLow + low + inRange + high + veryHigh

    fun percent(part: Int): Double = if (n == 0) 0.0 else 100.0 * part / n

    operator fun plus(other: RangeCounts) = RangeCounts(
        veryLow + other.veryLow, low + other.low, inRange + other.inRange,
        high + other.high, veryHigh + other.veryHigh,
    )

    companion object {
        fun of(mgdl: Int) = when {
            mgdl < 54 -> RangeCounts(veryLow = 1)
            mgdl < 70 -> RangeCounts(low = 1)
            mgdl <= 180 -> RangeCounts(inRange = 1)
            mgdl <= 250 -> RangeCounts(high = 1)
            else -> RangeCounts(veryHigh = 1)
        }
    }
}

/** One column of the daily or hourly table. Percents are 0 when [n] is 0. */
data class GlucoseBucket(
    val label: String,
    val n: Int,
    val min: Int?,
    val max: Int?,
    val mean: Double?,
    val sd: Double?,
    val p25: Double?,
    val p50: Double?,
    val p75: Double?,
    val ranges: RangeCounts,
) {
    val cv: Double? = if (mean != null && sd != null && mean > 0) 100.0 * sd / mean else null
    val iqr: Double? = if (p25 != null && p75 != null) p75 - p25 else null
}

/** One hour of the day, pooled across every day in the report. Insulin is a per-day mean. */
data class DayPatternHour(
    val hour: Int,
    val n: Int,
    val p5: Double?,
    val p25: Double?,
    val p50: Double?,
    val p75: Double?,
    val p95: Double?,
    val bolusU: Double,
    val basalU: Double,
)

data class ReportDay(
    val date: LocalDate,
    val label: String,
    val readings: Int,
    val mean: Double?,
    val inRangePercent: Double?,
    val bolusU: Double,
    val basalU: Double,
    val carbsG: Double,
)

data class GlucoseSummary(
    val startMillis: Long?,
    val endMillis: Long?,
    val count: Int,
    val mean: Double?,
    val sd: Double?,
    val gmi: Double?,
    val cv: Double?,
    val ranges: RangeCounts,
    val daysWithData: Int,
    val spanDays: Int,
    val sensorPercent: Double?,
    val bolusPerDay: Double,
    val basalPerDay: Double,
    val carbsPerDay: Double,
    val automaticBolusPercent: Double?,
    val tempBasalCount: Int,
    val tempBasalMinutes: Double,
    val bestDay: ReportDay?,
)

data class GlucoseReport(
    val glucoseSource: String,
    val zoneId: String,
    val summary: GlucoseSummary,
    val weekdays: List<GlucoseBucket>,
    val hours: List<GlucoseBucket>,
    val pattern: List<DayPatternHour>,
    val days: List<ReportDay>,
    val readings: List<GlucoseReading> = emptyList(),
    val events: List<ExportRow> = emptyList(),
    val window: ExportWindow? = null,
) {
    companion object {
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
        private val WEEKDAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        fun build(
            readings: List<GlucoseReading>,
            pump: List<Treatment>,
            glucoseSource: String,
            zone: ZoneId,
            now: Long = System.currentTimeMillis(),
            window: ExportWindow? = null,
            events: List<ExportRow> = emptyList(),
        ): GlucoseReport {
            val glucose = readings.filter { it.timeMillis <= now && (window == null || window.contains(it.timeMillis, zone)) }.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
            val values = glucose.map { it.mgdl.toDouble() }
            val ranges = glucose.fold(RangeCounts()) { acc, reading -> acc + RangeCounts.of(reading.mgdl) }
            val mean = values.takeIf { it.isNotEmpty() }?.average()
            val sd = mean?.let { sampleSd(values, it) }
            val dates = glucose.map { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }
            val distinctDays = dates.distinct().size
            val spanDays = if (window != null) ChronoUnit.DAYS.between(window.start, window.end).toInt() + 1
                else if (dates.isEmpty()) 0 else ChronoUnit.DAYS.between(dates.min(), dates.max()).toInt() + 1
            val divisor = spanDays.coerceAtLeast(1).toDouble()
            val selectedPump = pump.filter { window == null || window.contains(it.timeMillis, zone) }
            val bolus = selectedPump.filter { it.isBolus && it.timeMillis <= now }
            val basalPulses = selectedPump.filter { it.isBasal && it.insulin > 0 && it.durationMinutes == null && it.basalRate == null && it.timeMillis <= now }
            val meals = selectedPump.filter { it.carbs > 0 && it.timeMillis <= now }
            val temps = selectedPump.filter { it.durationMinutes != null && it.durationMinutes > 0 && it.timeMillis <= now }
            val bolusSum = bolus.sumOf { it.insulin }
            val autoSum = bolus.filter { it.automatic }.sumOf { it.insulin }
            val byDate = linkedMapOf<LocalDate, MutableList<GlucoseReading>>()
            glucose.forEach { reading ->
                val date = Instant.ofEpochMilli(reading.timeMillis).atZone(zone).toLocalDate()
                byDate.getOrPut(date) { mutableListOf() } += reading
            }
            if (window != null) {
                var day = window.start
                while (!day.isAfter(window.end)) { byDate.getOrPut(day) { mutableListOf() }; day = day.plusDays(1) }
            }
            val days = dayLines(byDate, bolus, basalPulses, meals, zone)
            val best = days.filter { it.readings >= 24 && it.inRangePercent != null }.maxByOrNull { it.inRangePercent!! }
            val summary = GlucoseSummary(
                startMillis = window?.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: glucose.firstOrNull()?.timeMillis,
                endMillis = window?.end?.atTime(23, 59)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: glucose.lastOrNull()?.timeMillis,
                count = glucose.size,
                mean = mean,
                sd = sd,
                gmi = mean?.let { 3.31 + 0.02392 * it },
                cv = if (mean != null && sd != null && mean > 0) 100.0 * sd / mean else null,
                ranges = ranges,
                daysWithData = distinctDays,
                spanDays = spanDays,
                sensorPercent = if (window == null) sensorPercent(glucose) else {
                    val a = window.start.atStartOfDay(zone).toInstant().toEpochMilli()
                    val b = minOf(now, window.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                    if (glucose.isEmpty() || b <= a) null else (100.0 * glucose.size / ((b - a) / 300_000.0)).coerceAtMost(100.0)
                },
                bolusPerDay = bolusSum / divisor,
                basalPerDay = basalPulses.sumOf { it.insulin } / divisor,
                carbsPerDay = meals.sumOf { it.carbs } / divisor,
                automaticBolusPercent = if (events.isNotEmpty() && events.any { it.kind == ExportRow.KIND_BOLUS && it.automatic == null }) null
                    else if (bolusSum > 0) 100.0 * autoSum / bolusSum else null,
                tempBasalCount = temps.size,
                tempBasalMinutes = temps.sumOf { it.durationMinutes ?: 0.0 },
                bestDay = best,
            )
            return GlucoseReport(
                glucoseSource = glucoseSource,
                zoneId = zone.id,
                summary = summary,
                weekdays = buckets(glucose, zone, weekday = true),
                hours = buckets(glucose, zone, weekday = false),
                pattern = pattern(glucose, bolus, basalPulses, zone, divisor),
                days = days,
                readings = glucose,
                events = events.filter { it.timeMillis <= now && (window == null || window.contains(it.timeMillis, zone)) },
                window = window,
            )
        }

        private fun dayLines(
            byDate: Map<LocalDate, List<GlucoseReading>>,
            bolus: List<Treatment>,
            basal: List<Treatment>,
            meals: List<Treatment>,
            zone: ZoneId,
        ): List<ReportDay> {
            val dates = (byDate.keys + bolus.map { it.date(zone) } + basal.map { it.date(zone) } + meals.map { it.date(zone) })
                .distinct().sorted()
            return dates.map { date ->
                val dayReadings = byDate[date].orEmpty()
                val inRange = dayReadings.count { it.mgdl in 70..180 }
                ReportDay(
                    date = date,
                    label = DAY.format(date),
                    readings = dayReadings.size,
                    mean = dayReadings.takeIf { it.isNotEmpty() }?.map { it.mgdl.toDouble() }?.average(),
                    inRangePercent = if (dayReadings.isEmpty()) null else 100.0 * inRange / dayReadings.size,
                    bolusU = bolus.filter { it.date(zone) == date }.sumOf { it.insulin },
                    basalU = basal.filter { it.date(zone) == date }.sumOf { it.insulin },
                    carbsG = meals.filter { it.date(zone) == date }.sumOf { it.carbs },
                )
            }
        }

        private fun buckets(readings: List<GlucoseReading>, zone: ZoneId, weekday: Boolean): List<GlucoseBucket> {
            val groups = if (weekday) {
                WEEKDAY.mapIndexed { index, label ->
                    label to readings.filter {
                        Instant.ofEpochMilli(it.timeMillis).atZone(zone).dayOfWeek.value == index + 1
                    }
                }
            } else {
                (0..23).map { hour ->
                    "%02d".format(hour) to readings.filter {
                        Instant.ofEpochMilli(it.timeMillis).atZone(zone).hour == hour
                    }
                }
            }
            return groups.map { (label, group) -> bucket(label, group.map { it.mgdl.toDouble() }) }
        }

        private fun pattern(
            readings: List<GlucoseReading>,
            bolus: List<Treatment>,
            basal: List<Treatment>,
            zone: ZoneId,
            days: Double,
        ): List<DayPatternHour> = (0..23).map { hour ->
            val values = readings.filter { Instant.ofEpochMilli(it.timeMillis).atZone(zone).hour == hour }
                .map { it.mgdl.toDouble() }.sorted()
            DayPatternHour(
                hour = hour,
                n = values.size,
                p5 = percentile(values, 0.05),
                p25 = percentile(values, 0.25),
                p50 = percentile(values, 0.50),
                p75 = percentile(values, 0.75),
                p95 = percentile(values, 0.95),
                bolusU = bolus.filter { Instant.ofEpochMilli(it.timeMillis).atZone(zone).hour == hour }.sumOf { it.insulin } / days,
                basalU = basal.filter { Instant.ofEpochMilli(it.timeMillis).atZone(zone).hour == hour }.sumOf { it.insulin } / days,
            )
        }

        private fun bucket(label: String, values: List<Double>): GlucoseBucket {
            if (values.isEmpty()) return GlucoseBucket(label, 0, null, null, null, null, null, null, null, RangeCounts())
            val sorted = values.sorted()
            val mean = values.average()
            val counts = values.fold(RangeCounts()) { acc, value -> acc + RangeCounts.of(value.toInt()) }
            return GlucoseBucket(
                label = label,
                n = values.size,
                min = sorted.first().toInt(),
                max = sorted.last().toInt(),
                mean = mean,
                sd = sampleSd(values, mean),
                p25 = percentile(sorted, 0.25),
                p50 = percentile(sorted, 0.50),
                p75 = percentile(sorted, 0.75),
                ranges = counts,
            )
        }

        private fun sensorPercent(readings: List<GlucoseReading>): Double? {
            if (readings.size < 2) return if (readings.size == 1) 100.0 else null
            val span = readings.last().timeMillis - readings.first().timeMillis
            val expected = span / (5 * 60_000L) + 1
            if (expected <= 0) return null
            return (100.0 * readings.size / expected).coerceAtMost(100.0)
        }

        /** Sample standard deviation (divisor n − 1). Null when fewer than two values. */
        internal fun sampleSd(values: List<Double>, mean: Double): Double? {
            if (values.size < 2) return null
            val sum = values.sumOf { (it - mean) * (it - mean) }
            return sqrt(sum / (values.size - 1))
        }

        /** Linear percentile on a sorted list. [p] is 0–1. */
        internal fun percentile(sorted: List<Double>, p: Double): Double? {
            if (sorted.isEmpty()) return null
            if (sorted.size == 1) return sorted.first()
            val rank = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
            val low = rank.toInt()
            val high = (low + 1).coerceAtMost(sorted.lastIndex)
            val weight = rank - low
            return sorted[low] * (1 - weight) + sorted[high] * weight
        }

        private fun Treatment.date(zone: ZoneId) = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
    }
}
