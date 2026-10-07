package io.github.antonkulaga.glucowatch.complications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import glucowatch.core.DemoData
import glucowatch.core.LoopStatus
import glucowatch.core.Treatment
import glucowatch.core.formatAge
import glucowatch.core.formatAmount
import glucowatch.core.lastCarbs
import glucowatch.core.lastDelta
import glucowatch.core.lastManualBolus
import io.github.antonkulaga.glucowatch.R
import io.github.antonkulaga.glucowatch.chart.ChartRenderer
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import io.github.antonkulaga.glucowatch.data.GlucoseState
import io.github.antonkulaga.glucowatch.data.DataSource
import io.github.antonkulaga.glucowatch.data.RefreshReceiver
import io.github.antonkulaga.glucowatch.data.Settings
import io.github.antonkulaga.glucowatch.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Shared plumbing: all data sources read the cached state and open the app on tap. */
abstract class GlucoseComplicationService : SuspendingComplicationDataSourceService() {

    abstract fun build(type: ComplicationType, state: GlucoseState): ComplicationData?

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        RefreshReceiver.kick(this)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, GlucoseRepository(this).state())

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = System.currentTimeMillis()
        val readings = DemoData.readings(now, hours = 3)
        return build(type, GlucoseState(Settings(), readings, null, null, 0, DemoData.treatments(now, hours = 3), DemoData.loopStatus(now)))
    }

    protected fun tapAction(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    protected fun plain(text: String) = PlainComplicationText.Builder(text).build()

    /** "35m", "3h 26m": counts up on the watch face by itself between refreshes. */
    protected fun since(timeMillis: Long) = TimeDifferenceComplicationText.Builder(
        TimeDifferenceStyle.SHORT_DUAL_UNIT,
        CountUpTimeReference(Instant.ofEpochMilli(timeMillis)),
    ).setMinimumTimeUnit(TimeUnit.MINUTES).build()
}

class GlucoseValueComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val latest = state.latest ?: return NoDataComplicationData()
        val stale = state.settings.source != DataSource.DEMO && state.isStale()
        val unit = state.settings.unit
        val value = unit.format(latest.mgdl.toDouble())
        val text = value + latest.trend.arrow
        val delta = state.readings.lastDelta()?.let(unit::formatDelta)
        // Fresh: show the change since the previous reading; stale: show how old the value is instead.
        val subtitle = if (stale) "OLD DATA" else delta ?: ""
        val description = plain("Glucose $value ${unit.label} ${latest.trend.description}" + if (stale) ", reading more than 10 minutes old" else "")
        val age = TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            CountUpTimeReference(Instant.ofEpochMilli(latest.timeMillis)),
        ).setMinimumTimeUnit(TimeUnit.MINUTES).build()

        return when (type) {
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(pngIcon(glucoseImage(state, value, latest.trend.arrow, delta, stale)), SmallImageType.PHOTO).build(),
                description,
            ).setTapAction(tapAction()).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(text), description)
                .setTitle(plain(subtitle))
                .setTapAction(tapAction())
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain("$text ${delta.orEmpty()} ${unit.label}"), description)
                .setTitle(if (stale) plain("OLD DATA") else age)
                .setTapAction(tapAction())
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = unit.convert(latest.mgdl.toDouble().coerceIn(40.0, 300.0)).toFloat(),
                min = unit.convert(40.0).toFloat(),
                max = unit.convert(300.0).toFloat(),
                contentDescription = description,
            ).setText(plain(text)).setTitle(plain(subtitle)).setTapAction(tapAction()).build()
            else -> null
        }
    }

    /**
     * The reading on the left of the middle band, between the big hour and the big minute.
     * The trend arrow sits just to the right of the number, drawn a little narrower.
     */
    private fun glucoseImage(state: GlucoseState, number: String, arrow: String, delta: String?, stale: Boolean): Bitmap {
        // Full face: the in-range wash behind, the reading in the left of the middle band on top of it.
        val width = 450
        val height = 450
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(ChartRenderer.rangeWash(state, width, height), 0f, 0f, null)
        canvas.save()
        canvas.translate(22f, 178f)
        canvas.scale(0.5f, 0.5f)
        drawGlucoseDigits(canvas, 300f, 192f, state, number, arrow, stale)
        canvas.restore()
        drawGlucoseCaption(canvas, state, delta, stale)
        return bitmap
    }

    /**
     * Change and age under the reading, one line each. Drawn on the full face so the type
     * stays large enough to read; the digit box above is scaled down.
     */
    private fun drawGlucoseCaption(canvas: Canvas, state: GlucoseState, delta: String?, stale: Boolean) {
        val minutes = state.ageMinutes() ?: 0
        val age = if (minutes < 1) "now" else "${formatAge(minutes)} ago"
        val change = when {
            stale -> "OLD DATA"
            delta != null -> "$delta ${state.settings.unit.label}"
            else -> null
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (stale) 0xFFF0B000.toInt() else 0xFFD5D8DA.toInt()
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 26f
        }
        val left = 26f
        val maxWidth = 148f
        while (paint.textSize > 18f && (
            (change != null && paint.measureText(change) > maxWidth) || paint.measureText(age) > maxWidth
        )) {
            paint.textSize -= 1f
        }
        var baseline = 268f
        if (change != null) {
            canvas.drawText(change, left, baseline, paint)
            baseline += paint.textSize + 8f
        }
        paint.color = 0xFFD5D8DA.toInt()
        canvas.drawText(age, left, baseline, paint)
    }

    /** Digits in a 300 by 192 box. The face scales that box into the left of the middle band. */
    private fun drawGlucoseDigits(
        canvas: Canvas, width: Float, height: Float, state: GlucoseState,
        number: String, arrow: String, stale: Boolean,
    ) {
        val latest = state.latest ?: return
        val color = if (stale) 0xFF858989.toInt()
            else ChartRenderer.glanceColorFor(latest.mgdl.toDouble(), state, latest.trend)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.LEFT
            typeface = roundedDigits()
            textSize = 128f
        }
        val squash = 0.62f
        val gap = 4f
        // The rounded face has no arrow glyphs, so the trend mark stays in the system font.
        val arrowPaint = Paint(valuePaint).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        var size = valuePaint.textSize
        var valueWidth = valuePaint.measureText(number)
        var rawArrow = 0f
        var arrowVisual = 0f
        fun measureArrow() {
            if (arrow.isEmpty()) {
                rawArrow = 0f
                arrowVisual = 0f
            } else {
                arrowPaint.textSize = size * 0.72f
                rawArrow = arrowPaint.measureText(arrow)
                arrowVisual = rawArrow * squash
            }
        }
        measureArrow()
        while (size > 64f && valueWidth + (if (arrow.isEmpty()) 0f else gap + arrowVisual) > width - 8f) {
            size -= 4f
            valuePaint.textSize = size
            valueWidth = valuePaint.measureText(number)
            measureArrow()
        }
        val extras = if (arrow.isEmpty()) 0f else gap + arrowVisual
        val left = ((width - valueWidth - extras) / 2f).coerceAtLeast(0f)
        val baseline = height * 0.62f
        canvas.drawText(number, left, baseline, valuePaint)
        if (arrow.isNotEmpty()) {
            val center = left + valueWidth + gap + arrowVisual / 2f
            val arrowBaseline = baseline - (size - arrowPaint.textSize) * 0.22f
            canvas.save()
            canvas.scale(squash, 1f, center, arrowBaseline)
            canvas.drawText(arrow, center - rawArrow / 2f, arrowBaseline, arrowPaint)
            canvas.restore()
        }
    }

    /** Baloo 2 ExtraBold, the open rounded face used for the reading. */
    private fun roundedDigits(): Typeface {
        rounded?.let { return it }
        val face = Typeface.create(resources.getFont(R.font.baloo2), 800, false)
        return face.also { rounded = it }
    }

    private var rounded: Typeface? = null
}

class GlucoseChartComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val description = plain("Glucose chart, last ${state.settings.chartHours} hours")
        return when (type) {
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                pngIcon(faceChart(state)), description,
            ).setTapAction(tapAction()).build()
            // Wide chart here too: this is the type the GlucoWatch face uses by default.
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(pngIcon(faceChart(state)), SmallImageType.PHOTO).build(),
                description,
            ).setTapAction(tapAction()).build()
            else -> null
        }
    }

    /**
     * The curve and its forecast, drawn on top of the clock. The in-range tint is a separate
     * layer behind the type. The right side of the plot is the forecast horizon.
     */
    private fun faceChart(state: GlucoseState): Bitmap {
        return ChartRenderer.render(
            state, CHART_WIDTH, CHART_PLOT_HEIGHT, labels = false, overlay = true,
        )
    }

    /** PNG keeps the IPC payload small compared to a raw bitmap. */
    companion object {
        // The face canvas. One pixel of the bitmap is one pixel of the design, so the line stays thick.
        const val CHART_WIDTH = 450
        const val CHART_PLOT_HEIGHT = 450
    }
}

