package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import glucowatch.core.GlucoseReading
import glucowatch.core.GlucoseUnit
import glucowatch.core.HeartSample
import glucowatch.core.PredictedPoint
import glucowatch.core.Prediction
import glucowatch.core.Treatment
import glucowatch.core.formatAmount
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The glucose timeline, drawn the way the GlucoseDAO logo draws a glucose molecule: ball and
 * stick. Readings are atoms — a coloured ball with a dark core — strung on bonds and coloured by
 * where each sits. Around them: the target band, the model's forecast, insulin hanging from the
 * top, carbs and logged meals on the chain, and optionally a heart-rate track.
 *
 * It follows "now" until the user drags it. Drag to go back through history, fling to travel
 * further, pinch to change how much time is shown, tap to read one point, double-tap to come back.
 * Plain Canvas on a plain View: no chart library, no bundled assets.
 */
class GlucoseChartView(context: Context) : View(context) {
    var readings: List<GlucoseReading> = emptyList()
        set(value) { field = value; invalidate() }
    var forecast: Prediction? = null
        set(value) { field = value; invalidate() }
    var treatments: List<Treatment> = emptyList()
        set(value) { field = value; invalidate() }
    var heart: List<HeartSample> = emptyList()
        set(value) { field = value; invalidate() }
    var showHeart: Boolean = false
        set(value) { field = value; invalidate() }
    var food: List<FoodEntry> = emptyList()
        set(value) {
            field = value
            thumbnails.keys.retainAll(value.mapNotNull(FoodEntry::photo).toSet())
            invalidate()
        }
    var unit: GlucoseUnit = GlucoseUnit.MMOL
        set(value) { field = value; invalidate() }

    /** Where to find a logged meal's photo; the activity supplies it so the view stays passive. */
    var photoLoader: (String) -> Bitmap? = { null }

    /** Told whenever the window moves, so the screen can show "Now" and the right period. */
    var onWindowChanged: (live: Boolean, hours: Double) -> Unit = { _, _ -> }

    /** How much time the chart shows. Pinch and the period buttons change it. */
    var hours: Double
        get() = windowMs / 3_600_000.0
        set(value) {
            windowMs = (value * 3_600_000).roundToLong().coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
            clampEnd()
            changed()
        }

    /** Whether the chart is following the latest reading, rather than showing history. */
    val live get() = endMs == null

    private var windowMs = 6 * 3_600_000L
    /** The right edge of the window, or null to follow now. */
    private var endMs: Long? = null
    private var inspectMs: Long? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val weekday = SimpleDateFormat("EEE", Locale.getDefault())
    private val dayLabel = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val thumbnails = mutableMapOf<String, Bitmap?>()
    private val source = Rect()
    private val scroller = OverScroller(context)
    private var plotLeft = 0f
    private var plotRight = 1f

    private fun dp(n: Float) = n * resources.displayMetrics.density

    /** Returns to the latest reading, as a double-tap does. */
    fun goLive() {
        scroller.forceFinished(true)
        endMs = null
        inspectMs = null
        changed()
    }

    private fun changed() {
        invalidate()
        onWindowChanged(live, hours)
    }

    private val msPerPixel get() = windowMs / (plotRight - plotLeft).coerceAtLeast(1f)
    private val earliest get() = (readings.firstOrNull()?.timeMillis ?: System.currentTimeMillis()) + windowMs

    /** Keeps the window inside the data: not before the oldest reading, and snapping to live at now. */
    private fun clampEnd() {
        val end = endMs ?: return
        // No further back than the oldest reading; with less than a window of data, nowhere to go.
        val target = maxOf(end, earliest)
        endMs = if (target >= System.currentTimeMillis() - LIVE_SNAP_MS) null else target
    }

