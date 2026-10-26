package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import glucowatch.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Shared clock for every panel. Pan, pinch and inspect without joining missing observations. */
internal class ExerciseChartView(context: Context, private val workout: Workout, private val analysis: WorkoutAnalysis.Result,
    private val glucose: List<GlucoseReading>, private val rows: List<ExportRow>, private val from: Long, private val until: Long,
    private val inspect: (String) -> Unit) : View(context) {
    private var leftTime = from.toDouble(); private var span = (until - from).toDouble(); private var cursor: Long? = null
    private val density = resources.displayMetrics.density
    private val left get() = 43 * density
    private val right get() = width - 10 * density
    private val plotWidth get() = (right - left).coerceAtLeast(1f)
    private val basal = ReportBasalEstimate.build(rows, ZoneId.systemDefault(), from, until)
    private val bolus = BolusLedger.records(rows).filter { it.insulinU != null }
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private fun x(t: Long) = left + ((t - leftTime) / span * plotWidth).toFloat()
    private fun at(x: Float) = (leftTime + ((x - left) / plotWidth).coerceIn(0f, 1f) * span).toLong()
    private fun clamp() { span = span.coerceIn(minOf(5 * 60_000.0, (until - from).toDouble()), (until - from).toDouble()); leftTime = leftTime.coerceIn(from.toDouble(), until - span) }
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focus = at(detector.focusX); span /= detector.scaleFactor; leftTime = focus - span * ((detector.focusX - left) / plotWidth)
            clamp(); invalidate(); return true
        }
    })
    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale.isInProgress || abs(dx) < abs(dy)) return false
            parent?.requestDisallowInterceptTouchEvent(true); leftTime += dx / plotWidth * span; clamp(); invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean { cursor = at(e.x); describe(cursor!!); performClick(); invalidate(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { leftTime = from.toDouble(); span = (until - from).toDouble(); cursor = null; invalidate(); return true }
    })
    init { contentDescription = "Activity comparison: heart rate, pace or speed, glucose, recorded bolus and calculated basal. Tap to inspect; drag to pan; pinch to zoom; double tap to reset."; isClickable = true }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(event); gesture.onTouchEvent(event); return true
    }
    private fun describe(t: Long) {
        fun value(groups: List<List<WorkoutPoint>>, gap: Long = 90_000L) = groups.flatten().minByOrNull { abs(it.time - t) }
            ?.takeIf { abs(it.time - t) <= gap }?.value?.let { "%.1f".format(Locale.US, it) } ?: "unavailable"
        val g = glucose.minByOrNull { abs(it.timeMillis - t) }?.takeIf { abs(it.timeMillis - t) <= GlucoseHistory.SAMPLE_MS / 2 }
        val b = basal.intervals.firstOrNull { t in it.start until it.end }
        val doses = bolus.filter { abs(it.timeMillis - t) <= 150_000L }.sumOf { it.insulinU ?: 0.0 }
        val message = "${clock.format(Instant.ofEpochMilli(t))}\nHR ${value(analysis.heart)} bpm · ${analysis.motionTitle} ${value(analysis.motion)} ${analysis.motionUnit}\nGlucose ${g?.mgdl ?: "unavailable"} mg/dL\nRecorded bolus nearby: ${if (doses > 0) "%.2f U".format(Locale.US, doses) else "no recorded dose"}\nBasal setting: ${b?.let { "%.2f U/h".format(Locale.US, it.programmedRate) } ?: "unavailable"}"
        inspect(message); contentDescription = message
    }
    private fun paint(color: Int = Brand.TEXT, size: Float = 11f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size * density; strokeWidth = 1.5f * density }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val panel = height / 5f
        repeat(5) { index ->
            val top = panel * index + 28 * density; val bottom = panel * (index + 1) - 23 * density
            val groups = when (index) { 0 -> analysis.heart; 1 -> analysis.motion; 2 -> glucose.map { WorkoutPoint(it.timeMillis, it.mgdl.toDouble()) }.let { points ->
                val result = mutableListOf<MutableList<WorkoutPoint>>()
                points.forEach { p -> if (result.isEmpty() || p.time - result.last().last().time > GlucoseHistory.GAP_MS) result += mutableListOf<WorkoutPoint>(); result.last() += p }; result
            }; else -> emptyList() }
            val values = groups.flatten().map { it.value }
            val minimum = if (index == 2) 40.0 else 0.0
            val maximum = when (index) {
                0 -> maxOf(200.0, values.maxOrNull() ?: 0.0)
                1 -> maxOf(1.0, values.maxOrNull()?.times(1.1) ?: 1.0)
                2 -> maxOf(300.0, values.maxOrNull()?.times(1.05) ?: 300.0)
                3 -> maxOf(1.0, bolus.maxOfOrNull { it.insulinU ?: 0.0 }?.times(1.1) ?: 1.0)
                else -> maxOf(1.0, basal.intervals.maxOfOrNull { it.programmedRate }?.times(1.1) ?: 1.0)
            }
            fun y(v: Double): Float {
                val offset = ((v - minimum) / (maximum - minimum) * (bottom - top)).toFloat()
                return if (index == 1 && analysis.motionUnit.startsWith("min/")) top + offset else bottom - offset
            }
            val title = when (index) { 0 -> "Heart rate · bpm"; 1 -> "${analysis.motionTitle} · ${analysis.motionUnit}"; 2 -> "Glucose · mg/dL"; 3 -> "Recorded bolus · U"; else -> "Basal setting · U/h (calculated)" }
            canvas.drawText(title, 0f, top - 10 * density, paint())
            repeat(3) { line -> val v = minimum + (maximum - minimum) * line / 2
                canvas.drawLine(left, y(v), right, y(v), paint(Brand.SURFACE))
                canvas.drawText(if (maximum <= 10) "%.1f".format(Locale.US, v) else "%.0f".format(Locale.US, v), 0f, y(v) + 3 * density, paint(Brand.MUTED, 9f))
            }
            canvas.save(); canvas.clipRect(left, top, right, bottom)
            if (index <= 2) {
                val elevations = analysis.elevation.flatten(); val min = elevations.minOfOrNull { it.value } ?: 0.0
                val max = elevations.maxOfOrNull { it.value } ?: 0.0
                if (max > min) analysis.elevation.forEach { segment ->
                    val path = Path(); path.moveTo(x(segment.first().time), bottom)
                    segment.forEach { path.lineTo(x(it.time), bottom - ((it.value - min) / (max - min) * (bottom - top)).toFloat()) }
                    path.lineTo(x(segment.last().time), bottom); path.close(); canvas.drawPath(path, paint(0x309aa3a8))
                }
            }
            if (index == 2) for (level in listOf(70, 180)) canvas.drawLine(left, y(level.toDouble()), right, y(level.toDouble()), paint(GlucosePalette.withAlpha(Brand.glucoseColor(level), 120)))
            if (index <= 2) groups.forEach { segment ->
                segment.zipWithNext().forEach { (a, b) -> canvas.drawLine(x(a.time), y(a.value), x(b.time), y(b.value), paint(if (index == 2) Brand.glucoseColor(b.value.toInt()) else Brand.TEXT)) }
                segment.forEach { if (it.time >= leftTime && it.time <= leftTime + span) canvas.drawCircle(x(it.time), y(it.value), density, paint(if (index == 2) Brand.glucoseColor(it.value.toInt()) else Brand.TEXT)) }
            }
            if (index == 3) bolus.filter { it.timeMillis in from until until }.forEach { b -> canvas.drawLine(x(b.timeMillis), bottom, x(b.timeMillis), y(b.insulinU!!), paint()) }
            if (index == 4) basal.intervals.forEach { interval -> canvas.drawRect(x(interval.start), y(interval.programmedRate), x(interval.end), bottom, paint(GlucosePalette.withAlpha(Brand.MUTED, 110))) }
            // Activity boundaries remain aligned across whole-day and zoomed views.
            for (t in listOf(workout.startMillis, workout.endMillis)) canvas.drawLine(x(t), top, x(t), bottom, paint(Brand.MUTED).apply { pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f) })
            cursor?.let { canvas.drawLine(x(it), top, x(it), bottom, paint(Brand.TEXT)) }
            canvas.restore()
            if (values.isEmpty() && index <= 2 || index == 3 && bolus.none { it.timeMillis in from until until } || index == 4 && basal.intervals.isEmpty())
                canvas.drawText(if (index == 3) "No recorded bolus" else "No observations", left + 5 * density, top + 20 * density, paint(Brand.MUTED))
            repeat(3) { tick ->
                val t = (leftTime + span * tick / 2).toLong(); val label = clock.format(Instant.ofEpochMilli(t)); val p = paint(Brand.MUTED, 9f)
                canvas.drawText(label, (x(t) - p.measureText(label) / 2).coerceIn(0f, (width - p.measureText(label)).coerceAtLeast(0f)), bottom + 16 * density, p)
            }
        }
    }
}
