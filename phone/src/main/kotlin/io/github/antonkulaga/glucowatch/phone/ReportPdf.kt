package io.github.antonkulaga.glucowatch.phone

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import glucowatch.core.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Production implementation of the approved rounded, layered, Clarity-style report. */
object ReportPdf {
    fun render(report: GlucoseReport, generatedMillis: Long = System.currentTimeMillis(), workouts: List<Workout> = emptyList(),
               zoneBoundaries: List<Int> = WorkoutAnalysis.defaultHeartBoundaries,
               paceBoundaries: List<Int> = WorkoutAnalysis.defaultPaceBoundaries): ByteArray =
        if (report.days.isNotEmpty() && (report.days.size == 1 || workouts.isNotEmpty()) &&
            report.window?.content?.sensor != false && report.window?.content?.pump != false)
            ReviewedReportPdf.render(report, workouts, zoneBoundaries, generatedMillis, paceBoundaries)
        else Renderer(report, generatedMillis, workouts, zoneBoundaries, paceBoundaries).render()

    private class Renderer(val report: GlucoseReport, val generated: Long, val workouts: List<Workout>, val zoneBoundaries: List<Int>, val paceBoundaries: List<Int>) {
        val model = ReportPresentation.build(report)
        val sensor = report.window?.content?.sensor != false
        val pump = report.window?.content?.pump != false
        val left = ReportStyle.MARGIN
        val right = ReportStyle.WIDTH - left
        val width = right - left
        val plotLeft = 60f
        val plotRight = 490f
        val plotWidth = plotRight - plotLeft
        val generatedLabel = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").format(Instant.ofEpochMilli(generated).atZone(model.zone))
        fun date(time: Long) = Instant.ofEpochMilli(time).atZone(model.zone).toLocalDate()
        fun start(day: LocalDate) = day.atStartOfDay(model.zone).toInstant().toEpochMilli()
        fun clock(time: Long): Double = Instant.ofEpochMilli(time).atZone(model.zone).let { it.hour + it.minute / 60.0 + it.second / 3600.0 }
        fun x(hour: Double) = plotLeft + plotWidth * hour.toFloat() / 24f
        fun glucoseY(value: Double, top: Float, height: Float) = top + height - ((value - 39) / 362).coerceIn(0.0, 1.0).toFloat() * height
        fun fmt(value: Double?, decimals: Int = 1) = value?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "--"
        fun pct(count: Int, total: Int): String = if (total == 0) "--" else (100.0 * count / total).let { if (count > 0 && it < 1) "<1" else fmt(it, 0) }

        fun render(): ByteArray {
            val pages = mutableListOf<(Canvas) -> Unit>()
            pages += { overview(it) }
            if (sensor && report.readings.isNotEmpty()) {
                pages += { patterns(it) }
            }
            if (report.readings.isNotEmpty() || pump) pages += { profiles(it) }
            if (sensor && report.readings.isNotEmpty()) {
                report.days.chunked(7).asReversed().forEach { days -> pages += { overlay(it, days) } }
            }
            report.days.asReversed().forEach { day ->
                val events = dailyEvents(day.date)
                pages += { daily(it, day, events.take(18)) }
                events.drop(18).chunked(32).forEach { rows -> pages += { eventPage(it, day, rows) } }
            }
            if (sensor && report.readings.isNotEmpty()) {
                pages += { statistics(it, "Daily Statistics", report.weekdays) }
                pages += { hourly(it) }
            }
            if (pump) report.days.chunked(20).forEach { days -> pages += { pumpTable(it, days) } }
            if (sensor && report.days.size >= 2 && report.readings.isNotEmpty()) pages += { compare(it) }
            pages += { definitions(it) }
            val from = report.window?.start?.atStartOfDay(model.zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
            val until = report.window?.end?.plusDays(1)?.atStartOfDay(model.zone)?.toInstant()?.toEpochMilli()?.minus(1) ?: Long.MAX_VALUE
            workouts.forEach { workout -> pages += WorkoutReportPdf.pages(workout, report.readings, report.events, model.zone, zoneBoundaries, from, until, paceBoundaries) }
            val pdf = PdfDocument()
            return try {
                pages.forEachIndexed { i, draw ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(ReportStyle.WIDTH, ReportStyle.HEIGHT, i + 1).create())
                    try { page.canvas.drawColor(Color.WHITE); draw(page.canvas); footer(page.canvas, i + 1, pages.size) }
                    finally { pdf.finishPage(page) }
                }
                ByteArrayOutputStream().also(pdf::writeTo).toByteArray()
            } finally { pdf.close() }
        }