/** PNG keeps the complication IPC payload small compared with a raw bitmap. */
private fun pngIcon(bitmap: Bitmap): Icon {
    val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    return Icon.createWithData(bytes, 0, bytes.size)
}

/** Optional: only shows data when prediction is enabled in settings. */
class PredictionComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val point = state.prediction?.points?.lastOrNull() ?: return NoDataComplicationData()
        val unit = state.settings.unit
        val minutes = state.settings.horizonMinutes
        val value = unit.format(point.mgdl)
        val fromLoop = state.prediction?.modelId == LoopStatus.MODEL_ID
        val description = plain("Forecast $value ${unit.label} in $minutes minutes" + if (fromLoop) ", from the loop" else "")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(value), description)
                .setTitle(plain("${minutes}m"))
                .setTapAction(tapAction())
                .build()
            ComplicationType.LONG_TEXT -> {
                val range = if (point.lower != null && point.upper != null) " (${unit.format(point.lower!!)}–${unit.format(point.upper!!)})" else ""
                LongTextComplicationData.Builder(plain("in ${minutes}m: $value$range" + if (fromLoop) " (loop)" else ""), description)
                    .setTapAction(tapAction())
                    .build()
            }
            else -> null
        }
    }
}

/**
 * Nightscout: insulin and carbs on board as the loop (AAPS, Trio, iAPS, Loop) last reported them.
 * Empty without a loop, or once it has not reported for 30 minutes.
 */
class LoopComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val loop = state.freshLoop() ?: return NoDataComplicationData()
        val iob = loop.iob?.let { formatAmount(it, 1) + "U" }
        val cob = loop.cob?.let { formatAmount(it, 0) + "g" }
        if (iob == null && cob == null) return NoDataComplicationData()
        val description = plain(
            listOfNotNull(loop.iob?.let { "Insulin on board ${formatAmount(it)} units" }, loop.cob?.let { "carbs on board ${formatAmount(it, 0)} grams" })
                .joinToString(", "),
        )
        return when (type) {
            // Big line IOB, small line COB, as on AAPS watch faces.
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(iob ?: cob!!), description)
                .setTitle(plain(if (iob != null) cob ?: "IOB" else "COB"))
                .setTapAction(tapAction())
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                plain(listOfNotNull(iob?.let { "IOB $it" }, cob?.let { "COB $it" }).joinToString("  ")), description,
            ).setTitle(since(loop.timeMillis)).setTapAction(tapAction()).build()
            else -> null
        }
    }
}

/** Nightscout: the last bolus given by hand (not SMBs) and the last carbs, with how long ago. */
class TreatmentComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val now = System.currentTimeMillis()
        val bolus = state.treatments.lastManualBolus(now)
        val carbs = state.treatments.lastCarbs(now)
        val latest = listOfNotNull(bolus, carbs).maxByOrNull { it.timeMillis } ?: return NoDataComplicationData()
        fun ago(t: Treatment) = formatAge((now - t.timeMillis) / 60_000)
        val bolusText = bolus?.let { formatAmount(it.insulin) + "U" }
        val carbsText = carbs?.let { formatAmount(it.carbs, 0) + "g" }
        val description = plain(
            listOfNotNull(
                bolus?.let { "Bolus ${formatAmount(it.insulin)} units ${ago(it)} ago" },
                carbs?.let { "carbs ${formatAmount(it.carbs, 0)} grams ${ago(it)} ago" },
            ).joinToString(", "),
        )
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                plain((if (latest === bolus) bolusText else carbsText).orEmpty()), description,
            ).setTitle(since(latest.timeMillis)).setTapAction(tapAction()).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                plain(
                    listOfNotNull(
                        bolus?.let { "$bolusText ${ago(it)}" },
                        carbs?.let { "$carbsText ${ago(it)}" },
                    ).joinToString("  "),
                ),
                description,
            ).setTitle(plain("Bolus, carbs")).setTapAction(tapAction()).build()
            else -> null
        }
    }
}
