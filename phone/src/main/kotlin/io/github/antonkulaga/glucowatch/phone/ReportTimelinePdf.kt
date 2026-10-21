package io.github.antonkulaga.glucowatch.phone

import android.graphics.*
import glucowatch.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

/** The same aligned glucose / bolus / basal panels on daily and weekly overlay pages. */
internal class ReportTimelinePdf(val report: GlucoseReport, val generated: Long, val workouts: List<Workout>,
                                 val heartZones: List<Int>, val paceZones: List<Int>,
                                 private val response: InsulinResponse.Result) {
    private val zone = ZoneId.of(report.zoneId)
    private val sensor = report.window?.content?.sensor != false
    private val pump = report.window?.content?.pump != false
    private val timelines by lazy { report.days.associate { it.date to ReportDayTimeline.build(report, it.date, generated) } }
    private val meals = ReportMeal.read(report.events)
    private val dayColors = listOf(Color.BLACK, 0xFF5E5E5E.toInt(), 0xFF2E6D28.toInt(), 0xFF8D7512.toInt(), 0xFF713B78.toInt(), 0xFFA44F27.toInt(), 0xFF497E93.toInt())
    private val left = 60f; private val right = 490f; private val width = right - left
    private fun paint(color: Int = Color.BLACK, size: Float = 8f, bold: Boolean = false, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; this.alpha = alpha; textSize = size; typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun text(c: Canvas, x: Float, y: Float, s: String, size: Float = 8f, color: Int = Color.BLACK, bold: Boolean = false) = c.drawText(s, x, y, paint(color, size, bold))
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int = ReportStyle.GRID, stroke: Float = .5f) =
        c.drawLine(x1, y1, x2, y2, paint(color).apply { strokeWidth = stroke })
    private fun round(c: Canvas, box: RectF, color: Int, alpha: Int = 255) {
        if (box.width() > 0 && box.height() > 0) { val radius = min(2f, min(box.width(), box.height()) / 2); c.drawRoundRect(box, radius, radius, paint(color, alpha = alpha)) }
    }
    private fun path(box: RectF) = Path().apply { addRoundRect(box, min(2f, box.width() / 2), min(2f, box.height() / 2), Path.Direction.CW) }
    private fun fmt(v: Double?, n: Int = 1) = v?.let { String.format(Locale.US, "%.${n}f", it) } ?: "--"
    private fun day(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    private fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun hour(t: Long) = Instant.ofEpochMilli(t).atZone(zone).let { it.hour + it.minute / 60.0 + it.second / 3600.0 }
    private fun x(h: Double) = left + width * h.toFloat() / 24f
    private fun timeX(t: Long, d: LocalDate) = if (t == start(d.plusDays(1))) right else x(hour(t))
    private fun labelTimes(c: Canvas, top: Float) { (0..24 step 3).forEach { h -> text(c, x(h.toDouble()) - 7, top, when (h) { 0, 24 -> "12am"; 12 -> "12pm"; else -> "${h % 12}" }, 6.5f) } }
    private fun heading(c: Canvas, y: Float, title: String, color: Int = Color.BLACK, note: String = "") {
        text(c, 22f, y, title, 10f, color, true)
        if (note.isNotBlank()) text(c, 100f, y, note, 7f)
    }
    private fun axes(c: Canvas, top: Float, height: Float, maximum: Double, color: Int, unit: String) {
        var step = if (color == ReportStyle.BASAL && maximum <= 4) .5 else 1.0
        while (maximum / step > 9) step *= 2
        var tick = 0.0
        while (tick <= maximum + .001) {
            val y = top + height - (tick / maximum).toFloat() * height
            line(c, left, y, right, y); text(c, right + 4, y + 2, fmt(tick, if (step < 1) 1 else 0), 6.5f, color)
            tick += step
        }
        text(c, right + 4, top - 6, unit, 6.5f, color); labelTimes(c, top + height + 14)
    }

    private fun shade(c: Canvas, dates: List<LocalDate>, top: Float, height: Float) {
        // Project and union all visible intervals before drawing, so overlaps have one grey fill.
        val spans = dates.flatMap { d -> ReportExercise.spans(workouts, start(d), start(d.plusDays(1))).map { (a, b) -> timeX(a, d) to timeX(b, d) } }.sortedBy { it.first }
        val merged = mutableListOf<Pair<Float, Float>>()
        spans.forEach { p -> val last = merged.lastOrNull(); if (last != null && p.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, p.second) else merged += p }
        dates.forEach { d -> ReportElevationPdf.draw(c,workouts,start(d),start(d.plusDays(1)),RectF(left,top,right,top+height)) }
        merged.forEach { (a, b) -> line(c, a, top, a, top + height, ReportStyle.GREY, .4f); line(c, b, top, b, top + height, ReportStyle.GREY, .4f) }
        if (dates.size > 1) dates.forEachIndexed { i, date ->
            ReportExercise.spans(workouts, start(date), start(date.plusDays(1))).forEach { (a, _) ->
                text(c, timeX(a, date).coerceAtMost(right - 45) + 3, top + 9, "${i + 1}. exercise", 6f, ReportStyle.GREY)
            }
        }
    }

    private fun glucose(c: Canvas, dates: List<LocalDate>, top: Float, height: Float, overlay: Boolean) {
        heading(c, top - 14, "Glucose", note = if (overlay) "One trace per day" else "Food circles label grams and source (P pump / S sensor)")
        fun y(v: Double) = top + height - ((v - 40) / 360).coerceIn(0.0, 1.0).toFloat() * height
        shade(c, dates, top, height)
        (50..400 step 50).forEach { v -> line(c, left, y(v.toDouble()), right, y(v.toDouble())); text(c, right + 3, y(v.toDouble()) + 2, "$v", 6f, ReportStyle.GREY) }
        listOf(70 to ReportStyle.LOW_RULE, 180 to ReportStyle.HIGH_RULE).forEach { (v, color) ->
            line(c, left, y(v.toDouble()), right, y(v.toDouble()), color, 1f); round(c, RectF(30f, y(v.toDouble()) - 5, 59f, y(v.toDouble()) + 5), color); text(c, 37f, y(v.toDouble()) + 2.6f, "$v", 7f, Color.WHITE, true)
        }
        dates.forEachIndexed { i, date ->
            val points = report.readings.filter { day(it.timeMillis) == date }.sortedBy { it.timeMillis }
            val path = Path(); var previous: Long? = null
            points.forEach { r -> val xx = x(hour(r.timeMillis)); val yy = y(r.mgdl.toDouble()); if (previous == null || r.timeMillis - previous!! > 900_000) path.moveTo(xx, yy) else path.lineTo(xx, yy); previous = r.timeMillis }
            c.drawPath(path, paint(if (overlay) dayColors[i % 7] else Color.BLACK).apply { style = Paint.Style.STROKE; strokeWidth = .8f })
            if (!overlay) ReportMealMarkers.draw(c, meals.filter { day(it.timeMillis) == date }, points, RectF(left, top, right, top + height), { x(hour(it)) }, ::y)
            if (points.isEmpty() && !overlay) text(c, left + 8, top + 20, "No sensor samples for this day.", color = ReportStyle.GREY)
        }
        labelTimes(c, top + height + 16); text(c, right + 3, top + height + 11, "mg/dL", 6f, ReportStyle.GREY)
    }

    fun overlay(c: Canvas, days: List<ReportDay>) {
        days.forEachIndexed { i, d ->
            val xx = 22f + i * 80
            text(c, xx, 111f, "${i + 1}. ${DateTimeFormatter.ofPattern("EEE d MMM").format(d.date)}", 7f, dayColors[i % 7], true)
            if (pump) listOf(ReportStyle.BOLUS to 120f, ReportStyle.BASAL to 124f).forEach { (color, y) -> c.drawLine(xx, y, xx + 23, y, dayPaint(color, i)) }
            else line(c, xx, 120f, xx + 23, 120f, dayColors[i % 7], .8f)
        }
        val dates = days.map { it.date }
        glucose(c, dates, 154f, if (pump) 173f else 420f, true)
        if (pump) {
            overlayInsulin(c, dates, 397f, 103f, false)
            overlayInsulin(c, dates, 573f, 103f, true)
        }
        text(c, 22f, 718f, "Grey = imported exercise time. Insulin ranges: hourly min-max / black median; thin traces use the day styles above.", 7f)
        text(c, 22f, 732f, "Missing hours stay blank. Basal calculated from settings is separate from measured delivered insulin.", 7f, ReportStyle.GREY)
    }

    private fun dayPaint(color: Int, index: Int) = paint(color, alpha = 150).apply {
        style = Paint.Style.STROKE; strokeWidth = .7f
        if (index > 0) pathEffect = DashPathEffect(floatArrayOf(2f + index, 1.5f + index % 2), 0f)
    }

    private fun overlayInsulin(c: Canvas, dates: List<LocalDate>, top: Float, height: Float, basal: Boolean) {
        val color = if (basal) ReportStyle.BASAL else ReportStyle.BOLUS
        val days = dates.map { timelines.getValue(it) }
        val ranges = (0..23).map { h -> ReportRange.of(days.mapNotNull { if (basal) it.hours[h].basal else it.hours[h].bolus }) }
        val scheduled = (0..23).map { h -> ReportRange.of(days.mapNotNull { it.hours[h].scheduled })?.median }
        val maximum = maxOf(1.0, ceil(maxOf(ranges.mapNotNull { it?.maximum }.maxOrNull() ?: 0.0, if (basal) scheduled.filterNotNull().maxOrNull() ?: 0.0 else 0.0) * 1.08))
        fun y(v: Double) = top + height - (v / maximum).toFloat() * height
        heading(c, top - 19, if (basal) "Basal" else "Bolus", color, if (basal) "Hourly units / pale scheduled, adjusted range and day traces" else "Delivered hourly units / range and individual day traces")
        shade(c, dates, top, height); axes(c, top, height, maximum, color, "U")
        val w = width / 24 * .62f
        ranges.forEachIndexed { h, range ->
            if (range == null) return@forEachIndexed
            val xx = x(h + .5); val base = if (basal) scheduled[h]?.let { RectF(xx - w / 2, y(it), xx + w / 2, top + height) } else null
            if (base != null) round(c, base, color, 64)
            val box = RectF(xx - w / 2, y(range.maximum), xx + w / 2, maxOf(y(range.minimum), y(range.maximum) + .4f))
            round(c, box, color, if (basal) 64 else 155)
            if (base != null) { val save = c.save(); c.clipPath(path(base)); c.clipPath(path(box)); c.drawColor(Color.argb(28, 22, 139, 211)); c.restoreToCount(save) }
            line(c, xx - w / 2, y(range.median), xx + w / 2, y(range.median), Color.BLACK, 1f)
        }
        days.forEachIndexed { i, d ->
            val p = Path(); var previous = false
            d.hours.forEach { h -> val value = if (basal) h.basal else h.bolus
                if (value == null) previous = false else { if (previous) p.lineTo(x(h.hour + .5), y(value)) else p.moveTo(x(h.hour + .5), y(value)); previous = true } }
            c.drawPath(p, dayPaint(color, i))
        }
        listOf("MAX", "MEDIAN", "MIN").forEachIndexed { i, s -> text(c, 531f, top + 8 + i * 10, s, 5.5f, bold = true) }
        round(c, RectF(517f, top + 1, 524f, top + 31), color, 145); line(c, 517f, top + 16, 524f, top + 16, Color.BLACK, 1f)
        if (basal) { round(c, RectF(517f, top + 43, 524f, top + 48), color, 64); text(c, 531f, top + 48, "SCHEDULED", 5f) }
        if (ranges.all { it == null }) text(c, left + 8, top + height / 2, "Hourly data unavailable", color = ReportStyle.GREY)
    }

    fun daily(c: Canvas, d: ReportDay) {
        val activities = ReportExercise.onDay(workouts, d.date, zone)
        val active = activities.isNotEmpty()
        val timeline = timelines.getValue(d.date)
        val food = meals.filter { day(it.timeMillis) == d.date }
        val hasPump = report.events.any { it.source == ExportRow.SOURCE_CARELINK && it.kind !in setOf(ExportRow.KIND_GLUCOSE, ExportRow.KIND_METER, ExportRow.KIND_CALIBRATION) && day(it.timeMillis) == d.date }
        val values = (if (sensor) listOf("Mean glucose" to "${fmt(d.mean, 0)} mg/dL", "In range" to "${fmt(d.inRangePercent)}%") else emptyList()) +
            (if (pump) listOf("Recorded bolus" to if (hasPump) "${fmt(d.bolusU, 2)} U" else "--") else emptyList()) + listOf("Logged food" to "${fmt(food.sumOf { it.grams }, 0)} g")
        values.forEachIndexed { i, (name, value) -> val xx = 22 + i * 568f / values.size; text(c, xx, 108f, name, 7f, bold = true); text(c, xx, 126f, value, 13f, bold = true) }
        if (sensor) glucose(c, listOf(d.date), 155f, if (!pump) 400f else if (active) 110f else 150f, false)
        if (pump) {
            val top = if (!sensor) 210f else if (active) 320f else 360f
            ReportInsulinPdf.draw(c, zone, start(d.date), start(d.date.plusDays(1)), top, if (active) 95f else 125f, report.events, timeline.estimate,workouts=workouts)
            InsulinReferencePdf.draw(c, report.events, zone, start(d.date), start(d.date.plusDays(1)), if (active) 475f else 555f, if (active) 48f else 80f,response)
        }
        if (active) exercise(c, d.date, activities, if (pump && sensor) 615f else 610f, if (pump && sensor) 65f else 80f)
        text(c, 22f, 720f, if (activities.any {it.pumpDisconnected}) "Swim pump disconnection is user-reported; FIT session bounds approximate timing. Raw pump events are retained." else if (active) "Grey shows recorded elevation within exercise intervals; activity details follow this day." else "Grey areas mark imported exercise when available. Food circles show grams; P = pump, S = sensor, P+S = identical log in both.", 6.7f)
        text(c, 22f, 733f, "${d.readings} sensor samples. Only basal drawing snaps to five minutes. Full native events remain in CSV; no event table is printed.", 6.7f, ReportStyle.GREY)
    }

    private fun exercise(c: Canvas, date: LocalDate, activities: List<Workout>, top: Float, height: Float) {
        val from = maxOf(start(date), activities.minOf { it.startMillis }); val until = minOf(start(date.plusDays(1)), activities.maxOf { it.endMillis })
        if (until <= from) return
        val analyses = activities.map { WorkoutAnalysis.analyse(it, maxOf(from, it.startMillis), minOf(until, it.endMillis), heartZones, paceZones) }
        fun xx(t: Long) = left + width * ((t - from).toDouble() / (until - from)).toFloat()
        fun yy(v: Double, lo: Double, hi: Double) = top + height - ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat() * height
        heading(c, top - 16, "Exercise", note = "HR (red, bpm) / running GAP (blue, min/km) / native sport detail on activity pages")
        val saved = c.save(); c.clipRect(left, top, right, top + height)
        analyses.forEach { a ->
            val elevations = a.elevation.flatten(); val low = elevations.minOfOrNull { it.value }; val high = elevations.maxOfOrNull { it.value }
            if (low != null && high != null && high > low) a.elevation.forEach { group -> if (group.isNotEmpty()) {
                val p = Path(); p.moveTo(xx(group.first().time), top + height); group.forEach { p.lineTo(xx(it.time), yy(it.value, low, high)) }; p.lineTo(xx(group.last().time), top + height); p.close(); c.drawPath(p, paint(Color.rgb(229,229,229)))
            } }
            fun trace(groups: List<List<WorkoutPoint>>, lo: Double, hi: Double, color: Int) { groups.forEach { group -> val p = Path(); group.forEachIndexed { i, point -> if (i == 0) p.moveTo(xx(point.time), yy(point.value, lo, hi)) else p.lineTo(xx(point.time), yy(point.value, lo, hi)) }; c.drawPath(p, paint(color).apply { style = Paint.Style.STROKE; strokeWidth = .8f }) } }
            trace(a.heart, 70.0, 200.0, ReportStyle.BOLUS); trace(a.gap, 33.0, 3.0, ReportStyle.BASAL)
        }
        c.restoreToCount(saved)
        (0..3).forEach { i -> val y = top + height * i / 3; line(c, left, y, right, y); text(c, 37f, y + 2, "${200 - i * 130 / 3}", 6f, ReportStyle.BOLUS) }
        if (analyses.any { it.gap.isNotEmpty() }) listOf(5, 10, 20, 30).forEach { v -> text(c, right + 4, yy(v.toDouble(), 33.0, 3.0) + 2, if (v == 30) "30+" else "$v:00", 6f, ReportStyle.BASAL) }
        (0..4).forEach { i -> val t = from + (until - from) * i / 4; text(c, left + width * i / 4 - 10, top + height + 14, DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(t)), 6.5f) }
    }
}

