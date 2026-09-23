package io.github.antonkulaga.glucowatch.complications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import glucowatch.core.lastDelta
import io.github.antonkulaga.glucowatch.chart.ChartRenderer
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import io.github.antonkulaga.glucowatch.data.GlucoseState
import io.github.antonkulaga.glucowatch.data.RefreshReceiver
import io.github.antonkulaga.glucowatch.data.Settings
import io.github.antonkulaga.glucowatch.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Shared plumbing: all three data sources read the cached state and open the app on tap. */
abstract class GlucoseComplicationService : SuspendingComplicationDataSourceService() {

    abstract fun build(type: ComplicationType, state: GlucoseState): ComplicationData?

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        RefreshReceiver.kick(this)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, GlucoseRepository(this).state())

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val readings = DemoData.readings(System.currentTimeMillis(), hours = 3)
        return build(type, GlucoseState(Settings(), readings, null, null, 0))
    }

    protected fun tapAction(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    protected fun plain(text: String) = PlainComplicationText.Builder(text).build()
}

class GlucoseValueComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val latest = state.latest ?: return NoDataComplicationData()
        val unit = state.settings.unit
        val value = unit.format(latest.mgdl.toDouble())
        val text = value + latest.trend.arrow
        val delta = state.readings.lastDelta()?.let(unit::formatDelta)
        // Fresh: show the change since the previous reading; stale: show how old the value is instead.
        val subtitle = if (state.isStale()) "${state.ageMinutes()}m ago" else delta ?: ""
        val description = plain("Glucose $value ${unit.label} ${latest.trend.description}")
        val age = TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            CountUpTimeReference(Instant.ofEpochMilli(latest.timeMillis)),
        ).setMinimumTimeUnit(TimeUnit.MINUTES).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(text), description)
                .setTitle(plain(subtitle))
                .setTapAction(tapAction())
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain("$text ${delta.orEmpty()} ${unit.label}"), description)
                .setTitle(age)
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
}

class GlucoseChartComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val description = plain("Glucose chart, last ${state.settings.chartHours} hours")
        return when (type) {
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                pngIcon(ChartRenderer.render(state, CHART_WIDTH, CHART_HEIGHT)), description,
            ).setTapAction(tapAction()).build()
            // Wide chart here too: this is the type the GlucoWatch face uses by default.
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(pngIcon(ChartRenderer.render(state, CHART_WIDTH, CHART_HEIGHT)), SmallImageType.PHOTO).build(),
                description,
            ).setTapAction(tapAction()).build()
            else -> null
        }
    }

    /** PNG keeps the IPC payload small compared to a raw bitmap. */
    private fun pngIcon(bitmap: Bitmap): Icon {
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        return Icon.createWithData(bytes, 0, bytes.size)
    }

    companion object {
        const val CHART_WIDTH = 480
        const val CHART_HEIGHT = 200
    }
}

/** Optional: only shows data when prediction is enabled in settings. */
class PredictionComplicationService : GlucoseComplicationService() {
    override fun build(type: ComplicationType, state: GlucoseState): ComplicationData? {
        val point = state.prediction?.points?.lastOrNull() ?: return NoDataComplicationData()
        val unit = state.settings.unit
        val minutes = state.settings.horizonMinutes
        val value = unit.format(point.mgdl)
        val description = plain("Forecast $value ${unit.label} in $minutes minutes")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(value), description)
                .setTitle(plain("${minutes}m"))
                .setTapAction(tapAction())
                .build()
            ComplicationType.LONG_TEXT -> {
                val range = if (point.lower != null && point.upper != null) " (${unit.format(point.lower!!)}–${unit.format(point.upper!!)})" else ""
                LongTextComplicationData.Builder(plain("in ${minutes}m: $value$range"), description)
                    .setTapAction(tapAction())
                    .build()
            }
            else -> null
        }
    }
}
