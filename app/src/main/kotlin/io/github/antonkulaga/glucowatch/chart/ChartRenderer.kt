package io.github.antonkulaga.glucowatch.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import glucowatch.core.Treatment
import glucowatch.core.Trend
import glucowatch.core.formatAmount
import io.github.antonkulaga.glucowatch.data.GlucoseState
import io.github.antonkulaga.glucowatch.ui.Brand
import kotlin.math.max
import kotlin.math.min

/**
 * The glucose chart for the face, the tile and the app: a smooth line coloured by range over a
 * faint target band, the forecast as a dashed continuation, boluses as dots under the plot and
 * carbs as dots above it, all in GlucoseDAO colours (see [Brand]). The background is
 * transparent, so it sits on any face.
 */
object ChartRenderer {
    const val COLOR_LOW = Brand.LOW
    const val COLOR_HIGH = Brand.HIGH
    const val COLOR_IN_RANGE = Brand.GREEN
    const val COLOR_INSULIN = Brand.INSULIN
    const val COLOR_CARBS = Brand.CARBS
    const val COLOR_FORECAST = Brand.FORECAST

    /** Chart colors for the app, the glucose-only tile, and both glucose-first tiles. */
    class Palette(
        val low: Int, val high: Int, val inRange: Int, val insulin: Int, val carbs: Int, val forecast: Int,
        val band: Int, val guide: Int, val grid: Int, val label: Int, val background: Int,
    ) {
        companion object {
            val DARK = Palette(
                low = COLOR_LOW, high = COLOR_HIGH, inRange = COLOR_IN_RANGE,
                insulin = COLOR_INSULIN, carbs = COLOR_CARBS, forecast = COLOR_FORECAST,
                band = Brand.GREEN and 0x00FFFFFF or 0x1A000000,
                guide = 0x4DFFFFFF,
                grid = 0x12FFFFFF, label = 0x8CFFFFFF.toInt(), background = 0xFF000000.toInt(),
            )

            /** GlucoseDAO's poster colours as printed: they are made for a light background. */
            val LIGHT = Palette(
                low = Brand.LIGHT_LOW, high = Brand.LIGHT_HIGH, inRange = Brand.LIGHT_CARBS,
                insulin = Brand.LIGHT_INSULIN, carbs = Brand.LIGHT_CARBS, forecast = Brand.LIGHT_FORECAST,
                band = Brand.LIGHT_CARBS and 0x00FFFFFF or 0x1A000000,
                guide = Brand.LIGHT_TEXT and 0x00FFFFFF or 0x66000000,
                grid = Brand.LIGHT_TEXT and 0x00FFFFFF or 0x1A000000, label = Brand.LIGHT_TEXT and 0x00FFFFFF or 0xB3000000.toInt(),
                background = Brand.LIGHT_BACKGROUND,
            )

            /** Black and gray framing with glucose-state colors for the glucose-all tile. */
            val GLUCOSE_ALL = Palette(
                low = Brand.LOW, high = Brand.LOW, inRange = Brand.CARBS,
                insulin = 0xFF898D8D.toInt(), carbs = 0xFF898D8D.toInt(), forecast = 0xFF898D8D.toInt(),
                band = 0xFF1A1C1C.toInt(), guide = 0xFF5C6161.toInt(), grid = 0xFF292D2D.toInt(),
                label = 0xFF858989.toInt(), background = 0xFF000000.toInt(),
            )

            /** The same clean range chart on an off-white surface, with darker state colors. */
            val GLUCOSE_LIGHT = Palette(
                low = Brand.LIGHT_LOW, high = Brand.LIGHT_LOW, inRange = Brand.LIGHT_CARBS,
                insulin = 0xFF777E7B.toInt(), carbs = 0xFF777E7B.toInt(), forecast = 0xFF777E7B.toInt(),
                band = 0xFFE8EBE8.toInt(), guide = 0xFFAEB6B0.toInt(), grid = 0xFFE0E4E0.toInt(),
                label = 0xFF68716C.toInt(), background = Brand.LIGHT_BACKGROUND,
            )
        }
    }

    /** Readings further apart than this are not joined by the line. */
    private const val GAP_MS = 12 * 60_000L

    fun colorFor(mgdl: Double, state: GlucoseState, palette: Palette = Palette.DARK): Int = when {
        mgdl < state.settings.lowMgdl -> palette.low
        mgdl > state.settings.highMgdl -> palette.high
        else -> palette.inRange
    }

