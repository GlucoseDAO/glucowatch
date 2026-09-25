package io.github.antonkulaga.glucowatch.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import glucowatch.core.Treatment
import glucowatch.core.formatAmount
import io.github.antonkulaga.glucowatch.data.GlucoseState
import kotlin.math.max
import kotlin.math.min

/**
 * The glucose chart for the face and the app: a smooth line coloured by range over a faint
 * target band, the forecast as a dashed continuation, boluses as blue dots under the plot and
 * carbs as orange dots above it. The background is transparent, so it sits on any face.
 */
object ChartRenderer {
    const val COLOR_LOW = 0xFFF87171.toInt()
    const val COLOR_HIGH = 0xFFFBBF24.toInt()
    const val COLOR_IN_RANGE = 0xFF4ADE80.toInt()
    const val COLOR_INSULIN = 0xFF60A5FA.toInt()
    const val COLOR_CARBS = 0xFFFB923C.toInt()
    const val COLOR_FORECAST = 0xFFC4B5FD.toInt()
    private const val COLOR_BAND = 0x14FFFFFF
    private const val COLOR_GUIDE = 0x38FFFFFF
    private const val COLOR_GRID = 0x12FFFFFF
    private const val COLOR_LABEL = 0x8CFFFFFF.toInt()

    /** Readings further apart than this are not joined by the line. */
    private const val GAP_MS = 12 * 60_000L

    fun colorFor(mgdl: Double, state: GlucoseState): Int = when {
        mgdl < state.settings.lowMgdl -> COLOR_LOW
        mgdl > state.settings.highMgdl -> COLOR_HIGH
        else -> COLOR_IN_RANGE
    }

    fun render(state: GlucoseState, width: Int, height: Int, labels: Boolean = true, now: Long = System.currentTimeMillis()): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = state.settings
        val forecast = state.prediction?.points.orEmpty()

        val start = now - s.chartHours * 3_600_000L
        val end = now + if (forecast.isNotEmpty()) s.horizonMinutes * 60_000L else 10 * 60_000L
        val visible = state.readings.filter { it.timeMillis in start..end }
        val marks = state.treatments.filter { it.timeMillis in start..end }
        val boluses = marks.filter { it.insulin > 0 }
        val carbs = marks.filter { it.carbs > 0 }

        val values = visible.map { it.mgdl.toDouble() } + forecast.map { it.upper ?: it.mgdl } + forecast.map { it.lower ?: it.mgdl }
        val yMin = min(s.lowMgdl - 15.0, (values.minOrNull() ?: 60.0) - 12).coerceAtLeast(39.0)
        val yMax = max(s.highMgdl + 40.0, (values.maxOrNull() ?: 220.0) + 12).coerceAtMost(401.0)