    private fun panBy(deltaMs: Long) {
        val now = System.currentTimeMillis()
        endMs = (endMs ?: now) + deltaMs
        inspectMs = null
        clampEnd()
        changed()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // A mostly sideways drag belongs to the chart; a mostly vertical one to the page.
            if (abs(distanceX) <= abs(distanceY)) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            panBy((distanceX * msPerPixel).roundToLong())
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityX) <= abs(velocityY)) return false
            val now = System.currentTimeMillis()
            val span = ((now - earliest) / msPerPixel).toInt().coerceAtLeast(0)
            val position = (((endMs ?: now) - earliest) / msPerPixel).toInt().coerceIn(0, span)
            scroller.fling(position, 0, -velocityX.toInt(), 0, 0, span, 0, 0)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val at = timeAt(e.x)
            inspectMs = if (inspectMs != null && abs(inspectMs!! - at) < 15 * msPerPixel) null else at
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            goLive()
            return true
        }
    })

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Zoom around the fingers, so the moment under them stays put.
            val anchor = timeAt(detector.focusX)
            val before = windowMs
            windowMs = (windowMs / detector.scaleFactor).toLong().coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
            if (windowMs != before) {
                val now = System.currentTimeMillis()
                val end = endMs ?: now
                val fromRight = (end - anchor).toDouble() / before
                endMs = anchor + (fromRight * windowMs).toLong()
                clampEnd()
                changed()
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        endMs = earliest + (scroller.currX * msPerPixel).toLong()
        clampEnd()
        if (endMs == null) scroller.forceFinished(true)
        changed()
        postInvalidateOnAnimation()
    }

    private fun windowEnd(now: Long) = endMs ?: now
    private fun timeAt(x: Float): Long {
        val now = System.currentTimeMillis()
        val end = plotEnd(now)
        val start = windowEnd(now) - windowMs
        return start + ((x - plotLeft) / (plotRight - plotLeft) * (end - start)).toLong()
    }

    /** Following now, the plot runs on past it to fit the forecast; in history it ends at the window. */
    private fun plotEnd(now: Long) =
        if (live) maxOf(now, forecast?.points?.lastOrNull()?.timeMillis ?: now) else windowEnd(now)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The chart is what this screen is for, so it takes the height the screen can spare.
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(330f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Tight gutters: just enough for the axis labels on the left and the end label on the right.
        plotLeft = dp(26f)
        plotRight = width - dp(20f)
        val left = plotLeft
        val right = plotRight
        val top = dp(22f)
        val bottom = height - dp(22f)
        if (right <= left || bottom <= top) return

        val now = System.currentTimeMillis()
        val start = windowEnd(now) - windowMs
        val end = plotEnd(now)
        val samples = readings.filter { it.timeMillis in start..end }
        val points = if (live) forecast?.points.orEmpty() else emptyList()
        val values = samples.map { it.mgdl.toDouble() } + points.map { it.mgdl }
        // Always show the 70–180 band with headroom, then fit the rest to the data rather than
        // reserving a fixed window that leaves most of the height empty.
        val min = minOf(MIN_MGDL, (values.minOrNull() ?: MIN_MGDL) - 12).coerceAtLeast(30.0)
        val max = maxOf(MAX_MGDL, (values.maxOrNull() ?: MAX_MGDL) + 15).coerceAtMost(420.0)
        fun x(t: Long) = left + (t - start).toFloat() / (end - start).coerceAtLeast(1L) * (right - left)
        fun y(v: Double) = bottom - ((v - min) / (max - min)).toFloat() * (bottom - top)

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        drawBand(canvas, left, right, ::y)
        drawGrid(canvas, left, right, top, bottom, start, end, ::x)
        drawLevels(canvas, left, right, min, max, ::y)
        if (showHeart) drawHeart(canvas, left, right, top, bottom, start, end, ::x)
        drawFood(canvas, left, right, bottom, start, end, ::x, ::y)
        if (samples.size >= 2) drawChain(canvas, samples, left, right, ::x, ::y)
        else drawEmpty(canvas, left, top, bottom)
        drawCarbs(canvas, start, end, ::x, ::y)
        drawInsulin(canvas, left, right, top, start, end, ::x)
        drawForecast(canvas, samples.lastOrNull(), points, ::x, ::y)
        if (live) drawLatest(canvas, samples.lastOrNull(), ::x, ::y)
        drawClock(canvas, start, end, ::x)
        if (!live) drawDate(canvas, windowEnd(now))
        inspectMs?.let { drawInspect(canvas, it, top, bottom, start, end, ::x, ::y) }
        canvas.restore()
    }

    private fun drawBand(canvas: Canvas, left: Float, right: Float, y: (Double) -> Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = BAND
        canvas.drawRect(left, y(Brand.TARGET_HIGH.toDouble()), right, y(Brand.TARGET_LOW.toDouble()), paint)
    }

    private fun drawGrid(
        canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float,
        start: Long, end: Long, x: (Long) -> Float,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(6f)), 0f)
        marks(start, end).forEach { mark ->
            val at = x(mark)
            if (at <= left || at >= right) return@forEach
            // Midnight gets a stronger line, so a day boundary stands out when dragging back.
            paint.color = if (isMidnight(mark)) MIDNIGHT else GRID
            canvas.drawLine(at, top, at, bottom, paint)
        }
        paint.pathEffect = null
    }

    /** Evenly spaced time marks for the current zoom, thinned so their labels never collide. */
    private fun marks(start: Long, end: Long): List<Long> {
        val span = end - start
        val step = when {
            span <= 2 * 3_600_000L -> 30 * 60_000L
            span <= 8 * 3_600_000L -> 3_600_000L
            span <= 14 * 3_600_000L -> 2 * 3_600_000L
            span <= 26 * 3_600_000L -> 3 * 3_600_000L
            else -> 6 * 3_600_000L
        }
        // Align to local time, so marks fall on 12:00 and 15:00 rather than on UTC hours.
        val offset = java.util.TimeZone.getDefault().getOffset(start).toLong()
        val first = ceil((start + offset).toDouble() / step).toLong() * step - offset
        return generateSequence(first) { it + step }.takeWhile { it <= end }.take(40).toList()
    }

    private fun isMidnight(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
        .let { it.get(Calendar.HOUR_OF_DAY) == 0 && it.get(Calendar.MINUTE) == 0 }

    private fun drawLevels(canvas: Canvas, left: Float, right: Float, min: Double, max: Double, y: (Double) -> Float) {
        val levels = (if (unit == GlucoseUnit.MMOL) MMOL_LEVELS.map { it * GlucoseUnit.MGDL_PER_MMOL } else MGDL_LEVELS)
            .filter { it in min..max }
        (levels + listOf(Brand.TARGET_LOW.toDouble(), Brand.TARGET_HIGH.toDouble())).distinct().forEach { level ->
            val target = level == Brand.TARGET_LOW.toDouble() || level == Brand.TARGET_HIGH.toDouble()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = if (target) TARGET_LINE else GRID
            paint.pathEffect = if (target) DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f) else null
            canvas.drawLine(left, y(level), right, y(level), paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = if (target) Brand.TEXT else Brand.MUTED
            paint.textSize = dp(if (target) 10f else 9f)
            val text = unit.format(level)
            canvas.drawText(text, left - dp(4f) - paint.measureText(text), y(level) + dp(3.5f), paint)
        }
    }

    /**
     * Bonds first, then the atoms on top, as a ball-and-stick drawing is built up. Atoms are
     * drawn only while they would stay apart; zoomed out, the chain reads as a line.
     */
    private fun drawChain(
        canvas: Canvas, samples: List<GlucoseReading>,
        left: Float, right: Float, x: (Long) -> Float, y: (Double) -> Float,
    ) {
        paint.pathEffect = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.6f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        samples.zipWithNext().forEach { (from, to) ->
            // A gap means the sensor dropped out; leaving it open says so more honestly than a line.
            if (to.timeMillis - from.timeMillis > GAP_MS) return@forEach
            paint.color = Brand.glucoseColor(to.mgdl)
            canvas.drawLine(x(from.timeMillis), y(from.mgdl.toDouble()), x(to.timeMillis), y(to.mgdl.toDouble()), paint)
        }
        val spacing = (right - left) / (samples.size - 1).coerceAtLeast(1)
        if (spacing < dp(9f)) return
        samples.forEach { atom(canvas, x(it.timeMillis), y(it.mgdl.toDouble()), Brand.glucoseColor(it.mgdl), dp(3.4f)) }
    }

    /** One ball-and-stick atom: a coloured ball with a dark core, as in the GlucoseDAO logo. */
    private fun atom(canvas: Canvas, at: Float, level: Float, color: Int, radius: Float) {
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(at, level, radius, paint)
        paint.color = Brand.BACKGROUND
        canvas.drawCircle(at, level, radius * 0.42f, paint)
    }

    private fun drawEmpty(canvas: Canvas, left: Float, top: Float, bottom: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Brand.MUTED
        paint.textSize = dp(13f)
        canvas.drawText("No readings in this window", left + dp(10f), (top + bottom) / 2, paint)
    }

    /**
     * Heart rate as a thin second track across the upper part of the plot, on its own bpm scale:
     * glucose keeps the full height and the heart never competes with it for attention.
     */
    private fun drawHeart(
        canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float,
        start: Long, end: Long, x: (Long) -> Float,
    ) {
        val window = heart.filter { it.timeMillis in start..end }
        if (window.size < 2) return
        val low = window.minOf { it.bpm }
        val high = window.maxOf { it.bpm }.coerceAtLeast(low + 10)
        val bandTop = top + dp(16f)
        val bandBottom = top + (bottom - top) * 0.34f
        fun y(bpm: Int) = bandBottom - (bpm - low).toFloat() / (high - low) * (bandBottom - bandTop)
        val path = Path()
        var previous: HeartSample? = null
        window.forEach { sample ->
            val p = previous
            if (p == null || sample.timeMillis - p.timeMillis > HEART_GAP_MS) path.moveTo(x(sample.timeMillis), y(sample.bpm))
            else path.lineTo(x(sample.timeMillis), y(sample.bpm))
            previous = sample
        }
        paint.pathEffect = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.4f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Brand.HEART and 0xB0FFFFFF.toInt()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = dp(9f)
        paint.color = Brand.HEART
        canvas.drawText("♥ $low–$high", left + dp(4f), bandTop - dp(2f), paint)
    }

    /**
     * Insulin hangs from the top edge: a white atom and its units for a bolus the user gave, a
     * small grey atom for one the loop gave on its own. Carbs stay on the curve below.
     */
    private fun drawInsulin(canvas: Canvas, left: Float, right: Float, top: Float, start: Long, end: Long, x: (Long) -> Float) {
        val row = top - dp(9f)
        var lastLabelEnd = Float.NEGATIVE_INFINITY
        treatments.filter { it.insulin > 0 && it.timeMillis in start..end }.forEach { dose ->
            val at = x(dose.timeMillis)
            if (at < left || at > right) return@forEach
            if (dose.automatic) {
                atom(canvas, at, row, Brand.MUTED, dp(2.6f))
                return@forEach
            }
            atom(canvas, at, row, Brand.INSULIN, dp(4f))
            paint.style = Paint.Style.FILL
            paint.textSize = dp(10f)
            paint.color = Brand.INSULIN
            val label = "${formatAmount(dose.insulin, 1)} U"
            val labelX = at + dp(6f)
            // Boluses close together keep their atoms but not overlapping labels.
            if (labelX > lastLabelEnd) {
                canvas.drawText(label, labelX, row + dp(3.5f), paint)
                lastLabelEnd = labelX + paint.measureText(label) + dp(4f)
            }
        }
    }

    /** Carbs the loop or Careportal recorded: a green atom on the curve with the grams. */
    private fun drawCarbs(canvas: Canvas, start: Long, end: Long, x: (Long) -> Float, y: (Double) -> Float) {
        treatments.filter { it.carbs > 0 && it.timeMillis in start..end }.forEach { meal ->
            // A meal logged on this phone at the same moment is drawn with its photo instead.
            if (food.any { abs(it.timeMillis - meal.timeMillis) < 10 * 60_000L }) return@forEach
            val at = x(meal.timeMillis)
            val level = y(valueAt(meal.timeMillis) ?: Brand.TARGET_LOW.toDouble())
            atom(canvas, at, level, Brand.CARBS, dp(4.2f))
            paint.style = Paint.Style.FILL
            paint.textSize = dp(10f)
            paint.color = Brand.CARBS
            val label = "${formatAmount(meal.carbs, 0)} g"
            canvas.drawText(label, (at + dp(7f)).coerceAtMost(width - paint.measureText(label) - dp(1f)), level + dp(14f), paint)
        }
    }

    private fun drawForecast(
        canvas: Canvas, last: GlucoseReading?, points: List<PredictedPoint>,
        x: (Long) -> Float, y: (Double) -> Float,
    ) {
        if (last == null || points.isEmpty()) return
        val path = Path().apply { moveTo(x(last.timeMillis), y(last.mgdl.toDouble())) }
        points.forEach { path.lineTo(x(it.timeMillis), y(it.mgdl)) }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Brand.FORECAST
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        val target = points.last()
        paint.style = Paint.Style.FILL
        paint.textSize = dp(11f)
        val text = unit.format(target.mgdl)
        val at = (x(target.timeMillis) + dp(4f)).coerceAtMost(width - paint.measureText(text) - dp(1f))
        canvas.drawText(text, at, (y(target.mgdl) - dp(7f)).coerceAtLeast(dp(11f)), paint)
    }

    /** The newest reading is the atom the eye should land on: bigger, with a halo around it. */
    private fun drawLatest(canvas: Canvas, last: GlucoseReading?, x: (Long) -> Float, y: (Double) -> Float) {
        if (last == null) return
        val at = x(last.timeMillis)
        val level = y(last.mgdl.toDouble())
        val color = Brand.glucoseColor(last.mgdl)
        paint.pathEffect = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.4f)
        paint.color = color and 0x55FFFFFF
        canvas.drawCircle(at, level, dp(9f), paint)
        atom(canvas, at, level, color, dp(6f))
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = dp(13f)
        val text = unit.format(last.mgdl.toDouble())
        canvas.drawText(text, (at + dp(11f)).coerceAtMost(width - paint.measureText(text) - dp(1f)), level - dp(10f), paint)
    }

    /**
     * Meals logged on the phone: an atom on the curve, a bond down to the photo, and the carbs.
     * Photos are drawn from the app's private storage and never leave it.
     */
    private fun drawFood(
        canvas: Canvas, left: Float, right: Float, bottom: Float,
        start: Long, end: Long, x: (Long) -> Float, y: (Double) -> Float,
    ) {
        val visible = food.filter { it.timeMillis in start..end }.takeLast(MAX_FOOD)
        if (visible.isEmpty()) return
        val size = dp(40f)
        val row = bottom - size - dp(4f)
        visible.forEach { entry ->
            val at = x(entry.timeMillis).coerceIn(left + size / 2, right - size / 2)
            val level = y(valueAt(entry.timeMillis) ?: Brand.TARGET_LOW.toDouble())
            paint.pathEffect = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.4f)
            paint.color = Brand.CARBS and 0x88FFFFFF.toInt()
            canvas.drawLine(at, level + dp(5f), at, row, paint)
            atom(canvas, at, level, Brand.CARBS, dp(4.2f))
            paint.style = Paint.Style.FILL
            paint.textSize = dp(11f)
            paint.color = Brand.TEXT
            val label = "${formatAmount(entry.carbs, 0)} g"
            canvas.drawText(label, (at + dp(8f)).coerceAtMost(width - paint.measureText(label) - dp(1f)), level + dp(4f), paint)
            val photo = entry.photo?.let { name -> thumbnails.getOrPut(name) { photoLoader(name) } } ?: return@forEach
            val box = RectF(at - size / 2, row, at + size / 2, row + size)
            source.set(0, 0, photo.width, photo.height)
            canvas.drawBitmap(photo, source, box, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = Brand.TEXT
            canvas.drawRoundRect(box, dp(4f), dp(4f), paint)
            paint.style = Paint.Style.FILL
        }
    }

    /** The reading nearest [timeMillis] within 10 minutes, so a meal atom sits on the chain. */
    private fun valueAt(timeMillis: Long): Double? = nearest(timeMillis, 10 * 60_000L)?.mgdl?.toDouble()

    private fun nearest(timeMillis: Long, within: Long): GlucoseReading? = readings
        .minByOrNull { abs(it.timeMillis - timeMillis) }
        ?.takeIf { abs(it.timeMillis - timeMillis) <= within }

    private fun drawClock(canvas: Canvas, start: Long, end: Long, x: (Long) -> Float) {
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.textSize = dp(10f)
        marks(start, end).forEach { mark ->
            // At midnight, name the day: dragged back, that is what tells one day from another.
            val midnight = isMidnight(mark)
            val text = if (midnight) weekday.format(Date(mark)) else clock.format(Date(mark))
            paint.color = if (midnight) Brand.TEXT else Brand.MUTED
            val at = x(mark) - paint.measureText(text) / 2
            if (at >= 0 && at + paint.measureText(text) <= width) canvas.drawText(text, at, height - dp(6f), paint)
        }
    }

    /** Which day the window is on, when it is not today's live view. */
    private fun drawDate(canvas: Canvas, end: Long) {
        paint.style = Paint.Style.FILL
        paint.textSize = dp(11f)
        paint.color = Brand.TEXT
        val text = dayLabel.format(Date(end))
        canvas.drawText(text, width - paint.measureText(text) - dp(4f), dp(11f), paint)
    }

    /** A tapped moment: a line through it and what was measured there. */
    private fun drawInspect(
        canvas: Canvas, at: Long, top: Float, bottom: Float,
        start: Long, end: Long, x: (Long) -> Float, y: (Double) -> Float,
    ) {
        if (at !in start..end) return
        val reading = nearest(at, 8 * 60_000L)
        val time = reading?.timeMillis ?: at
        val atX = x(time)
        paint.pathEffect = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Brand.TEXT and 0x99FFFFFF.toInt()
        canvas.drawLine(atX, top, atX, bottom, paint)
        reading?.let { atom(canvas, atX, y(it.mgdl.toDouble()), Brand.glucoseColor(it.mgdl), dp(5.5f)) }

        val parts = buildList {
            add(clock.format(Date(time)))
            reading?.let { add("${unit.format(it.mgdl.toDouble())} ${unit.label}") }
            if (showHeart) heart.minByOrNull { abs(it.timeMillis - time) }
                ?.takeIf { abs(it.timeMillis - time) <= 5 * 60_000L }?.let { add("♥ ${it.bpm}") }
            treatments.filter { abs(it.timeMillis - time) <= 10 * 60_000L }.forEach { t ->
                if (t.insulin > 0) add("${formatAmount(t.insulin, 1)} U")
                if (t.carbs > 0) add("${formatAmount(t.carbs, 0)} g")
            }
        }
        val text = parts.joinToString("  ·  ")
        paint.textSize = dp(12f)
        val pad = dp(7f)
        val w = paint.measureText(text) + pad * 2
        val h = dp(24f)
        val boxX = (atX - w / 2).coerceIn(0f, width - w)
        val box = RectF(boxX, top + dp(2f), boxX + w, top + dp(2f) + h)
        paint.style = Paint.Style.FILL
        paint.color = INSPECT_BOX
        canvas.drawRoundRect(box, dp(8f), dp(8f), paint)
        paint.color = reading?.let { Brand.glucoseColor(it.mgdl) } ?: Brand.TEXT
        canvas.drawText(text, box.left + pad, box.top + h / 2 + dp(4f), paint)
    }

    private companion object {
        /** The chart always covers at least this, so the target band never fills the whole height. */
        const val MIN_MGDL = 60.0
        const val MAX_MGDL = 195.0

        const val MIN_WINDOW_MS = 3_600_000L
        const val MAX_WINDOW_MS = 48 * 3_600_000L

        /** Dragged to within this of now, the chart takes up following now again. */
        const val LIVE_SNAP_MS = 2 * 60_000L

        /** Longer than this between readings is a sensor gap, not a bond. */
        const val GAP_MS = 20 * 60_000L
        const val HEART_GAP_MS = 10 * 60_000L
        const val MAX_FOOD = 8

        val MGDL_LEVELS = listOf(50.0, 100.0, 150.0, 220.0, 250.0, 300.0, 350.0)
        val MMOL_LEVELS = listOf(3.0, 6.0, 8.0, 12.0, 14.0, 17.0, 19.0)

        /** A green wash for the target range, a faint white grid, white dashes at 70 and 180. */
        const val BAND = 0x12_4F_BF_85
        const val GRID = 0x18_FF_FF_FF
        const val MIDNIGHT = 0x50_FF_FF_FF
        const val TARGET_LINE = 0x70_FF_FF_FF
        const val INSPECT_BOX = 0xF0_1C_1C_1C.toInt()
    }
}