        fun paint(size: Float = 9f, bold: Boolean = false, color: Int = ReportStyle.INK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        fun text(c: Canvas, x: Float, y: Float, value: String, size: Float = 9f, bold: Boolean = false, color: Int = ReportStyle.INK) =
            c.drawText(value, x, y, paint(size, bold, color))
        fun rightText(c: Canvas, x: Float, y: Float, value: String, size: Float = 9f, color: Int = ReportStyle.INK) {
            val p = paint(size, color = color); c.drawText(value, x - p.measureText(value), y, p)
        }
        fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int = ReportStyle.GRID, stroke: Float = .4f) =
            c.drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = stroke })
        fun rect(c: Canvas, box: RectF, color: Int, alpha: Int = 255) =
            c.drawRect(box, Paint().apply { this.color = color; this.alpha = alpha })
        fun rounded(c: Canvas, box: RectF, color: Int, alpha: Int = 255, radius: Float = ReportStyle.RADIUS) {
            if (box.width() <= 0 || box.height() <= 0) return
            val r = min(radius, min(box.width(), box.height()) / 2)
            c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha })
        }
        fun roundedPath(box: RectF) = Path().apply {
            val radius = min(ReportStyle.RADIUS, min(box.width(), box.height()) / 2)
            addRoundRect(box, radius, radius, Path.Direction.CW)
        }
        fun layers(c: Canvas, baseline: RectF, overlay: RectF, color: Int = ReportStyle.BASAL) {
            rounded(c, overlay, color, ReportStyle.LAYER_ALPHA)
            val save = c.save()
            try {
                c.clipPath(roundedPath(baseline)); c.clipPath(roundedPath(overlay))
                rect(c, overlay, color, ReportStyle.INTERSECTION_ALPHA)
            } finally { c.restoreToCount(save) }
        }
        fun wrap(value: String, size: Float, available: Float): List<String> {
            val p = paint(size); val result = mutableListOf<String>(); var pending = ""
            value.split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
                val next = if (pending.isEmpty()) word else "$pending $word"
                if (p.measureText(next) <= available) pending = next else {
                    if (pending.isNotEmpty()) result += pending
                    pending = ""
                    word.codePoints().toArray().forEach { cp ->
                        val s = String(Character.toChars(cp))
                        if (pending.isNotEmpty() && p.measureText(pending + s) > available) { result += pending; pending = "" }
                        pending += s
                    }
                }
            }
            if (pending.isNotEmpty()) result += pending
            return result
        }
        fun paragraph(c: Canvas, y: Float, value: String, size: Float = 8f, x: Float = left, available: Float = width, color: Int = ReportStyle.INK): Float {
            var at = y + size
            wrap(value, size, available).forEach { text(c, x, at, it, size, color = color); at += size * 1.35f }
            return at
        }
        fun title(c: Canvas, value: String, subtitle: String? = null) {
            var size = 18f
            while (paint(size, true).measureText(value) > 440 && size > 12) size -= .5f
            text(c, left, 53f, value, size, true)
            text(c, left, 65f, period(), 7.3f, true)
            rightText(c, right, 44f, "GlucoPhone report", 8f)
            rightText(c, right, 56f, "Private health data", 7f, ReportStyle.GREY)
            if (subtitle != null) text(c, left, 90f, subtitle, 10f, true)
        }
        fun period(): String {
            val a = report.days.firstOrNull()?.date ?: return "No data in requested interval"
            val b = report.days.last().date
            val f = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
            return "${model.coverage.requestedDays} days | ${f.format(a)} - ${f.format(b)}"
        }
        fun section(c: Canvas, y: Float, label: String, w: Float = width) { text(c, left, y, label, 11f, true); line(c, left, y + 6, left + w, y + 6, ReportStyle.INK, .5f) }
        fun footer(c: Canvas, page: Int, pages: Int) {
            line(c, left, 751f, right, 751f, ReportStyle.GREY)
            text(c, left, 764f, "GlucoPhone | ${report.zoneId} | $generatedLabel", 6.1f)
            rightText(c, right, 764f, "$page of $pages", 6.1f)
            text(c, left, 775f, "Not a medical device. Reconstructed basal settings and programmed amounts are not measured delivery.", 5.7f)
        }

        fun overview(c: Canvas) {
            title(c, "Overview")
            val s = report.summary
            if (sensor) {
                text(c, left, 88f, "Glucose", 15f, true); line(c, left, 103f, right, 103f, ReportStyle.INK, .5f)
                text(c, left, 117f, "Average glucose", 10f, true)
                val avg = fmt(s.mean, 0); text(c, left - 1, 163f, avg, 45f, true)
                text(c, left + paint(45f, true).measureText(avg) + 5, 163f, "mg/dL", 8f)
                text(c, 245f, 117f, "Time in Range", 10f, true); verticalRange(c, s.ranges)
                text(c, left, 222f, "Standard deviation", 8f, true); text(c, left, 242f, fmt(s.sd, 0), 21f, true)
                text(c, 55f, 242f, "mg/dL", 7.5f)
                text(c, 128f, 222f, "GMI", 8f, true); text(c, 128f, 242f, fmt(s.gmi), 21f, true); text(c, 164f, 242f, "%", 8f)
                text(c, 245f, 235f, "Target Range:", 8f, true); text(c, 245f, 247f, "70-180 mg/dL", 8f)
            }
            text(c, 467f, 117f, "Coverage (days)", 10f, true)
            listOf("Sensor" to model.coverage.sensorDays, "Pump" to model.coverage.pumpDays, "Both" to model.coverage.bothDays).forEachIndexed { i, (name, count) ->
                text(c, 467f, 137f + i * 42, name, 8f, true); text(c, 467f, 156f + i * 42, "$count / ${model.coverage.requestedDays}", 17f, true)
            }
            text(c, 467f, 254f, "Days with any data.", 6.1f)
            val insulinY = if (sensor) 270f else 110f
            if (pump) {
                section(c, insulinY, "Insulin", 342f)
                rect(c, RectF(left, insulinY + 13, left + 28, insulinY + 40), ReportStyle.BOLUS)
                rect(c, RectF(left, insulinY + 45, left + 28, insulinY + 72), ReportStyle.BASAL)
                text(c, 62f, insulinY + 23, "Bolus - delivered", 10f, true)
                text(c, 62f, insulinY + 37, "${fmt(s.bolusPerDay)} U/day recorded", 9f)
                text(c, 62f, insulinY + 55, if (model.programmedBasal) "Basal - inferred schedule" else "Basal - recorded delivery", 9.5f, true)
                text(c, 62f, insulinY + 69, when {
                    model.programmedBasal -> "${fmt(model.scheduledDailyUnits, 2)} U/day programmed"
                    model.basalPulses.isNotEmpty() -> "${fmt(s.basalPerDay)} U/day recorded"
                    else -> "Unavailable"
                }, 9f)
                text(c, 245f, insulinY + 22, "Total recorded delivery", 8f, true)
                text(c, 245f, insulinY + 39, if (model.basalPulses.isEmpty()) "Unavailable" else "${fmt(s.bolusPerDay + s.basalPerDay)} U/day", 13f, true)
                val carbsX = if (sensor) 423f else 245f
                val carbsY = if (sensor) insulinY + 22 else insulinY + 100
                text(c, carbsX, carbsY, "Logged carbs", 8f, true); text(c, carbsX, carbsY + 17, "${fmt(s.carbsPerDay, 0)} g/day", 13f, true)
                paragraph(c, insulinY + 53, if (model.basalPulses.isEmpty()) "Basal doses are not supplied. Programmed settings remain separate from delivery." else "Only recorded doses contribute to these totals.", 7.2f, 245f, 310f)
            }
            if (sensor) {
                report.summary.bestDay?.let { best ->
                    text(c, left, 375f, "Top Patterns", 13f)
                    rounded(c, RectF(225f, 363f, 231f, 385f), ReportStyle.GREEN, radius = 3f)
                    text(c, 244f, 374f, "Best recorded glucose day: ${best.date}", 10f, true)
                    text(c, 244f, 386f, "${fmt(best.inRangePercent, 0)}% of recorded samples in range; ${best.readings} readings.", 7f)
                }
                text(c, left, 415f, "This graph shows your glucose averaged over the requested interval", 7.5f)
                glucoseProfile(c, 431f, 92f)
            }
            if (pump) {
                val redTop = if (sensor) 568f else 300f
                text(c, left, redTop - 11, "Bolus", 9f, true, ReportStyle.BOLUS)
                text(c, 95f, redTop - 11, "Delivered hourly totals - min, median and max across observed hours", 7.1f)
                distribution(c, model.bolusProfile, redTop, if (sensor) 44f else 110f, ReportStyle.BOLUS, false, false)
                val blueTop = if (sensor) 655f else 495f
                text(c, left, blueTop - 11, "Basal", 9f, true, ReportStyle.BASAL)
                text(c, 95f, blueTop - 11, if (model.programmedBasal) "Programmed U per hour - scheduled and adjusted ranges" else "Recorded delivered U per hour", 7.1f)
                distribution(c, model.basalProfile, blueTop, if (sensor) 43f else 110f, ReportStyle.BASAL, model.programmedBasal, true)
            }
            paragraph(c, 726f, "Coverage counts days with any data. Missing hours stay unavailable." + if (pump) " Insulin rectangles show min-max; black strokes mark medians." else " Glucose bars show the 15th-75th percentiles; the black line marks the mean.", 6.3f)
        }

        fun verticalRange(c: Canvas, ranges: RangeCounts) {
            val counts = listOf(ranges.veryHigh, ranges.high, ranges.inRange, ranges.low, ranges.veryLow)
            val colors = listOf(ReportStyle.ORANGE, ReportStyle.YELLOW, ReportStyle.GREEN, ReportStyle.LOW, ReportStyle.VERY_LOW)
            val labels = listOf("Very High", "High", "In Range", "Low", "Very Low")
            var y = 130f
            counts.forEachIndexed { i, count -> val h = 92f * count / ranges.n.coerceAtLeast(1); rect(c, RectF(245f, y, 273f, y + h), colors[i]); line(c, 245f, y, 273f, y, Color.WHITE, .7f); y += h }
            labels.forEachIndexed { i, label -> text(c, 278f, 137f + i * 18, "${pct(counts[i], ranges.n)}% $label", 9.2f, true) }
        }

        fun timeLabels(c: Canvas, y: Float) {
            (0..24 step 3).forEach { hour -> text(c, x(hour.toDouble()) - 7, y, when (hour) { 0, 24 -> "12am"; 12 -> "12pm"; else -> (hour % 12).toString() }, 6.5f) }
        }
        fun glucoseAxes(c: Canvas, top: Float, height: Float) {
            (50..400 step 50).forEach { value -> val yy = glucoseY(value.toDouble(), top, height); line(c, plotLeft, yy, plotRight, yy); text(c, plotRight + 2, yy + 2, "$value", 6f, color = ReportStyle.GREY) }
            line(c, plotLeft, top, plotRight, top, ReportStyle.GREY); line(c, plotLeft, top + height, plotRight, top + height, ReportStyle.GREY)
            line(c, plotLeft, top, plotLeft, top + height, ReportStyle.GREY)
            listOf(180 to ReportStyle.HIGH_RULE, 70 to ReportStyle.LOW_RULE).forEach { (value, color) ->
                val yy = glucoseY(value.toDouble(), top, height); line(c, plotLeft, yy, plotRight, yy, color, 1f)
                rounded(c, RectF(30f, yy - 5, 60f, yy + 5), color, radius = 5f); text(c, 38f, yy + 2.7f, "$value", 7f, true, Color.WHITE)
            }
            timeLabels(c, top + height + 16); text(c, plotRight + 2, top + height + 10, "mg/dL", 5.7f, color = ReportStyle.GREY)
        }
        fun glucoseProfile(c: Canvas, top: Float, height: Float) {
            glucoseAxes(c, top, height)
            val path = Path(); var previous = false
            model.glucoseProfile.forEachIndexed { i, value ->
                if (value == null) { previous = false; return@forEachIndexed }
                val xx = plotLeft + plotWidth * (i + .5f) / 96
                fun stroke(lo: Double, hi: Double, color: Int) {
                    if (hi <= lo) return
                    c.drawLine(xx, glucoseY(lo, top, height), xx, glucoseY(hi, top, height), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND })
                }
                stroke(value.p15, value.p75, 0xFFBDBDBD.toInt())
                if (value.p75 > 180) stroke(maxOf(180.0, value.p15), value.p75, ReportStyle.YELLOW)
                if (value.p15 < 70) stroke(value.p15, minOf(70.0, value.p75), ReportStyle.LOW)
                val yy = glucoseY(value.mean, top, height)
                if (previous) path.lineTo(xx, yy) else path.moveTo(xx, yy)
                previous = true
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportStyle.INK; style = Paint.Style.STROKE; strokeWidth = .6f })
            listOf("ABOVE HIGH" to 9f, "THRESHOLD" to 17f, "75TH" to 33f, "PERCENTILE" to 40f, "AVERAGE" to 48f, "15TH" to 58f, "PERCENTILE" to 65f, "BELOW LOW" to 80f, "THRESHOLD" to 88f).forEach { (label, dy) -> text(c, 528f, top + dy, label, 5.3f, true) }
            line(c, 518f, top + 4, 518f, top + 20, ReportStyle.YELLOW, 3f); line(c, 518f, top + 30, 518f, top + 58, 0xFFBDBDBD.toInt(), 3f)
            c.drawCircle(518f, top + 45, 1.4f, paint()); line(c, 518f, top + 75, 518f, top + 91, ReportStyle.LOW, 3f)
        }
        fun grid(c: Canvas, top: Float, height: Float, max: Double, blue: Boolean = false) {
            var step = if (max <= 4) .5 else if (max <= 8) 1.0 else 2.0
            while (max / step > 9) step *= 2
            var value = 0.0
            while (value <= max + .001) {
                val y = top + height - (value / max).toFloat() * height
                line(c, plotLeft, y, plotRight, y)
                text(c, plotRight + 3, y + 1.8f, fmt(value, if (step < 1) 1 else 0), if (height >= 70) 6f else 5.2f, color = if (blue) ReportStyle.BASAL else ReportStyle.GREY)
                value += step
            }
        }
        fun distribution(c: Canvas, ranges: List<ReportRange?>, top: Float, height: Float, color: Int, baseline: Boolean, labels: Boolean) {
            val scheduledMax = if (baseline) (0..23).mapNotNull { model.scheduledUnits(it) }.maxOrNull() ?: 0.0 else 0.0
            val maximum = maxOf(1.0, ceil(maxOf(scheduledMax, ranges.mapNotNull { it?.maximum }.maxOrNull() ?: 1.0) * 1.08))
            fun y(value: Double) = top + height - (value / maximum).toFloat() * height
            grid(c, top, height, maximum); text(c, plotRight + 3, top - 6, "U", 6f, color = ReportStyle.GREY)
            val w = plotWidth / 24 * .62f
            ranges.forEachIndexed { hour, range ->
                val xx = x(hour + .5)
                val base = range?.let { model.scheduledUnits(hour) }?.let { RectF(xx - w / 2, y(it), xx + w / 2, top + height) }
                if (baseline && base != null) rounded(c, base, color, ReportStyle.LAYER_ALPHA)
                if (range == null) return@forEachIndexed
                val box = RectF(xx - w / 2, y(range.maximum), xx + w / 2, maxOf(y(range.minimum), y(range.maximum) + .35f))
                if (baseline && base != null) layers(c, base, box, color) else rounded(c, box, color, 230)
                line(c, xx - w * .35f, y(range.median), xx + w * .35f, y(range.median), ReportStyle.INK, .9f)
            }
            if (ranges.all { it == null }) text(c, plotLeft + 8, top + height / 2, "Unavailable for the observed interval", 8f, color = ReportStyle.GREY)
            rounded(c, RectF(517f, top + 3, 524f, top + 23), color, if (baseline) 128 else 230)
            line(c, 518f, top + 13, 523f, top + 13, ReportStyle.INK, 1f)
            listOf("MAX", "MEDIAN", "MIN").forEachIndexed { i, label -> text(c, 530f, top + 7 + i * 9, label, 5.5f, true) }
            if (baseline) { rounded(c, RectF(517f, top + 33, 524f, top + 38), color, ReportStyle.LAYER_ALPHA); text(c, 530f, top + 38, "SCHEDULED", 5.2f, true) }
            if (labels) timeLabels(c, top + height + 14)
        }

        fun profiles(c: Canvas) {
            title(c, if (sensor && pump) "Glucose and insulin by time of day" else if (sensor) "Glucose by time of day" else "Insulin by time of day")
            if (sensor) { text(c, left, 101f, "Glucose", 11f, true); glucoseProfile(c, 118f, if (pump) 215f else 430f) }
            if (pump) {
                val red = if (sensor) 395f else 145f
                text(c, left, red - 17, "Bolus - recorded delivered units per hour", 11f, true, ReportStyle.BOLUS)
                distribution(c, model.bolusProfile, red, 101f, ReportStyle.BOLUS, false, true)
                val blue = if (sensor) 557f else 390f
                text(c, left, blue - 17, if (model.programmedBasal) "Basal - programmed units per hour" else "Basal - recorded delivered units per hour", 11f, true, ReportStyle.BASAL)
                distribution(c, model.basalProfile, blue, 101f, ReportStyle.BASAL, model.programmedBasal, true)
            }
            paragraph(c, 699f, if (pump) "Insulin rectangles span min-max hourly totals across observed days; black strokes mark medians. Pale blue is the inferred scheduled amount. Programmed basal units use exact rate settings and interval durations; they are separate from recorded delivery." else "Glucose bars show the 15th-75th percentiles in fifteen-minute bins. The black line marks the mean. Missing intervals break the line.", 7.4f)
        }

        fun trace(c: Canvas, readings: List<GlucoseReading>, top: Float, height: Float, color: Int = ReportStyle.INK) {
            val path = Path(); var previous: GlucoseReading? = null
            readings.forEach { value ->
                val xx = x(clock(value.timeMillis)); val yy = glucoseY(value.mgdl.toDouble(), top, height)
                if (previous == null || value.timeMillis - previous!!.timeMillis > 900_000) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                previous = value
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = .8f })
        }
        fun patterns(c: Canvas) {
            title(c, "Patterns", "Best Day")
            val best = report.summary.bestDay ?: run { paragraph(c, 125f, "No day has the 24 recorded samples needed for a best-day comparison.", 10f); return }
            text(c, left, 119f, "Best recorded glucose day: ${best.label}", 12f, true)
            text(c, left, 136f, "${fmt(best.inRangePercent)}% of recorded samples in the 70-180 mg/dL target range.", 9f)
            glucoseAxes(c, 180f, 216f); trace(c, report.readings.filter { date(it.timeMillis) == best.date }, 180f, 216f)
            section(c, 446f, "Statistics for this day")
            listOf("Average glucose" to "${fmt(best.mean, 0)} mg/dL", "Readings" to best.readings.toString(), "In range" to "${fmt(best.inRangePercent)}%").forEachIndexed { i, (name, value) -> text(c, left + i * 176, 476f, name, 9f, true); text(c, left + i * 176, 504f, value, 20f, true) }
            paragraph(c, 550f, "Best day compares recorded samples, not complete-day coverage. These descriptive summaries do not diagnose patterns or suggest treatment.", 9f)
        }
        fun overlay(c: Canvas, days: List<ReportDay>) {
            title(c, "Overlay", "${days.first().date} - ${days.last().date}")
            val colors = listOf(ReportStyle.INK, 0xFF5E5E5E.toInt(), 0xFF2E6D28.toInt(), 0xFF8D7512.toInt(), 0xFF713B78.toInt(), 0xFFA44F27.toInt(), 0xFF497E93.toInt())
            days.forEachIndexed { i, day -> text(c, left + i * 80, 119f, DateTimeFormatter.ofPattern("EEE d MMM").format(day.date), 8f, true, colors[i]) }
            glucoseAxes(c, 145f, 350f)
            days.forEachIndexed { i, day -> trace(c, report.readings.filter { date(it.timeMillis) == day.date }, 145f, 350f, colors[i]) }
            paragraph(c, 548f, "Each line is one recorded day. Missing spans longer than 15 minutes break the trace. Orange and red rules mark 180 and 70 mg/dL.", 9f)
        }

        fun dailyEvents(day: LocalDate) = report.events.filter { date(it.timeMillis) == day && when (it.kind) {
            ExportRow.KIND_BOLUS -> it.insulinU != null
            ExportRow.KIND_MEAL -> it.carbsG != null
            ExportRow.KIND_CALIBRATION, ExportRow.KIND_METER -> true
            else -> false
        } }.distinctBy { it.source + it.identity() }.sortedBy { it.timeMillis }

        fun daily(c: Canvas, day: ReportDay, events: List<ExportRow>) {
            title(c, "Daily", day.label)
            if (sensor) {
                val height = if (pump) 150f else 300f
                glucoseAxes(c, 120f, height)
                val readings = report.readings.filter { date(it.timeMillis) == day.date }
                if (readings.isEmpty()) text(c, plotLeft + 8, 190f, "No sensor samples for this day.", 9f) else trace(c, readings, 120f, height)
                workouts.filter { it.overlaps(start(day.date), start(day.date.plusDays(1))) }.forEachIndexed { i, workout ->
                    val from = maxOf(start(day.date), workout.startMillis)
                    val until = minOf(start(day.date.plusDays(1)) - 1, workout.endMillis)
                    val a = x(clock(from)); val b = x(clock(until))
                    rect(c, RectF(a, 120f, b, 120f + height), ReportStyle.GREY, 24)
                    text(c, a.coerceAtMost(plotRight - 85), 133f + (i % 3) * 9, ellipsize(workout.name, 6f, 85f), 6f, color = ReportStyle.GREY)
                }
            }
            if (pump) {
                val dy = if (sensor) 0f else -190f
                text(c, left, 309f + dy, "Insulin", 9f, true)
                text(c, 94f, 309f + dy, "Bolus: U (red, left) | Basal: ${if (model.programmedBasal || model.basalPulses.isEmpty()) "U/h" else "U"} (blue, right)", 7.2f)
                if (model.programmedBasal) {
                    val scheduled = RectF(60f, 322f + dy, 70f, 328f + dy); rounded(c, scheduled, ReportStyle.BASAL, ReportStyle.LAYER_ALPHA); text(c, 75f, 328f + dy, "Scheduled 25%", 7f)
                    rounded(c, RectF(174f, 322f + dy, 184f, 328f + dy), ReportStyle.BASAL, ReportStyle.LAYER_ALPHA); text(c, 189f, 328f + dy, "Temporary 25%", 7f)
                    val overlap = RectF(294f, 322f + dy, 304f, 328f + dy); rounded(c, overlap, ReportStyle.BASAL, ReportStyle.LAYER_ALPHA); layers(c, overlap, overlap); text(c, 309f, 328f + dy, "Overlap 50%", 7f)
                    repeat(3) { i -> line(c, 405f + i * 4, 328f + dy, 411f + i * 4, 322f + dy, ReportStyle.BASAL) }; text(c, 424f, 328f + dy, "Suspended", 7f)
                } else text(c, plotLeft, 328f + dy, if (model.basalPulses.isNotEmpty()) "Recorded basal doses" else "Recorded basal rate observations; recurring schedule unavailable", 7f, color = ReportStyle.BASAL)
                dailyInsulin(c, day.date, 341f + dy, 109f - dy)
            }
            val values = if (sensor) listOf("Mean" to "${fmt(day.mean, 0)} mg/dL", "In range" to "${fmt(day.inRangePercent)}%") else emptyList()
            val metrics = values + if (pump) listOf("Bolus" to "${fmt(day.bolusU, 2)} U", "Carbs" to "${fmt(day.carbsG, 0)} g") else emptyList()
            metrics.forEachIndexed { i, (label, value) -> val xx = left + i * width / metrics.size; text(c, xx, 495f, label, 7.5f, true); text(c, xx, 514f, value, 14f, true) }
            text(c, left, 540f, "Recorded events", 10f, true)
            val split = (events.size + 1) / 2
            listOf(events.take(split), events.drop(split)).forEachIndexed { i, rows -> eventTable(c, 550f, rows, left + i * 296, true) }
            paragraph(c, 725f, "${day.readings} sensor samples. Basal display changes round to five minutes. Event table reads down the left column, then the right; source timestamps and calculations stay exact.", 6.7f)
        }

        fun dailyInsulin(c: Canvas, day: LocalDate, top: Float, height: Float) {
            val intervals = model.displayBasal(day, ReportStyle.DISPLAY_MINUTES)
            val rates = model.pumpRows.filter { it.kind == ExportRow.KIND_REPORTED_BASAL && date(it.timeMillis) == day && it.basalRateUPerH != null }
            val pulses = model.basalPulses.filter { date(it.timeMillis) == day }
            val blueMax = if (model.programmedBasal || pulses.isEmpty()) ceil(maxOf(2.5,
                intervals.maxOfOrNull { maxOf(it.scheduledRate, it.programmedRate) } ?: rates.maxOfOrNull { it.basalRateUPerH!! } ?: 1.0) * 2) / 2
                else maxOf(.1, pulses.maxOfOrNull { it.insulinU!! } ?: .1)
            fun y(value: Double) = top + height - (value / blueMax).toFloat() * height
            val step = if (blueMax >= 2) .5 else blueMax / 4
            var tick = 0.0
            while (tick <= blueMax + .0001) { line(c, plotLeft, y(tick), plotRight, y(tick)); text(c, plotRight + 3, y(tick) + 2, fmt(tick, if (blueMax < 1) 2 else 1), 6f, color = ReportStyle.BASAL); tick += step }
            val end = minOf(start(day.plusDays(1)), model.pumpEnd ?: start(day))
            for (hour in 0..23) {
                val a = day.atTime(hour, 0).atZone(model.zone).toInstant().toEpochMilli()
                val b = minOf(day.atTime(hour, 0).plusHours(1).atZone(model.zone).toInstant().toEpochMilli(), end)
                if (b <= a) continue
                val observed = intervals.filter { it.start < b && it.end > a }
                if (observed.isEmpty()) continue
                val runs = mutableListOf<ReportBasalInterval>()
                observed.forEach { interval ->
                    val run = interval.copy(start = maxOf(a, interval.start), end = minOf(b, interval.end))
                    val previous = runs.lastOrNull()
                    if (previous != null && previous.end == run.start && previous.scheduledRate == run.scheduledRate)
                        runs[runs.lastIndex] = previous.copy(end = run.end)
                    else runs += run
                }
                runs.forEach runLoop@ { run ->
                    val base = RectF(maxOf(x(hour.toDouble()) + ReportStyle.HOUR_GAP / 2, x(clock(run.start))), y(run.scheduledRate),
                        minOf(x((hour + 1).toDouble()) - ReportStyle.HOUR_GAP / 2, if (run.end == start(day.plusDays(1))) plotRight else x(clock(run.end))), top + height)
                    if (base.width() <= 0) return@runLoop
                    rounded(c, base, ReportStyle.BASAL, ReportStyle.LAYER_ALPHA)
                    observed.filter { it.start < run.end && it.end > run.start && it.change != ReportBasalChange.SCHEDULED }.forEach changeLoop@ { interval ->
                        val lo = maxOf(run.start, interval.start); val hi = minOf(run.end, interval.end)
                        val x1 = maxOf(base.left, x(clock(lo)))
                        val x2 = minOf(base.right, if (hi == start(day.plusDays(1))) plotRight else x(clock(hi)))
                        val inset = min(.25f, (x2 - x1) * .1f)
                        if (x2 - x1 <= inset * 2) return@changeLoop
                        if (interval.change == ReportBasalChange.TEMPORARY) layers(c, base, RectF(x1 + inset, y(interval.programmedRate), x2 - inset, top + height))
                        else {
                            val save = c.save()
                            try {
                                c.clipPath(roundedPath(base)); c.clipRect(x1, base.top, x2, base.bottom)
                                var xx = x1 - base.height()
                                while (xx < x2 + 4) { line(c, xx, top + height, xx + base.height(), base.top, ReportStyle.BASAL, .45f); xx += 4 }
                            } finally { c.restoreToCount(save) }
                        }
                    }
                }
            }
            if (!model.programmedBasal) {
                if (pulses.isNotEmpty()) pulses.forEach { e -> val xx = x(clock(e.timeMillis)); rounded(c, RectF(xx - 1, y(e.insulinU!!), xx + 1, top + height), ReportStyle.BASAL, 180) }
                else {
                    rates.forEach { e -> val value = e.basalRateUPerH!!; val xx = x(clock(e.timeMillis)); line(c, xx, top + height, xx, y(value), ReportStyle.BASAL); c.drawCircle(xx, y(value), 1.2f, paint(color = ReportStyle.BASAL)) }
                    text(c, plotLeft + 8, top + 13, "Recurring basal schedule unavailable", 7f, color = ReportStyle.GREY)
                }
            }
            if (date(model.pumpEnd ?: start(day)) == day && end < start(day.plusDays(1)) && end >= start(day)) {
                val cutoff = x(clock(end)); rect(c, RectF(cutoff, top, plotRight, top + height), 0xFFF5F5F5.toInt())
                val clock = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(end).atZone(model.zone))
                val gapLabel = "No pump data after $clock"
                if (plotRight - cutoff > paint(6f).measureText(gapLabel) + 10)
                    text(c, cutoff + 5, top + height / 2, gapLabel, 6f, color = ReportStyle.GREY)
            }
            timeLabels(c, top + height + 14); text(c, plotRight + 3, top - 6, if (model.programmedBasal || pulses.isEmpty()) "U/h" else "U", 6f, color = ReportStyle.BASAL)
            val boluses = model.boluses.filter { date(it.timeMillis) == day }
            val redMax = maxOf(2.0, ceil(boluses.maxOfOrNull { it.insulinU!! } ?: 1.0))
            (0..redMax.toInt()).forEach { v -> rightText(c, plotLeft - 7, top + height - (v / redMax).toFloat() * height + 2, "$v", 6f, ReportStyle.BOLUS) }
            rightText(c, plotLeft - 7, top - 6, "U", 6f, ReportStyle.BOLUS)
            boluses.forEach { e -> val xx = x(clock(e.timeMillis)); rounded(c, RectF(xx - 1.1f, top + height - (e.insulinU!! / redMax).toFloat() * height, xx + 1.1f, top + height), ReportStyle.BOLUS, 242, 1.1f) }
        }

        fun eventCells(row: ExportRow): List<String> {
            val time = DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(row.timeMillis).atZone(model.zone))
            val type = row.bolusType?.replace("Dual (normal part)", "Dual normal")?.replace("Dual (square part)", "Dual square")
            return listOf(time, when (row.kind) { ExportRow.KIND_BOLUS -> "Bolus" + (type?.let { " - $it" } ?: ""); ExportRow.KIND_MEAL -> "Carbs - logged"; ExportRow.KIND_CALIBRATION -> "Calibration"; else -> "Meter glucose" },
                row.insulinU?.let { "${fmt(it, 2)} U" } ?: row.carbsG?.let { "${fmt(it, 0)} g" } ?: "${fmt(row.value, 0)} ${row.valueUnit.orEmpty()}")
        }
        fun eventTable(c: Canvas, top: Float, events: List<ExportRow>, x: Float, compact: Boolean) {
            table(c, top, listOf("Time", "Recorded event", "Amount"), events.map(::eventCells), if (compact) listOf(52f, 157f, 63f) else listOf(100f, 335f, 133f), if (compact) 7.5f else 8f, x, if (compact) 16.5f else 17.5f, true)
        }
        fun eventPage(c: Canvas, day: ReportDay, rows: List<ExportRow>) { title(c, "Daily events", "${day.label} - continued"); eventTable(c, 111f, rows, left, false) }
        fun table(c: Canvas, top: Float, headers: List<String>, rows: List<List<String>>, widths: List<Float>, size: Float = 8f, x: Float = left, rowHeight: Float = 24f, singleLine: Boolean = false): Float {
            var y = top
            val headerHeight = if (singleLine) 20f else 24f
            rect(c, RectF(x, y, x + widths.sum(), y + headerHeight), 0xFFF5F5F5.toInt())
            var xx = x
            headers.forEachIndexed { i, head -> text(c, xx + 5, y + 14, head, size, true); xx += widths[i] }; y += headerHeight
            rows.forEachIndexed { rowIndex, cells ->
                val lines = cells.mapIndexed { i, value -> if (singleLine) listOf(ellipsize(value, size, widths[i] - 10)) else wrap(value, size, widths[i] - 10) }
                val h = if (singleLine) rowHeight else maxOf(rowHeight, lines.maxOfOrNull { it.size }?.times(size * 1.3f)?.plus(9) ?: rowHeight)
                if (rowIndex % 2 == 0) rect(c, RectF(x, y, x + widths.sum(), y + h), 0xFFFAFAFA.toInt())
                xx = x
                lines.forEachIndexed { i, content -> content.forEachIndexed { j, value -> text(c, xx + 5, y + 4 + size + j * size * 1.3f, value, size) }; xx += widths[i] }
                y += h; line(c, x, y, x + widths.sum(), y)
            }
            return y
        }
        fun ellipsize(value: String, size: Float, available: Float): String {
            val p = paint(size); if (p.measureText(value) <= available) return value
            var result = value
            while (result.isNotEmpty() && p.measureText("$result...") > available) result = result.dropLast(Character.charCount(result.codePointBefore(result.length)))
            return "$result..."
        }

        fun statistics(c: Canvas, name: String, buckets: List<GlucoseBucket>) {
            title(c, name, "Glucose by weekday")
            val measures = listOf<Pair<String, (GlucoseBucket) -> String>>(
                "Very High %" to { pct(it.ranges.veryHigh, it.n) }, "High %" to { pct(it.ranges.high, it.n) }, "In Range %" to { pct(it.ranges.inRange, it.n) }, "Low %" to { pct(it.ranges.low, it.n) }, "Very Low %" to { pct(it.ranges.veryLow, it.n) },
                "Readings" to { it.n.toString() }, "Minimum" to { it.min?.toString() ?: "--" }, "Maximum" to { it.max?.toString() ?: "--" }, "Mean" to { fmt(it.mean) }, "Standard deviation" to { fmt(it.sd) },
                "25th percentile" to { fmt(it.p25) }, "Median" to { fmt(it.p50) }, "75th percentile" to { fmt(it.p75) }, "IQR" to { fmt(it.iqr) }, "CV %" to { fmt(it.cv) })
            table(c, 117f, listOf("Measure") + buckets.map { it.label }, measures.map { (label, value) -> listOf(label) + buckets.map(value) }, listOf(141f) + List(buckets.size) { (width - 141) / buckets.size }, 8f, rowHeight = 26f)
            paragraph(c, 610f, "Columns pool recorded glucose by weekday. Missing samples stay missing; statistics use the selected glucose source only.", 9f)
        }
        fun hourly(c: Canvas) {
            title(c, "Hourly Statistics", "Glucose by hour")
            report.hours.chunked(12).forEachIndexed { i, buckets -> table(c, if (i == 0) 112f else 424f, listOf("Hour", "N", "Mean", "SD", "Median", "In range %", "CV %"), buckets.map {
                listOf("${it.label}:00", it.n.toString(), fmt(it.mean), fmt(it.sd), fmt(it.p50), if (it.n == 0) "--" else fmt(it.ranges.percent(it.ranges.inRange)), fmt(it.cv))
            }, listOf(86f, 72f, 82f, 82f, 82f, 82f, 82f), 7.8f, rowHeight = 19f) }
        }
        fun pumpTable(c: Canvas, days: List<ReportDay>) {
            title(c, "Pump by day", "Recorded bolus, basal and logged carbohydrates")
            table(c, 116f, listOf("Date", "Bolus U", if (model.programmedBasal) "Basal plan U*" else "Basal U*", "Carbs g", "Pump data"), days.map { day ->
                val has = model.pumpRows.any { date(it.timeMillis) == day.date }
                listOf(DateTimeFormatter.ofPattern("d MMM yyyy").format(day.date), fmt(day.bolusU, 2), if (model.programmedBasal && has) fmt(model.programmedUnits(start(day.date), start(day.date.plusDays(1))), 2) else if (model.basalPulses.isNotEmpty() && has) fmt(day.basalU, 2) else "--", fmt(day.carbsG, 0), if (has) "Present" else "Unavailable")
            }, listOf(145f, 100f, 115f, 90f, 118f), 8.2f, rowHeight = 24f)
            paragraph(c, 670f, "* A basal plan is reconstructed rate x exact interval duration, including temporary settings and suspension. It is not measured delivery. Missing basal doses stay unavailable; rates are never added to recorded dose totals.", 8.5f)
        }
        fun compare(c: Canvas) {
            title(c, "Compare", "First and second halves of the selected interval")
            report.days.chunked((report.days.size + 1) / 2).forEachIndexed { i, days ->
                val dates = days.map { it.date }.toSet(); val points = report.readings.filter { date(it.timeMillis) in dates }
                val ranges = points.fold(RangeCounts()) { a, p -> a + RangeCounts.of(p.mgdl) }
                val y = 119f + i * 250
                text(c, left, y, "${days.first().date} - ${days.last().date}", 11f, true)
                table(c, y + 18, listOf("Mean glucose", "Readings", "In range"), listOf(listOf(if (points.isEmpty()) "--" else "${fmt(points.map { it.mgdl }.average())} mg/dL", "${points.size}", "${pct(ranges.inRange, ranges.n)}%")), List(3) { width / 3 }, 9f)
                var xx = left
                listOf(ranges.veryLow to ReportStyle.VERY_LOW, ranges.low to ReportStyle.LOW, ranges.inRange to ReportStyle.GREEN, ranges.high to ReportStyle.YELLOW, ranges.veryHigh to ReportStyle.ORANGE).forEach { (count, color) -> val w = width * count / ranges.n.coerceAtLeast(1); rect(c, RectF(xx, y + 98, xx + w, y + 118), color); xx += w }
                paragraph(c, y + 154, "Within-period comparison; no earlier-period data is fetched or inferred.", 8.5f)
            }
        }
        fun definitions(c: Canvas) {
            title(c, "Report definitions", "Data and drawing rules")
            table(c, 110f, listOf("Item", "Rule"), listOf(
                listOf("Coverage", "Counts requested calendar days with any sensor data, pump events and both. Presence is not full-day continuity."),
                listOf("Glucose", "The selected sensor is kept separate from other sources. Profile bars show 15th-75th percentiles in 15-minute bins, with a black mean line."),
                listOf("Bolus", "Only recorded delivered doses contribute. Programmed-only values, estimates and prime/fill amounts stay separate."),
                listOf("Basal schedule", "A recurring schedule is inferred only from historical rate snapshots repeated on multiple days. A sparse live rate does not establish a full-day schedule."),
                listOf("Basal changes", "Percent settings multiply the scheduled rate; absolute temporary rates replace it within their duration. Recorded suspension gives zero until resumption."),
                listOf("Basal drawing", "Rounded hourly blocks have visible gaps. Scheduled and temporary layers each use 25% opacity; the intersection is 50%. Only display boundaries snap to five minutes."),
                listOf("Daily axes", "Red bolus spikes use the left U scale. Blue uses the right U/h scale for settings, or U when only delivered basal pulses are available."),
                listOf("Hourly insulin", "Rounded min-max rectangles and black median strokes summarize hourly totals across observed days. Source timestamps and numeric calculations retain their exact precision."),
                listOf("Unknown data", "Missing schedules, doses or hours remain unavailable. Inferred programmed basal units never become measured doses or normalized CSV events."),
                listOf("Statistics", "Sample SD; GMI = 3.31 + 0.02392 x mean mg/dL; CV = 100 x SD / mean. Range bins: <54, 54-69, 70-180, 181-250, >250."),
                listOf("Event categories", report.events.groupingBy { it.kind }.eachCount().entries.sortedBy { it.key }.joinToString("; ") { "${it.key.replace('_', ' ')}: ${it.value}" }.ifEmpty { "No event rows supplied." })
            ), listOf(110f, 458f), 8.2f, rowHeight = 28f)
        }
    }
}