        val textSize = height * 0.12f
        val stroke = max(2.5f, height / 60f)
        val dot = stroke * 1.25f
        val font = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_LABEL; this.textSize = textSize; typeface = font }

        // Rows: carbs above the plot, boluses and then the time axis below it. Empty rows take no space.
        val carbRow = if (carbs.isEmpty()) 0f else textSize * 1.25f
        val bolusRow = if (boluses.isEmpty()) 0f else textSize * 1.25f
        val axisRow = if (labels) textSize * 1.35f else 0f
        val left = if (labels) label.measureText(s.unit.format(s.highMgdl.toDouble())) + textSize * 0.5f else 2f
        val plot = RectF(left, carbRow + stroke * 2, width - stroke * 2.5f, height - axisRow - bolusRow - stroke)

        fun x(t: Long) = plot.left + (t - start).toFloat() / (end - start) * plot.width()
        fun y(v: Double) = plot.bottom - ((v - yMin) / (yMax - yMin)).toFloat() * plot.height()
        val yHigh = y(s.highMgdl.toDouble())
        val yLow = y(s.lowMgdl.toDouble())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Hour grid and labels.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_GRID; strokeWidth = max(1f, stroke / 2.5f) }
        label.textAlign = Paint.Align.CENTER
        for (h in s.chartHours downTo 1) {
            val gx = x(now - h * 3_600_000L)
            c.drawLine(gx, plot.top, gx, plot.bottom, grid)
            if (labels) c.drawText("-${h}h", gx, height - textSize * 0.3f, label)
        }

        // Target range: a faint band with thin dashed edges, labelled on the left.
        paint.color = COLOR_BAND
        c.drawRect(plot.left, yHigh, plot.right, yLow, paint)
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = COLOR_GUIDE; strokeWidth = max(1f, stroke / 2.5f)
            pathEffect = DashPathEffect(floatArrayOf(stroke * 1.5f, stroke * 1.5f), 0f)
        }
        c.drawLine(plot.left, yHigh, plot.right, yHigh, guide)
        c.drawLine(plot.left, yLow, plot.right, yLow, guide)
        if (labels) {
            label.textAlign = Paint.Align.RIGHT
            listOf(s.lowMgdl, s.highMgdl).forEach { v ->
                c.drawText(s.unit.format(v.toDouble()), plot.left - textSize * 0.35f, y(v.toDouble()) + textSize * 0.35f, label)
            }
        }

        // Forecast: a soft cone if the model gives one, then a dashed line from the latest reading.
        val last = visible.lastOrNull()
        if (forecast.isNotEmpty() && last != null) {
            val from = PointF(x(last.timeMillis), y(last.mgdl.toDouble()))
            if (forecast.all { it.lower != null && it.upper != null }) {
                val cone = Path().apply {
                    moveTo(from.x, from.y)
                    forecast.forEach { lineTo(x(it.timeMillis), y(it.upper!!)) }
                    forecast.asReversed().forEach { lineTo(x(it.timeMillis), y(it.lower!!)) }
                    close()
                }
                paint.color = COLOR_FORECAST and 0x00FFFFFF or 0x2E000000
                c.drawPath(cone, paint)
            }
            val line = smooth(listOf(from) + forecast.map { PointF(x(it.timeMillis), y(it.mgdl)) })
            c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = COLOR_FORECAST; strokeWidth = stroke * 0.85f; strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(stroke * 2.2f, stroke * 1.8f), 0f)
            })
        }

        // Readings: one smooth line per run without gaps, coloured by the band it passes through,
        // with a glow underneath that fades towards the bottom.
        val runs = mutableListOf(mutableListOf<PointF>())
        visible.forEachIndexed { i, r ->
            if (i > 0 && r.timeMillis - visible[i - 1].timeMillis > GAP_MS) runs += mutableListOf<PointF>()
            runs.last() += PointF(x(r.timeMillis), y(r.mgdl.toDouble()))
        }
        val bands = listOf(Triple(0f, yHigh, COLOR_HIGH), Triple(yHigh, yLow, COLOR_IN_RANGE), Triple(yLow, height.toFloat(), COLOR_LOW))
        val lineStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        runs.filter { it.isNotEmpty() }.forEach { run ->
            val line = smooth(run)
            val area = Path(line).apply { lineTo(run.last().x, plot.bottom); lineTo(run.first().x, plot.bottom); close() }
            for ((top, bottom, color) in bands) {
                c.save()
                c.clipRect(0f, top, width.toFloat(), bottom)
                paint.shader = LinearGradient(0f, plot.top, 0f, plot.bottom, color and 0x00FFFFFF or 0x40000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawPath(area, paint)
                paint.shader = null
                lineStroke.color = color
                if (run.size == 1) c.drawCircle(run[0].x, run[0].y, stroke * 0.8f, lineStroke) else c.drawPath(line, lineStroke)
                c.restore()
            }
        }

        // Latest reading: a dot with a halo in its range colour.
        if (last != null) {
            val cx = x(last.timeMillis)
            val cy = y(last.mgdl.toDouble())
            val color = colorFor(last.mgdl.toDouble(), state)
            paint.color = color and 0x00FFFFFF or 0x40000000
            c.drawCircle(cx, cy, stroke * 3.2f, paint)
            paint.color = 0xFF000000.toInt()
            c.drawCircle(cx, cy, stroke * 2.1f, paint)
            paint.color = color
            c.drawCircle(cx, cy, stroke * 1.5f, paint)
        }

        // Treatments. Labels that would overlap the previous one on the same row are skipped.
        val markLabel = Paint(label).apply { this.textSize = textSize * 0.95f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD }
        fun row(items: List<Treatment>, rowY: Float, color: Int, text: (Treatment) -> String?) {
            var free = Float.NEGATIVE_INFINITY
            items.sortedBy { text(it) != null }.forEach {
                val cx = x(it.timeMillis)
                val t = text(it)
                if (t == null) {
                    // Automatic boluses: a short faint tick, so they do not read as part of a label.
                    paint.color = color and 0x00FFFFFF or 0x99000000.toInt()
                    c.drawRoundRect(cx - stroke * 0.35f, rowY - dot, cx + stroke * 0.35f, rowY + dot, stroke, stroke, paint)
                    return@forEach
                }
                paint.color = color
                c.drawCircle(cx, rowY, dot, paint)
                val tx = cx + dot * 1.6f
                if (labels && tx >= free && tx + markLabel.measureText(t) <= width) {
                    // A black outline first, so ticks and grid lines never run into the text.
                    markLabel.style = Paint.Style.STROKE; markLabel.strokeWidth = textSize * 0.3f; markLabel.color = 0xFF000000.toInt()
                    c.drawText(t, tx, rowY + textSize * 0.34f, markLabel)
                    markLabel.style = Paint.Style.FILL; markLabel.color = color
                    c.drawText(t, tx, rowY + textSize * 0.34f, markLabel)
                    free = tx + markLabel.measureText(t) + dot * 2
                }
            }
        }
        if (carbs.isNotEmpty()) row(carbs, carbRow / 2 + stroke, COLOR_CARBS) { "${formatAmount(it.carbs, 0)}g" }
        if (boluses.isNotEmpty()) row(boluses, plot.bottom + stroke + bolusRow / 2, COLOR_INSULIN) { if (it.automatic) null else "${formatAmount(it.insulin, 1)}U" }

        if (visible.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            c.drawText("No readings", plot.centerX(), plot.centerY() + textSize * 0.35f, label)
        }
        return bmp
    }

    /** Catmull-Rom spline through [points], as cubic Béziers. */
    private fun smooth(points: List<PointF>): Path = Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points[0].x, points[0].y)
        for (i in 0 until points.size - 1) {
            val p0 = points[max(i - 1, 0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[min(i + 2, points.size - 1)]
            cubicTo(
                p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6,
                p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6,
                p2.x, p2.y,
            )
        }
    }
}
