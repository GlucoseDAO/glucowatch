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
                SmallImage.Builder(pngIcon(glucoseImage(state, text, delta, stale)), SmallImageType.PHOTO).build(),
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

    /** The default face's value block, drawn as an image so glucose and trend can share a state color. */
    private fun glucoseImage(state: GlucoseState, text: String, delta: String?, stale: Boolean): Bitmap {
        val width = 432
        val height = 132
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val latest = state.latest ?: return bitmap
        val color = if (stale) 0xFF858989.toInt()
            else ChartRenderer.glanceColorFor(latest.mgdl.toDouble(), state, latest.trend)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // The 432 px bitmap is shown in a 360 px slot: 62 px renders at the clock's 52 px.
            textSize = 62f
        }
        canvas.drawText(text, width / 2f, 75f, valuePaint)
        val age = "${state.ageMinutes()}m ago"
        val status = if (stale) "⚠ OLD DATA · 10+ min" else listOfNotNull(delta, age).joinToString("  ·  ")
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (stale) 0xFFF0B000.toInt() else 0xFF9AA3A8.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 23f
        }
        canvas.drawText(status, width / 2f, 112f, statusPaint)
        return bitmap
    }
}

class GlucoseChartComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val description = plain("Glucose chart, last ${state.settings.chartHours} hours")
        return when (type) {
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                pngIcon(ChartRenderer.render(state, CHART_WIDTH, CHART_HEIGHT, edge = true, palette = ChartRenderer.Palette.GLUCOSE_ALL)), description,
            ).setTapAction(tapAction()).build()
            // Wide chart here too: this is the type the GlucoWatch face uses by default.
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(pngIcon(ChartRenderer.render(state, CHART_WIDTH, CHART_HEIGHT, edge = true, palette = ChartRenderer.Palette.GLUCOSE_ALL)), SmallImageType.PHOTO).build(),
                description,
            ).setTapAction(tapAction()).build()
            else -> null
        }
    }

    /** PNG keeps the IPC payload small compared to a raw bitmap. */
    companion object {
        // The face shows this rim to rim in a 450 x 200 slot; 1.2x for a sharp scale-down.
        const val CHART_WIDTH = 540
        const val CHART_HEIGHT = 240
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