/** Meal symbols are annotations, never a made-up glucose reading or an extra event chart. */
internal object ReportMealMarkers {
    fun draw(c: Canvas, meals: List<ReportMeal>, glucose: List<GlucoseReading>, bounds: RectF,
             x: (Long) -> Float, y: (Double) -> Float) {
        val occupied = mutableListOf<RectF>()
        meals.forEach { meal ->
            val xx = x(meal.timeMillis).coerceIn(bounds.left + 3, bounds.right - 3)
            val nearest = glucose.minByOrNull { abs(it.timeMillis - meal.timeMillis) }?.takeIf { abs(it.timeMillis - meal.timeMillis) <= 900_000 }
            val yy = (nearest?.let { y(it.mgdl.toDouble()) } ?: (bounds.bottom - 8)).coerceIn(bounds.top + 10, bounds.bottom - 8)
            val label = "${String.format(Locale.US, if (meal.grams % 1 == 0.0) "%.0f" else "%.1f", meal.grams)}g ${meal.sourceLabel}"
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 6.5f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
            val w = p.measureText(label); val lx = (xx + 5).coerceIn(bounds.left, maxOf(bounds.left, bounds.right - w))
            val candidates = listOf(-10f, 13f, -22f, 25f, -34f, 37f).map { delta -> val base = (yy + delta).coerceIn(bounds.top + 8, bounds.bottom - 3); RectF(lx - 1, base - 7, lx + w + 1, base + 2) }
            val box = candidates.firstOrNull { candidate -> occupied.none { RectF.intersects(it, candidate) } } ?: candidates.minBy { candidate -> occupied.count { RectF.intersects(it, candidate) } }
            occupied += box
            c.drawRect(box, Paint().apply { color = Color.WHITE; alpha = 225 }); c.drawText(label, lx, box.bottom - 2, p)
            c.drawCircle(xx, yy, 2.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(xx, yy, 2.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = .8f })
        }
    }
}