    /** Glance color: green in range, yellow near a limit, red beyond it or on a fastest trend. */
    fun glanceColorFor(mgdl: Double, state: GlucoseState, trend: Trend, palette: Palette = Palette.GLUCOSE_ALL): Int {
        val low = state.settings.lowMgdl.toDouble()
        val high = state.settings.highMgdl.toDouble()
        if (mgdl < low || mgdl > high || trend == Trend.DoubleUp || trend == Trend.DoubleDown) return palette.low
        val near = min(20.0, (high - low) / 6.0)
        return if (mgdl <= low + near || mgdl >= high - near) {
            if (palette === Palette.GLUCOSE_LIGHT) Brand.LIGHT_HIGH else Brand.HIGH
        } else palette.inRange
    }

    /**
     * [edge]: the chart spans a round screen from rim to rim, as on the face. The plot then fills
     * the whole width, labels sit inside it, and nothing that matters (labels, the latest reading,
     * the forecast) comes closer than 7% to the sides, where the circle cuts the corners off.
     */
    fun render(
        state: GlucoseState, width: Int, height: Int, labels: Boolean = true, edge: Boolean = false,
        palette: Palette = Palette.DARK, now: Long = System.currentTimeMillis(),
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = state.settings
        val forecast = state.prediction?.points.orEmpty()
        val glanceStyle = palette === Palette.GLUCOSE_ALL || palette === Palette.GLUCOSE_LIGHT
        val inset = if (edge) width * 0.07f else 0f

        val start = now - s.chartHours * 3_600_000L
        val end = now + if (forecast.isNotEmpty()) s.horizonMinutes * 60_000L else 10 * 60_000L
        val visible = state.readings.filter { it.timeMillis in start..end }
        // The glucose-first tiles reserve this short chart for the trajectory and target band.
        val marks = if (glanceStyle) emptyList() else state.treatments.filter { it.timeMillis in start..end }
        val boluses = marks.filter { it.insulin > 0 }
        val carbs = marks.filter { it.carbs > 0 }

        val values = visible.map { it.mgdl.toDouble() } + forecast.map { it.upper ?: it.mgdl } + forecast.map { it.lower ?: it.mgdl }
        val yMin = min(s.lowMgdl - 15.0, (values.minOrNull() ?: 60.0) - 12).coerceAtLeast(39.0)
        val yMax = max(s.highMgdl + 40.0, (values.maxOrNull() ?: 220.0) + 12).coerceAtMost(401.0)

        val textSize = height * 0.12f
        val stroke = max(2.5f, height / 60f)
        val dot = stroke * 1.25f
        val font = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.label; this.textSize = textSize; typeface = font }

        // Rows: carbs above the plot, boluses and then the time axis below it. Empty rows take no space.
        val carbRow = if (carbs.isEmpty()) 0f else textSize * 1.25f
        val bolusRow = if (boluses.isEmpty()) 0f else textSize * 1.25f
        val axisRow = if (labels) textSize * 1.35f else 0f
        val left = when {
            edge -> 0f
            labels -> label.measureText(s.unit.format(s.highMgdl.toDouble())) + textSize * 0.5f
            else -> 2f
        }
        val right = if (edge) width - inset else width - stroke * 2.5f
        val plot = RectF(left, carbRow + stroke * 2, right, height - axisRow - bolusRow - stroke)

