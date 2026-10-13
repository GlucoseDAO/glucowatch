package io.github.antonkulaga.glucowatch.phone

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import glucowatch.core.DayPatternHour
import glucowatch.core.GlucoseBucket
import glucowatch.core.GlucosePalette
import glucowatch.core.GlucoseReport
import glucowatch.core.RangeCounts
import glucowatch.core.ReportDay
import glucowatch.core.ExportRow
import java.time.LocalDate
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A printable report in the shape of a Dexcom Clarity overview, with pump delivery added. */
object ReportPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private val INK = GlucosePalette.LIGHT_TEXT
    private val MUTED = GlucosePalette.LIGHT_GREY
    private val VERY_LOW = 0xFF8E1B22.toInt()
    private val LOW = GlucosePalette.LIGHT_RED
    private val IN_RANGE = GlucosePalette.LIGHT_GREEN
    private val HIGH = GlucosePalette.LIGHT_YELLOW
    private val VERY_HIGH = GlucosePalette.LIGHT_ORANGE
    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

    fun render(report: GlucoseReport, generatedMillis: Long = System.currentTimeMillis()): ByteArray {
        val zone = runCatching { ZoneId.of(report.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val generated = WHEN.format(Instant.ofEpochMilli(generatedMillis).atZone(zone))
        val pages = mutableListOf<(Canvas) -> Unit>()
        val sensor = report.window?.content?.sensor ?: true
        val pumpData = report.window?.content?.pump ?: true
        if (report.summary.count == 0 && report.events.isEmpty()) {
            pages += { empty(it, report.glucoseSource) }
        } else {
            pages += { overview(it, report) }
            pages += { devices(it, report) }
            if (sensor && report.summary.count > 0) {
                pages += { patterns(it, report) }
                pages += { pattern(it, report) }
                report.days.chunked(7).forEach { week -> pages += { overlay(it, report, week) } }
                pages += { compare(it, report) }
                pages += { tablePage(it, "Daily statistics", "Each column pools matching weekdays in the requested period.", report.weekdays, weekday = true) }
                pages += { hours(it, report.hours) }
            }
            if (pumpData) report.days.chunked(28).forEach { days -> pages += { pump(it, report, days) } }
            report.days.forEach { day -> pages += { daily(it, report, day) } }
            report.events.groupBy { it.kind }.entries.chunked(25).forEach { kinds -> pages += { categories(it, kinds) } }
        }
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, draw ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create())
                try {
                    page.canvas.drawColor(GlucosePalette.WHITE)
                    draw(page.canvas)
                    footer(page.canvas, index + 1, pages.size, generated)
                } finally { document.finishPage(page) }
            }
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally { document.close() }
    }

    private fun empty(canvas: Canvas, source: String) {
        val paint = text(18f, bold = true)
        canvas.drawText("Glucose and pump", MARGIN, 72f, paint)
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = MUTED
        canvas.drawText("Nothing is cached from $source yet.", MARGIN, 100f, paint)
        canvas.drawText("Open GlucoPhone after the sensor and the pump have uploaded, then export again.", MARGIN, 118f, paint)
    }

    private fun overview(canvas: Canvas, report: GlucoseReport) {
        val s = report.summary
        var y = heading(canvas, "Overview", period(report))
        y = line(canvas, y, if (report.window?.content?.sensor == false) "Pump records. Times are ${report.zoneId}."
            else "Glucose from ${report.glucoseSource}. Times are ${report.zoneId}.")
        y += 8f
        if (report.window?.content?.sensor != false) {
            val gap = 8f
            val width = (PAGE_W - 2 * MARGIN - 2 * gap) / 3f
            metric(canvas, MARGIN, y, width, s.mean?.let(::mgdl) ?: "--", "mg/dL", "Average glucose")
            metric(canvas, MARGIN + width + gap, y, width, s.sd?.let(::mgdl) ?: "--", "mg/dL", "Standard deviation")
            metric(canvas, MARGIN + 2 * (width + gap), y, width, s.gmi?.let { one(it) + "%" } ?: "--", "", "GMI")
            y += 78f
            y = line(canvas, y, "Coefficient of variation  " + (s.cv?.let { one(it) + "%" } ?: "--"))
            y = line(canvas, y, "Time sensor active  " + (s.sensorPercent?.let { integer(it) + "%" } ?: "--") +
                "    Days with glucose  ${s.daysWithData}/${s.spanDays.coerceAtLeast(s.daysWithData)}")
            y += 8f
            y = section(canvas, y, "Time in range")
            y = line(canvas, y, "Target 70-180 mg/dL. Very low is under 54. Very high is over 250. 70 counts as in range.", 9f)
            y += 4f
            bar(canvas, MARGIN, y, PAGE_W - 2 * MARGIN, 18f, s.ranges)
            y += 26f
            y = legend(canvas, y, s.ranges)
        }
        y += 10f
        if (report.window?.content?.pump != false) {
            y = section(canvas, y, "Insulin and carbs")
            y = line(canvas, y, "Bolus  ${one(s.bolusPerDay)} U/day     Basal delivered  ${one(s.basalPerDay)} U/day     Carbs  ${integer(s.carbsPerDay)} g/day")
            val auto = s.automaticBolusPercent?.let { integer(it) + "% of bolus units were automatic corrections" }
                ?: "Automatic-correction share unavailable"
            y = line(canvas, y, "Total recorded delivery ${one(s.bolusPerDay + s.basalPerDay)} U/day; ${one(report.events.count { it.kind == ExportRow.KIND_BOLUS }.toDouble() / s.spanDays.coerceAtLeast(1))} bolus events/day")
            y = line(canvas, y, auto)
            y = line(canvas, y, "Temporary basal settings  ${s.tempBasalCount}    ${one(s.tempBasalMinutes / 60.0)} h set")
        }
        s.bestDay?.let { day ->
            y += 6f
            y = line(canvas, y, "Highest time in range  ${day.label}  ${integer(day.inRangePercent ?: 0.0)}%  (${day.readings} readings)", 11f, bold = true)
        }
        if (report.window?.content?.sensor != false) {
            val calibrations = report.events.count { it.kind == ExportRow.KIND_CALIBRATION }
            y = line(canvas, y, "Recorded calibrations ${one(calibrations.toDouble() / s.spanDays.coerceAtLeast(1))}/day")
        }
        y += 12f
        if (report.window?.content?.pump != false) note(canvas, y, "Basal delivered is the sum of pump pulses (AUTO_BASAL_DELIVERY), divided by the requested calendar days. A reported basal rate and a temporary basal are settings, not doses, and are not included in those units. GMI is 3.31 + 0.02392 x average mg/dL. This is not a medical device.")
        if (report.window?.content?.sensor != false && s.count > 0) {
            val chartTop = maxOf(y + 90f, 530f)
            if (chartTop + 180f < PAGE_H - 50f) {
                section(canvas, chartTop - 30f, "Average day")
                val left = MARGIN + 28f; val right = PAGE_W - MARGIN
                drawBands(canvas, left, chartTop, right, chartTop + 160f)
                band(canvas, report.pattern, left, right, chartTop, chartTop + 160f, { it.p25 }, { it.p75 }, GlucosePalette.withAlpha(MUTED, 65))
                lineThrough(canvas, report.pattern, left, right, chartTop, chartTop + 160f, { it.p50 }, INK, 1.5f)
                axisLabels(canvas, left, right, chartTop, chartTop + 160f)
                line(canvas, chartTop + 186f, "Median and middle 50% of recorded samples, pooled by hour.", 8f)
            }
        }
    }

    private fun pattern(canvas: Canvas, report: GlucoseReport) {
        var y = heading(canvas, "Glucose by time of day", "Median and percentiles pooled by hour. Recorded insulin is shown only when pump data is selected.")
        val top = y + 8f
        val plotBottom = top + 250f
        val left = MARGIN + 28f
        val right = PAGE_W - MARGIN
        drawBands(canvas, left, top, right, plotBottom)
        val slots = report.pattern
        band(canvas, slots, left, right, top, plotBottom, { it.p5 }, { it.p95 }, GlucosePalette.withAlpha(MUTED, 50))
        band(canvas, slots, left, right, top, plotBottom, { it.p25 }, { it.p75 }, GlucosePalette.withAlpha(GlucosePalette.LIGHT_GREEN, 90))
        lineThrough(canvas, slots, left, right, top, plotBottom, { it.p50 }, INK, 2.2f)
        axisLabels(canvas, left, right, top, plotBottom)
        y = plotBottom + 16f
        y = line(canvas, y, "Shaded band is the 25th-75th percentile. Grey wash is the 5th-95th. The line is the median.", 9f)
        y += 8f
        if (report.window?.content?.pump == false) return
        y = section(canvas, y, "Delivered insulin by hour")
        y = bars(canvas, y, slots.map { it.bolusU }, INK, "Bolus U")
        y = bars(canvas, y, slots.map { it.basalU }, IN_RANGE, "Basal U")
        note(canvas, y + 8f, "Each bar is the total units in that hour of the day, divided by the number of requested calendar days. A day with no pulse contributes zero. Rates are not drawn here.")
    }

    private fun devices(canvas: Canvas, report: GlucoseReport) {
        var y = heading(canvas, "Devices and data availability", period(report))
        if (report.window?.content?.sensor != false) y = line(canvas, y, "Glucose source: ${report.glucoseSource}")
        y = line(canvas, y, "Time zone: ${report.zoneId}")
        report.window?.let {
            y = line(canvas, y, "Export: ${it.content.label}.")
            if (it.content == glucowatch.core.ExportContent.SENSOR_AND_PUMP && !it.allowIncomplete)
                y = line(canvas, y, "Latest device: ${it.device.label}; started ${it.deviceSince}.")
            y = line(canvas, y, if (it.allowIncomplete) "Earlier incomplete data allowed." else "Combined date boundary enforced when both devices are selected.")
        }
        report.events.groupBy { it.source }.forEach { (source, rows) ->
            val dates = rows.map { Instant.ofEpochMilli(it.timeMillis).atZone(ZoneId.of(report.zoneId)).toLocalDate() }.distinct()
            y = line(canvas, y, "$source: ${rows.size} events on ${dates.size} days.")
        }
        y = section(canvas, y + 12f, "Device snapshots and settings")
        val snapshots = report.events.filter { it.kind == ExportRow.KIND_DEVICE }
        if (snapshots.isEmpty()) y = line(canvas, y, "Device identifiers and alert settings were not supplied in these data exports.")
        else snapshots.take(3).forEach { row ->
            y = line(canvas, y, row.detail.orEmpty().take(250), 8f)
        }
        note(canvas, y + 10f, "Missing days and samples are not interpolated. Portal CSV exports may omit automated basal delivery, alerts or device settings. The report includes only measured or explicitly logged events; an absent field is not zero.")
    }

    private fun patterns(canvas: Canvas, report: GlucoseReport) {
        var y = heading(canvas, "Patterns", "Descriptive summaries of the selected data. Missing samples are not filled.")
        val best = report.summary.bestDay
        if (best != null) {
            y = section(canvas, y, "Best day")
            y = line(canvas, y, "${best.label}: ${integer(best.inRangePercent ?: 0.0)}% of recorded readings in range; ${best.readings} samples.")
            drawSeries(canvas, report.readings.filter { dateOf(it.timeMillis, report) == best.date }, report, y + 10f, y + 190f)
            y += 220f
        }
        y = section(canvas, y, "Time-of-day patterns")
        val usable = report.hours.filter { it.n > 0 }
        usable.maxByOrNull { it.ranges.percent(it.ranges.low + it.ranges.veryLow) }?.let {
            y = line(canvas, y, "Most below-range samples: ${it.label}:00, ${one(it.ranges.percent(it.ranges.low + it.ranges.veryLow))}% of ${it.n} readings.")
        }
        usable.maxByOrNull { it.ranges.percent(it.ranges.high + it.ranges.veryHigh) }?.let {
            y = line(canvas, y, "Most above-range samples: ${it.label}:00, ${one(it.ranges.percent(it.ranges.high + it.ranges.veryHigh))}% of ${it.n} readings.")
        }
        note(canvas, y + 12f, "Best day requires at least 24 samples and compares the share of recorded readings, not full-day coverage. These summaries do not diagnose patterns or make treatment recommendations.")
    }

    private fun overlay(canvas: Canvas, report: GlucoseReport, days: List<ReportDay>) {
        val top = heading(canvas, "Weekly overlay", "${days.first().date} to ${days.last().date}. Each trace is a recorded day; gaps over 15 minutes break the line.") + 12f
        drawBands(canvas, MARGIN + 28f, top, PAGE_W - MARGIN, top + 270f)
        axisLabels(canvas, MARGIN + 28f, PAGE_W - MARGIN, top, top + 270f)
        days.forEachIndexed { i, day ->
            val shade = 45 + i * 25
            val color = android.graphics.Color.rgb(shade, shade, shade)
            drawTrace(canvas, report.readings.filter { dateOf(it.timeMillis, report) == day.date }, report, top, top + 270f, color)
            canvas.drawText(day.date.toString(), MARGIN + (i % 4) * 130f, top + 310f + (i / 4) * 20f, text(10f).apply { this.color = color })
        }
    }

    private fun compare(canvas: Canvas, report: GlucoseReport) {
        var y = heading(canvas, "Compare", "First and second halves of the requested period. This comparison does not fetch or invent a previous period.")
        if (report.days.size < 2) { line(canvas, y, "Choose at least two days to compare."); return }
        val halves = report.days.chunked((report.days.size + 1) / 2)
        halves.forEachIndexed { i, days ->
            val points = report.readings.filter { dateOf(it.timeMillis, report) in days.map { it.date }.toSet() }
            y = section(canvas, y, "${if (i == 0) "First" else "Second"} half: ${days.first().date} to ${days.last().date}")
            y = line(canvas, y, "${points.size} readings; mean ${if (points.isEmpty()) "unavailable" else mgdl(points.map { it.mgdl.toDouble() }.average()) + " mg/dL"}.")
            val ranges = points.fold(RangeCounts()) { a, p -> a + RangeCounts.of(p.mgdl) }
            bar(canvas, MARGIN, y, PAGE_W - 2 * MARGIN, 18f, ranges)
            y = legend(canvas, y + 30f, ranges) + 20f
        }
    }

    private fun daily(canvas: Canvas, report: GlucoseReport, day: ReportDay) {
        var y = heading(canvas, "Daily", day.label)
        if (report.window?.content?.sensor != false) {
            val points = report.readings.filter { dateOf(it.timeMillis, report) == day.date }
            if (points.isEmpty()) y = line(canvas, y, "No sensor samples for this day.")
            else {
                drawSeries(canvas, points, report, y + 5f, y + 195f)
                y += 225f
                y = line(canvas, y, "${day.readings} readings; mean ${day.mean?.let(::mgdl)} mg/dL; ${day.inRangePercent?.let(::integer)}% of samples in range.")
            }
        }
        if (report.window?.content?.pump != false)
            y = line(canvas, y, "Recorded delivery: bolus ${one(day.bolusU)} U; basal pulses ${one(day.basalU)} U; carbs ${integer(day.carbsG)} g.")
        val rows = report.events.filter { dateOf(it.timeMillis, report) == day.date && it.kind != ExportRow.KIND_GLUCOSE }
        y = section(canvas, y, "Events")
        if (rows.isEmpty()) { line(canvas, y, "No event data supplied for this day."); return }
        rows.groupingBy { it.kind }.eachCount().forEach { (kind, count) -> y = line(canvas, y, "${kind.replace('_', ' ')}: $count", 9f) }
        y += 8f
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(report.zoneId))
        val significant = rows.filter { it.kind !in setOf(ExportRow.KIND_BASAL_DELIVERY, ExportRow.KIND_DEVICE, ExportRow.KIND_IOB) }
        significant.take(15).forEach { row ->
            val description = listOfNotNull(row.insulinU?.let { "${one(it)} U" }, row.carbsG?.let { "${integer(it)} g" },
                row.basalRateUPerH?.let { "${one(it)} U/h" }, row.basalPercent?.let { "${one(it)}%" }, row.durationMin?.let { "${one(it)} min" },
                row.value?.let { "${one(it)} ${row.valueUnit.orEmpty()}" }).joinToString(", ")
            if (y < 740f) y = line(canvas, y, "${clock.format(Instant.ofEpochMilli(row.timeMillis))}  ${row.kind.replace('_', ' ')}  $description", 9f)
        }
        note(canvas, 772f, "Event counts include every row. Up to 15 event summaries shown; CSV preserves event fields. Unreported doses and settings stay unknown.")
    }

    private fun categories(canvas: Canvas, kinds: List<Map.Entry<String, List<ExportRow>>>) {
        var y = heading(canvas, "Event categories", "Every supplied category, including alarms, calibrations, settings and unfamiliar events. Raw details remain in CSV.")
        kinds.forEach { (kind, rows) ->
            y = line(canvas, y, "${kind.replace('_', ' ')}: ${rows.size} rows", 11f, true)
        }
    }

    private fun dateOf(time: Long, report: GlucoseReport) = Instant.ofEpochMilli(time).atZone(ZoneId.of(report.zoneId)).toLocalDate()

    private fun drawSeries(canvas: Canvas, points: List<glucowatch.core.GlucoseReading>, report: GlucoseReport, top: Float, bottom: Float) {
        drawBands(canvas, MARGIN + 28f, top, PAGE_W - MARGIN, bottom)
        axisLabels(canvas, MARGIN + 28f, PAGE_W - MARGIN, top, bottom)
        drawTrace(canvas, points, report, top, bottom, INK)
    }

    private fun drawTrace(canvas: Canvas, points: List<glucowatch.core.GlucoseReading>, report: GlucoseReport, top: Float, bottom: Float, color: Int) {
        val path = Path()
        var previous: Long? = null
        val left = MARGIN + 28f
        val right = PAGE_W - MARGIN
        points.sortedBy { it.timeMillis }.forEach { p ->
            val local = Instant.ofEpochMilli(p.timeMillis).atZone(ZoneId.of(report.zoneId))
            val x = left + (right - left) * local.toLocalTime().toSecondOfDay() / 86400f
            val y = yOf(p.mgdl.toDouble(), top, bottom)
            if (previous == null || p.timeMillis - previous!! > 15 * 60_000L) path.moveTo(x, y) else path.lineTo(x, y)
            previous = p.timeMillis
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.2f })
    }

    private fun hours(canvas: Canvas, buckets: List<GlucoseBucket>) {
        var y = heading(canvas, "By hour", "Pooled across every day. 00 is midnight to 1am in the phone's time zone.")
        y = statTable(canvas, y, buckets.take(12), hourHeader = true)
        y += 16f
        statTable(canvas, y, buckets.drop(12), hourHeader = true)
    }

    private fun pump(canvas: Canvas, report: GlucoseReport, days: List<ReportDay>) {
        val sensor = report.window?.content?.sensor != false
        var y = heading(canvas, "Pump by day", if (sensor) "Bolus units, basal pulses and carb grams. In range uses that day's glucose."
            else "Recorded delivery and carbs. Missing data does not confirm zero delivery.")
        if (report.days.isEmpty()) {
            line(canvas, y, "No pump events or glucose days are cached.")
            return
        }
        val paint = text(8.5f, bold = true)
        val cols = if (sensor) floatArrayOf(MARGIN, 150f, 210f, 270f, 340f, 400f, 470f)
            else floatArrayOf(MARGIN, 230f, 340f, 450f)
        val headers = if (sensor) listOf("Day", "Readings", "Mean", "In range", "Bolus U", "Basal U", "Carbs g")
            else listOf("Day", "Bolus U", "Basal U", "Carbs g")
        headers.forEachIndexed { i, header -> canvas.drawText(header, cols[i], y, paint) }
        y += 6f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule())
        y += 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = INK
        days.forEach { day ->
            val cells = listOf(day.label) + (if (sensor) listOf(day.readings.toString(),
                day.mean?.let(::mgdl) ?: "--", day.inRangePercent?.let { integer(it) + "%" } ?: "--") else emptyList()) +
                listOf(one(day.bolusU), one(day.basalU), integer(day.carbsG))
            cells.forEachIndexed { i, cell -> canvas.drawText(cell, cols[i], y, paint) }
            y += 14f
        }
        note(canvas, y + 10f, "All requested days are included across pages. Zero means no delivered units were recorded, not confirmed zero delivery. Basal rates are settings; they are not counted as doses.")
    }

    private fun tablePage(canvas: Canvas, title: String, subtitle: String, buckets: List<GlucoseBucket>, weekday: Boolean) {
        val y = heading(canvas, title, subtitle)
        statTable(canvas, y, buckets, hourHeader = !weekday)
    }

    private fun statTable(canvas: Canvas, top: Float, buckets: List<GlucoseBucket>, hourHeader: Boolean): Float {
        if (buckets.isEmpty()) return top
        val labelW = 78f
        val colW = (PAGE_W - 2 * MARGIN - labelW) / buckets.size
        var y = top
        val head = text(7.5f, bold = true)
        buckets.forEachIndexed { index, bucket ->
            val label = if (hourHeader) hourLabel(bucket.label.toIntOrNull() ?: index) else bucket.label
            canvas.drawText(label, MARGIN + labelW + index * colW, y, head)
        }
        y += 5f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule())
        y += 12f
        val rows = listOf(
            "Very high %" to { b: GlucoseBucket -> if (b.n == 0) "--" else pct(b.ranges.veryHigh, b.ranges.n) },
            "High %" to { b: GlucoseBucket -> if (b.n == 0) "--" else pct(b.ranges.high, b.ranges.n) },
            "In range %" to { b: GlucoseBucket -> if (b.n == 0) "--" else pct(b.ranges.inRange, b.ranges.n) },
            "Low %" to { b: GlucoseBucket -> if (b.n == 0) "--" else pct(b.ranges.low, b.ranges.n) },
            "Very low %" to { b: GlucoseBucket -> if (b.n == 0) "--" else pct(b.ranges.veryLow, b.ranges.n) },
            "Readings" to { b: GlucoseBucket -> if (b.n == 0) "--" else b.n.toString() },
            "Min" to { b: GlucoseBucket -> b.min?.toString() ?: "--" },
            "Max" to { b: GlucoseBucket -> b.max?.toString() ?: "--" },
            "Mean" to { b: GlucoseBucket -> b.mean?.let(::mgdl) ?: "--" },
            "Std. dev." to { b: GlucoseBucket -> b.sd?.let(::mgdl) ?: "--" },
            "Quartile 25" to { b: GlucoseBucket -> b.p25?.let(::mgdl) ?: "--" },
            "Median" to { b: GlucoseBucket -> b.p50?.let(::mgdl) ?: "--" },
            "Quartile 75" to { b: GlucoseBucket -> b.p75?.let(::mgdl) ?: "--" },
            "IQR" to { b: GlucoseBucket -> b.iqr?.let(::mgdl) ?: "--" },
            "CV %" to { b: GlucoseBucket -> b.cv?.let(::integer) ?: "--" },
        )
        val paint = text(8f)
        rows.forEach { (name, value) ->
            paint.color = MUTED
            canvas.drawText(name, MARGIN, y, paint)
            paint.color = INK
            buckets.forEachIndexed { index, bucket ->
                canvas.drawText(value(bucket), MARGIN + labelW + index * colW, y, paint)
            }
            y += 13f
        }
        return y
    }

    private fun heading(canvas: Canvas, title: String, subtitle: String): Float {
        val paint = text(20f, bold = true)
        canvas.drawText(title, MARGIN, 52f, paint)
        paint.textSize = 9.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = MUTED
        val wrapped = wrap(subtitle, paint, PAGE_W - 2 * MARGIN)
        var y = 70f
        wrapped.forEach { canvas.drawText(it, MARGIN, y, paint); y += 12f }
        return y + 8f
    }

    private fun section(canvas: Canvas, y: Float, title: String): Float {
        val paint = text(12f, bold = true)
        canvas.drawText(title, MARGIN, y + 12f, paint)
        return y + 28f
    }

    private fun line(canvas: Canvas, y: Float, value: String, size: Float = 10.5f, bold: Boolean = false): Float {
        val paint = text(size, bold)
        var at = y
        wrap(value, paint, PAGE_W - 2 * MARGIN).forEach { canvas.drawText(it, MARGIN, at, paint); at += size + 6f }
        return at
    }

    private fun note(canvas: Canvas, y: Float, value: String) {
        val paint = text(8.5f)
        paint.color = MUTED
        var at = y
        wrap(value, paint, PAGE_W - 2 * MARGIN).forEach { canvas.drawText(it, MARGIN, at, paint); at += 11f }
    }

    private fun metric(canvas: Canvas, x: Float, y: Float, w: Float, value: String, unit: String, label: String) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F7F6.toInt(); style = Paint.Style.FILL }
        canvas.drawRoundRect(x, y, x + w, y + 64f, 8f, 8f, fill)
        val valuePaint = text(18f, bold = true)
        canvas.drawText(value, x + 10f, y + 28f, valuePaint)
        val unitPaint = text(9f).apply { color = MUTED }
        if (unit.isNotEmpty()) canvas.drawText(unit, x + 14f + valuePaint.measureText(value), y + 28f, unitPaint)
        canvas.drawText(label, x + 10f, y + 48f, unitPaint)
    }

    private fun bar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, ranges: RangeCounts) {
        val parts = listOf(
            ranges.veryLow to VERY_LOW,
            ranges.low to LOW,
            ranges.inRange to IN_RANGE,
            ranges.high to HIGH,
            ranges.veryHigh to VERY_HIGH,
        )
        val total = ranges.n.coerceAtLeast(1)
        var at = x
        parts.forEach { (count, color) ->
            val span = w * count / total
            if (span > 0f) canvas.drawRect(at, y, at + span, y + h, Paint().apply { this.color = color })
            at += span
        }
        if (ranges.n == 0) canvas.drawRect(x, y, x + w, y + h, Paint().apply { color = 0xFFE4EAE8.toInt() })
    }

    private fun legend(canvas: Canvas, y: Float, ranges: RangeCounts): Float {
        val items = listOf(
            "Very low ${pct(ranges.veryLow, ranges.n)}%" to VERY_LOW,
            "Low ${pct(ranges.low, ranges.n)}%" to LOW,
            "In range ${pct(ranges.inRange, ranges.n)}%" to IN_RANGE,
            "High ${pct(ranges.high, ranges.n)}%" to HIGH,
            "Very high ${pct(ranges.veryHigh, ranges.n)}%" to VERY_HIGH,
        )
        val paint = text(9f)
        var x = MARGIN
        items.forEach { (label, color) ->
            canvas.drawRect(x, y - 8f, x + 8f, y, Paint().apply { this.color = color })
            paint.color = INK
            canvas.drawText(label, x + 12f, y, paint)
            x += paint.measureText(label) + 28f
        }
        return y + 16f
    }

    private fun drawBands(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        fun rect(low: Double, high: Double, color: Int) {
            canvas.drawRect(left, yOf(high, top, bottom), right, yOf(low, top, bottom), Paint().apply { this.color = color })
        }
        rect(40.0, 54.0, GlucosePalette.withAlpha(VERY_LOW, 28))
        rect(54.0, 70.0, GlucosePalette.withAlpha(LOW, 28))
        rect(70.0, 180.0, GlucosePalette.withAlpha(IN_RANGE, 28))
        rect(180.0, 250.0, GlucosePalette.withAlpha(HIGH, 36))
        rect(250.0, 400.0, GlucosePalette.withAlpha(VERY_HIGH, 28))
        val grid = Paint().apply { color = 0xFFD5DEE2.toInt(); strokeWidth = 1f }
        listOf(54.0, 70.0, 180.0, 250.0).forEach { level ->
            val y = yOf(level, top, bottom)
            canvas.drawLine(left, y, right, y, grid)
        }
    }

    private fun band(
        canvas: Canvas,
        slots: List<DayPatternHour>,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        low: (DayPatternHour) -> Double?,
        high: (DayPatternHour) -> Double?,
        color: Int,
    ) {
        val path = Path()
        val usable = slots.mapIndexedNotNull { index, slot ->
            val a = low(slot)
            val b = high(slot)
            if (a == null || b == null) null else Triple(index, a, b)
        }
        val segments = mutableListOf<MutableList<Triple<Int, Double, Double>>>()
        usable.forEach { point ->
            if (segments.isEmpty() || segments.last().last().first + 1 != point.first) segments += mutableListOf(point)
            else segments.last() += point
        }
        segments.filter { it.size >= 2 }.forEach { segment ->
            path.moveTo(xOf(segment.first().first, left, right), yOf(segment.first().second, top, bottom))
            segment.drop(1).forEach { path.lineTo(xOf(it.first, left, right), yOf(it.second, top, bottom)) }
            segment.asReversed().forEach { path.lineTo(xOf(it.first, left, right), yOf(it.third, top, bottom)) }
            path.close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
    }

    private fun lineThrough(
        canvas: Canvas,
        slots: List<DayPatternHour>,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        value: (DayPatternHour) -> Double?,
        color: Int,
        width: Float,
    ) {
        val path = Path()
        var started = false
        slots.forEachIndexed { index, slot ->
            val v = value(slot)
            if (v == null) {
                started = false
            } else if (!started) {
                path.moveTo(xOf(index, left, right), yOf(v, top, bottom))
                started = true
            } else {
                path.lineTo(xOf(index, left, right), yOf(v, top, bottom))
            }
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
        })
    }

    private fun axisLabels(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val paint = text(8f)
        paint.color = MUTED
        listOf(40, 70, 180, 250, 400).forEach { level ->
            canvas.drawText(level.toString(), MARGIN, yOf(level.toDouble(), top, bottom) + 3f, paint)
        }
        listOf(0, 6, 12, 18, 23).forEach { hour ->
            canvas.drawText(hourLabel(hour), xOf(hour, left, right) - 8f, bottom + 12f, paint)
        }
    }

    private fun bars(canvas: Canvas, y: Float, values: List<Double>, color: Int, label: String): Float {
        val paint = text(8f)
        paint.color = MUTED
        canvas.drawText(label, MARGIN, y + 10f, paint)
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 0.0
        val left = MARGIN + 52f
        val width = (PAGE_W - MARGIN - left) / values.size.coerceAtLeast(1)
        values.forEachIndexed { index, value ->
            val h = if (max == 0.0) 0f else (28f * value / max).toFloat()
            val x = left + index * width
            canvas.drawRect(x + 1f, y + 28f - h, x + width - 2f, y + 28f, Paint().apply { this.color = color })
        }
        if (max == 0.0) canvas.drawText("none", left, y + 18f, paint)
        return y + 40f
    }

    private fun footer(canvas: Canvas, page: Int, pages: Int, generated: String) {
        val paint = text(8f)
        paint.color = MUTED
        val y = PAGE_H - 22f
        canvas.drawText("GlucoWatch | not a medical device | $generated", MARGIN, y, paint)
        val mark = "$page of $pages"
        canvas.drawText(mark, PAGE_W - MARGIN - paint.measureText(mark), y, paint)
    }

    private fun period(report: GlucoseReport): String {
        val zone = runCatching { ZoneId.of(report.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val start = report.summary.startMillis
        val end = report.summary.endMillis
        if (start == null || end == null) return "No glucose in this export"
        val format = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
        val a = format.format(Instant.ofEpochMilli(start).atZone(zone))
        val b = format.format(Instant.ofEpochMilli(end).atZone(zone))
        val days = report.summary.spanDays
        return "$days day" + (if (days == 1) "" else "s") + " | $a - $b | Requested dates; unavailable samples stay missing."
    }

    private fun yOf(mgdl: Double, top: Float, bottom: Float): Float {
        val t = ((mgdl - 40.0) / 360.0).coerceIn(0.0, 1.0)
        return bottom - (bottom - top) * t.toFloat()
    }

    private fun xOf(hour: Int, left: Float, right: Float): Float = left + (right - left) * (hour + 0.5f) / 24f

    private fun hourLabel(hour: Int) = when (hour) {
        0 -> "12a"
        12 -> "12p"
        in 1..11 -> "${hour}a"
        else -> "${hour - 12}p"
    }

    private fun wrap(value: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        value.split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) <= width) line = next
            else {
                if (line.isNotEmpty()) { lines += line; line = "" }
                // IDs and compact JSON can be a single long word. Break only at code-point boundaries.
                word.codePoints().toArray().forEach { codePoint ->
                    val character = String(Character.toChars(codePoint))
                    if (line.isNotEmpty() && paint.measureText(line + character) > width) { lines += line; line = "" }
                    line += character
                }
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun text(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun rule() = Paint().apply { color = 0xFFD5DEE2.toInt(); strokeWidth = 1f }

    private fun mgdl(value: Double) = value.roundToInt().toString()
    private fun one(value: Double) = String.format(Locale.US, "%.1f", value)
    private fun integer(value: Double) = value.roundToInt().toString()

    private fun pct(part: Int, n: Int): String {
        if (n == 0 || part == 0) return "0"
        val percent = 100.0 * part / n
        return if (percent < 1) "<1" else percent.roundToInt().toString()
    }
}
