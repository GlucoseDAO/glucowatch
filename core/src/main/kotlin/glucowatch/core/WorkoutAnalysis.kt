package glucowatch.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

data class WorkoutPoint(val time: Long, val value: Double)

/** Source-only Minetti (2002) estimate, not Strava's proprietary GAP model. */
object WorkoutAnalysis {
    val defaultHeartBoundaries = listOf(115, 143, 157, 171)
    val defaultPaceBoundaries = listOf(405, 431, 460, 513, 596) // seconds/km, fastest first
    const val MAX_GAP = 15_000L
    const val PAPER = "https://journals.physiology.org/doi/10.1152/japplphysiol.01177.2001"

    data class Result(
        val heart: List<List<WorkoutPoint>>, val elevation: List<List<WorkoutPoint>>,
        val gap: List<List<WorkoutPoint>>, val heartZones: List<Double>, val gapZones: List<Double>,
        val heartMean: Double?, val heartMax: Double?, val gapMean: Double?,
        val heartGaps: List<Pair<Long, Long>>, val clampedGradeSeconds: Int,
        val motion: List<List<WorkoutPoint>> = emptyList(), val motionTitle: String = "Speed",
        val motionUnit: String = "km/h", val motionMean: Double? = null,
    )

    fun runningCost(grade: Double): Double {
        val g = grade.coerceIn(-.45, .45)
        return ((((155.4 * g - 30.4) * g - 43.3) * g + 46.3) * g + 19.5) * g + 3.6
    }

    fun analyse(workout: Workout, from: Long = workout.startMillis, until: Long = workout.endMillis,
                heartBoundaries: List<Int> = defaultHeartBoundaries,
                paceBoundaries: List<Int> = defaultPaceBoundaries): Result {
        require(heartBoundaries == heartBoundaries.sorted().distinct() && heartBoundaries.all { it in 1..250 })
        require(paceBoundaries.size == 5 && paceBoundaries == paceBoundaries.sorted().distinct() && paceBoundaries.first() > 0)
        val samples = workout.samples.filter { it.timeMillis in from..until }.sortedBy { it.timeMillis }
        fun field(value: (WorkoutSample) -> Double?) = samples.mapNotNull { s ->
            value(s)?.takeIf { it.isFinite() }?.let { WorkoutPoint(s.timeMillis, it) }
        }
        val heart = field { it.heartRate?.toDouble() }
        val elevation = field { it.elevationM }
        val distance = field { it.distanceM?.takeIf { d -> d >= 0 } }
        val zones = MutableList(heartBoundaries.size + 1) { 0.0 }
        var weighted = 0.0
        val gaps = mutableListOf<Pair<Long, Long>>()
        heart.zipWithNext().forEach { (a, b) ->
            val span = b.time - a.time
            if (span in 1..MAX_GAP) {
                zones[heartBoundaries.count { a.value >= it }] += span / 1000.0
                weighted += a.value * span / 1000.0
            } else if (span > MAX_GAP) gaps += a.time to b.time
        }
        // Build bounded, one-second motion segments. Missing source data and distance resets
        // break a segment; neither smoothing window can cross that break.
        data class Motion(val time: Long, val distance: Double, val elevation: Double)
        val segments = mutableListOf<MutableList<Motion>>()
        var segment = mutableListOf<Motion>()
        if (workout.usesRunningGap && distance.isNotEmpty() && elevation.isNotEmpty()) {
            val first = maxOf(from, distance.first().time, elevation.first().time)
            val last = minOf(until, distance.last().time, elevation.last().time)
            var t = ceil(first / 1000.0).toLong() * 1000
            while (t <= last) {
                val d = interpolate(distance, t); val h = interpolate(elevation, t)
                if (d == null || h == null || (segment.isNotEmpty() && d < segment.last().distance)) {
                    if (segment.isNotEmpty()) segments += segment
                    segment = mutableListOf()
                }
                if (d != null && h != null) segment += Motion(t, d, h)
                t += 1000
            }
            if (segment.isNotEmpty()) segments += segment
        }
        val gapGroups = mutableListOf<List<WorkoutPoint>>()
        val gapZones = MutableList(paceBoundaries.size + 1) { 0.0 }
        var equivalentDistance = 0.0; var known = 0.0; var clamped = 0
        segments.filter { it.size >= 2 }.forEach { run ->
            val heights = run.groupBy { it.distance }.map { (d, points) -> d to median(points.map { it.elevation }) }
            if (heights.size < 2) return@forEach
            val calculated = run.mapIndexed { i, point ->
                val da = maxOf(point.distance - 50, heights.first().first)
                val db = minOf(point.distance + 50, heights.last().first)
                val grade = if (db - da > 1) (atDistance(heights, db) - atDistance(heights, da)) / (db - da) else 0.0
                val a = run[maxOf(0, i - 15)]; val b = run[minOf(run.lastIndex, i + 15)]
                val speed = if (b.time > a.time) (b.distance - a.distance) / ((b.time - a.time) / 1000.0) else 0.0
                val adjusted = speed * runningCost(grade) / runningCost(0.0)
                if (i < run.lastIndex) {
                    if (kotlin.math.abs(grade) > .45) clamped++
                    equivalentDistance += adjusted; known++
                    val seconds = if (adjusted > 1e-9) 1000 / adjusted else Double.POSITIVE_INFINITY
                    gapZones[5 - paceBoundaries.count { seconds >= it }] += 1.0
                }
                WorkoutPoint(point.time, if (adjusted > 1e-9) min(30.0, 1000 / adjusted / 60) else 30.0)
            }
            gapGroups += bin(calculated)
        }
        val gapMean = equivalentDistance.takeIf { it > 0 }?.let { known / it * 1000 / 60 }
        val nativeSpeed = samples.mapIndexedNotNull { index, sample ->
            val previous = samples.getOrNull(index - 1)
            val derived = previous?.let { before ->
                val span = sample.timeMillis - before.timeMillis
                if (span in 1..MAX_GAP && sample.distanceM != null && before.distanceM != null && sample.distanceM >= before.distanceM)
                    (sample.distanceM - before.distanceM) * 1000 / span else null
            }
            val speed = sample.speedMps?.takeIf { it > 0 } ?: derived?.takeIf { it > 0 } ?: sample.speedMps ?: derived
            speed?.takeIf { it.isFinite() && it >= 0 }?.let { WorkoutPoint(sample.timeMillis, it) }
        }
        val swim = if (workout.isSwim) workout.lengths.filter { it.active && it.startMillis >= from && it.endMillis <= until }.mapNotNull { length ->
            val speed = length.speedMps?.takeIf { it > 0 } ?: workout.poolLengthM?.let { metres ->
                length.timerSeconds?.takeIf { it > 0 }?.let { metres / it }
            }
            speed?.let { listOf(WorkoutPoint(length.startMillis, 100 / it / 60), WorkoutPoint(length.endMillis, 100 / it / 60)) }
        } else emptyList()
        val motion = when {
            workout.usesRunningGap && gapGroups.isNotEmpty() -> gapGroups
            workout.usesRunningGap -> split(nativeSpeed.filter { it.value > 0 }.map { it.copy(value = 1000 / it.value / 60) })
            swim.isNotEmpty() -> swim
            workout.isSwim -> split(nativeSpeed.filter { it.value > 0 }.map { it.copy(value = 100 / it.value / 60) })
            else -> split(nativeSpeed.map { it.copy(value = it.value * 3.6) }).map(::bin)
        }
        val duration = if (from == workout.startMillis && until == workout.endMillis)
            workout.timerSeconds ?: workout.elapsedSeconds ?: (until - from) / 1000.0 else (until - from) / 1000.0
        val metres = if (from == workout.startMillis && until == workout.endMillis) workout.distanceM else
            distance.takeIf { it.size > 1 }?.let { it.last().value - it.first().value }
        val motionMean = when {
            workout.usesRunningGap && gapGroups.isNotEmpty() -> gapMean
            duration <= 0 || metres == null || metres <= 0 -> null
            workout.isSwim -> duration / metres * 100 / 60
            workout.usesRunningGap -> duration / metres * 1000 / 60
            else -> metres / duration * 3.6
        }
        return Result(split(heart).map(::bin), split(elevation).map(::bin), gapGroups, zones, gapZones,
            zones.sum().takeIf { it > 0 }?.let { weighted / it }, heart.maxOfOrNull { it.value },
            gapMean, gaps, clamped, motion,
            if (workout.usesRunningGap && gapGroups.isNotEmpty()) "Estimated GAP" else if (workout.usesRunningGap) "Recorded pace" else if (workout.isSwim) "Swim pace" else "Speed",
            if (workout.usesRunningGap) "min/km" else if (workout.isSwim) "min/100m" else "km/h", motionMean)
    }