        fun x(t: Long) = plot.left + (t - start).toFloat() / (end - start) * plot.width()
        fun y(v: Double) = plot.bottom - ((v - yMin) / (yMax - yMin)).toFloat() * plot.height()
        val yHigh = y(s.highMgdl.toDouble())
        val yLow = y(s.lowMgdl.toDouble())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Hour grid and labels.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.grid; strokeWidth = max(1f, stroke / 2.5f) }
        label.textAlign = Paint.Align.CENTER
        for (h in s.chartHours downTo 1) {
            val gx = x(now - h * 3_600_000L)
            c.drawLine(gx, plot.top, gx, plot.bottom, grid)
            val half = label.measureText("-${h}h") / 2
            if (labels && gx - half >= inset && gx + half <= width - inset) c.drawText("-${h}h", gx, height - textSize * 0.3f, label)
        }

        // Target range: a faint band with thin dashed edges, labelled on the left.
        paint.color = palette.band
        c.drawRect(plot.left, yHigh, plot.right, yLow, paint)
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = palette.guide; strokeWidth = max(1f, stroke / 2.5f)
            pathEffect = DashPathEffect(floatArrayOf(stroke * 1.5f, stroke * 1.5f), 0f)
        }
        c.drawLine(plot.left, yHigh, plot.right, yHigh, guide)
        c.drawLine(plot.left, yLow, plot.right, yLow, guide)
        if (labels && edge) {
            // Inside the plot, just above each guide, on a black outline so the line can pass behind.
            label.textAlign = Paint.Align.LEFT
            listOf(s.lowMgdl, s.highMgdl).forEach { v ->
                outlined(c, s.unit.format(v.toDouble()), inset, y(v.toDouble()) - textSize * 0.3f, label, palette.label, palette.background)
            }
        } else if (labels) {
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
                paint.color = palette.forecast and 0x00FFFFFF or 0x2E000000
                c.drawPath(cone, paint)
            }
            val line = smooth(listOf(from) + forecast.map { PointF(x(it.timeMillis), y(it.mgdl)) })
            c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = palette.forecast; strokeWidth = stroke * 0.85f; strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(stroke * 2.2f, stroke * 1.8f), 0f)
            })
        }

        // Readings: one smooth line per run without gaps, coloured by the band it passes through.
        // The glow under it takes the colour of the line above each column and fades downwards,
        // so a high line over the target band does not paint the band green.
        val runs = mutableListOf(mutableListOf<PointF>())
        visible.forEachIndexed { i, r ->
            if (i > 0 && r.timeMillis - visible[i - 1].timeMillis > GAP_MS) runs += mutableListOf<PointF>()
            runs.last() += PointF(x(r.timeMillis), y(r.mgdl.toDouble()))
        }
        val bands = listOf(Triple(0f, yHigh, palette.high), Triple(yHigh, yLow, palette.inRange), Triple(yLow, height.toFloat(), palette.low))
        fun bandColor(py: Float) = bands.first { py < it.second || it === bands.last() }.third
        val lineStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        runs.filter { it.isNotEmpty() }.forEach { run ->
            val line = smooth(run)
            if (run.size > 1 && !glanceStyle) {
                val area = Path(line).apply { lineTo(run.last().x, plot.bottom); lineTo(run.first().x, plot.bottom); close() }
                paint.shader = ComposeShader(
                    glowColors(run, yHigh, yLow, ::bandColor),
                    LinearGradient(0f, run.minOf { it.y }, 0f, plot.bottom, 0xFF000000.toInt(), 0, Shader.TileMode.CLAMP),
                    PorterDuff.Mode.DST_IN,
                )
                c.drawPath(area, paint)
                paint.shader = null
            }
            if (glanceStyle) {
                val near = min(20.0, (s.highMgdl - s.lowMgdl) / 6.0)
                val positions = floatArrayOf(
                    0f,
                    (yHigh - plot.top) / plot.height(),
                    (y(s.highMgdl - near) - plot.top) / plot.height(),
                    (y(s.highMgdl - near * 2) - plot.top) / plot.height(),
                    (y(s.lowMgdl + near * 2) - plot.top) / plot.height(),
                    (y(s.lowMgdl + near) - plot.top) / plot.height(),
                    (yLow - plot.top) / plot.height(),
                    1f,
                )
                lineStroke.shader = LinearGradient(
                    0f, plot.top, 0f, plot.bottom,
                    intArrayOf(
                        palette.low, palette.low,
                        if (palette === Palette.GLUCOSE_LIGHT) Brand.LIGHT_HIGH else Brand.HIGH,
                        palette.inRange, palette.inRange,
                        if (palette === Palette.GLUCOSE_LIGHT) Brand.LIGHT_HIGH else Brand.HIGH,
                        palette.low, palette.low,
                    ),
                    positions, Shader.TileMode.CLAMP,
                )
                if (run.size == 1) c.drawCircle(run[0].x, run[0].y, stroke * 0.8f, lineStroke) else c.drawPath(line, lineStroke)
                lineStroke.shader = null
            } else {
                for ((top, bottom, color) in bands) {
                    c.save()
                    c.clipRect(0f, top, width.toFloat(), bottom)
                    lineStroke.color = color
                    if (run.size == 1) c.drawCircle(run[0].x, run[0].y, stroke * 0.8f, lineStroke) else c.drawPath(line, lineStroke)
                    c.restore()
                }
            }
        }

        if (glanceStyle && last != null && glanceColorFor(last.mgdl.toDouble(), state, last.trend, palette) == palette.low) {
            val tail = runs.lastOrNull().orEmpty()
            if (tail.size >= 2) {
                lineStroke.color = palette.low
                c.drawLine(tail[tail.size - 2].x, tail[tail.size - 2].y, tail.last().x, tail.last().y, lineStroke)
            }
        }

        // The glucose-first tiles turn recent samples and the forecast into a small,
        // functional connected-circle mark inspired by the app logo.
        if (glanceStyle && last != null) {
            val previous = runs.lastOrNull().orEmpty().dropLast(1).asReversed()
            var rightmost = x(last.timeMillis)
            var shown = 0
            val node = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = if (palette === Palette.GLUCOSE_LIGHT) 0xFF757D78.toInt() else 0xFFB8BAB9.toInt()
                strokeWidth = stroke * 0.8f
            }
            for (point in previous) {
                if (rightmost - point.x < stroke * 7) continue
                c.drawCircle(point.x, point.y, stroke * 2f, node)
                rightmost = point.x
                if (++shown == 2) break
            }
            forecast.lastOrNull()?.let {
                c.drawCircle(x(it.timeMillis), y(it.mgdl), stroke * 2.1f, node)
            }
        }

        // Latest reading: a dot with a halo in its range colour.
        if (last != null) {
            val cx = x(last.timeMillis)
            val cy = y(last.mgdl.toDouble())
            val color = if (glanceStyle) glanceColorFor(last.mgdl.toDouble(), state, last.trend, palette) else colorFor(last.mgdl.toDouble(), state, palette)
            if (!glanceStyle) {
                paint.color = color and 0x00FFFFFF or 0x40000000
                c.drawCircle(cx, cy, stroke * 3.2f, paint)
                paint.color = palette.background
                c.drawCircle(cx, cy, stroke * 2.1f, paint)
            }
            paint.color = color
            c.drawCircle(cx, cy, stroke * if (glanceStyle) 2.5f else 1.5f, paint)
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
                if (labels && tx >= free && tx >= inset && tx + markLabel.measureText(t) <= width - inset) {
                    outlined(c, t, tx, rowY + textSize * 0.34f, markLabel, color, palette.background)
                    free = tx + markLabel.measureText(t) + dot * 2
                }
            }
        }
        if (carbs.isNotEmpty()) row(carbs, carbRow / 2 + stroke, palette.carbs) { "${formatAmount(it.carbs, 0)}g" }
        if (boluses.isNotEmpty()) row(boluses, plot.bottom + stroke + bolusRow / 2, palette.insulin) { if (it.automatic) null else "${formatAmount(it.insulin, 1)}U" }

        if (visible.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            c.drawText("No readings", plot.centerX(), plot.centerY() + textSize * 0.35f, label)
        }
        return bmp
    }

    /**
     * A horizontal gradient with hard stops where the polyline through [run] crosses [yHigh] or
     * [yLow], so every column gets the colour of the line above it. Alpha 25%.
     */
    private fun glowColors(run: List<PointF>, yHigh: Float, yLow: Float, bandColor: (Float) -> Int): Shader {
        val x0 = run.first().x
        val span = max(run.last().x - x0, 1f)
        val colors = mutableListOf<Int>()
        val stops = mutableListOf<Float>()
        fun stop(x: Float, color: Int) { colors += color and 0x00FFFFFF or 0x40000000; stops += ((x - x0) / span).coerceIn(0f, 1f) }
        stop(x0, bandColor(run.first().y))
        for (i in 0 until run.size - 1) {
            val a = run[i]
            val b = run[i + 1]
            // A segment can cross both guides (low to high in one step): handle them in x order.
            val towardsB = if (b.y > a.y) 0.5f else -0.5f
            listOf(yHigh, yLow)
                .filter { (a.y - it) * (b.y - it) < 0 }
                .map { it to a.x + (b.x - a.x) * (it - a.y) / (b.y - a.y) }
                .sortedBy { it.second }
                .forEach { (guide, xc) ->
                    stop(xc, colors.last())
                    stop(xc, bandColor(guide + towardsB))
                }
        }
        stop(run.last().x, bandColor(run.last().y))
        return LinearGradient(x0, 0f, x0 + span, 0f, colors.toIntArray(), stops.toFloatArray(), Shader.TileMode.CLAMP)
    }

    /** [text] with an outline in the [background] colour first, so lines and ticks never run into it. */
    private fun outlined(c: Canvas, text: String, x: Float, y: Float, paint: Paint, color: Int, background: Int) {
        val style = paint.style
        val strokeWidth = paint.strokeWidth
        paint.style = Paint.Style.STROKE; paint.strokeWidth = paint.textSize * 0.3f; paint.color = background
        c.drawText(text, x, y, paint)
        paint.style = Paint.Style.FILL; paint.color = color
        c.drawText(text, x, y, paint)
        paint.style = style; paint.strokeWidth = strokeWidth
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