    private fun interpolate(points: List<WorkoutPoint>, time: Long): Double? {
        val at = points.binarySearchBy(time) { it.time }
        if (at >= 0) return points[at].value
        val next = -at - 1
        if (next == 0 || next == points.size) return null
        val a = points[next - 1]; val b = points[next]
        if (b.time - a.time > MAX_GAP) return null
        return a.value + (b.value - a.value) * (time - a.time).toDouble() / (b.time - a.time)
    }
    private fun atDistance(points: List<Pair<Double, Double>>, distance: Double): Double {
        val at = points.binarySearchBy(distance) { it.first }
        if (at >= 0) return points[at].second
        val next = (-at - 1).coerceIn(1, points.lastIndex)
        val a = points[next - 1]; val b = points[next]
        return a.second + (b.second - a.second) * (distance - a.first) / (b.first - a.first)
    }
    private fun split(points: List<WorkoutPoint>): List<List<WorkoutPoint>> {
        val groups = mutableListOf<MutableList<WorkoutPoint>>()
        points.forEach { p ->
            if (groups.isEmpty() || p.time - groups.last().last().time > MAX_GAP) groups += mutableListOf<WorkoutPoint>()
            groups.last() += p
        }
        return groups
    }
    private fun bin(points: List<WorkoutPoint>): List<WorkoutPoint> =
        (listOf(points.first()) + points.groupBy { it.time / 60_000 }.values.map { values ->
            WorkoutPoint(median(values.map { it.time.toDouble() }).toLong(), median(values.map { it.value }))
        } + points.last()).distinct().sortedBy { it.time }
    private fun median(values: List<Double>): Double = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
}
