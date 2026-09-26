package io.github.antonkulaga.glucowatch.tile

import android.graphics.Bitmap
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicFloat
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicString
import androidx.wear.protolayout.expression.PlatformHealthSources
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import glucowatch.core.formatAge
import glucowatch.core.lastDelta
import io.github.antonkulaga.glucowatch.R
import io.github.antonkulaga.glucowatch.chart.ChartRenderer
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import io.github.antonkulaga.glucowatch.data.GlucoseState
import io.github.antonkulaga.glucowatch.ui.Brand
import io.github.antonkulaga.glucowatch.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.time.ZoneId

/**
 * What the three GlucoWatch tiles share: they read the same cache as the complications and are
 * refreshed with them (RefreshReceiver), draw the chart from rim to rim into the tile's own
 * resources, and open the app on tap. Each one is a separate entry under "Add tiles".
 */
abstract class GlucoseTile : TileService() {
    protected open val light = false
    protected open val palette get() = if (light) ChartRenderer.Palette.LIGHT else ChartRenderer.Palette.DARK
    protected val ink get() = if (light) Brand.LIGHT_TEXT else Brand.TEXT
    protected val muted get() = if (light) Brand.LIGHT_MUTED else Brand.MUTED
    protected val accent get() = if (light) Brand.LIGHT_MUTED else Brand.MUTED

    /** The tile's content, centred on the screen. */
    protected abstract fun content(request: RequestBuilders.TileRequest, state: GlucoseState): LayoutElementBuilders.LayoutElement

    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = now {
        val state = GlucoseRepository(this).state()
        TileBuilders.Tile.Builder()
            .setResourcesVersion(version(state))
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root(content(request, state))))
            .build()
    }

    private fun root(content: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        val modifiers = ModifiersBuilders.Modifiers.Builder().setClickable(open)
        if (light) modifiers.setBackground(ModifiersBuilders.Background.Builder().setColor(argb(Brand.LIGHT_BACKGROUND)).build())
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(modifiers.build())
            .addContent(content)
            .build()
    }

    protected fun column() = LayoutElementBuilders.Column.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

    /** Value and trend arrow in the range colour, grey when stale, "---" before the first reading. */
    protected fun value(state: GlucoseState, size: Float): LayoutElementBuilders.LayoutElement {
        val latest = state.latest ?: return text("---", size, ink, bold = true)
        val color = if (state.isStale()) muted else ChartRenderer.colorFor(latest.mgdl.toDouble(), state, palette)
        return text("${state.settings.unit.format(latest.mgdl.toDouble())} ${latest.trend.arrow}", size, color, bold = true)
    }

    /** "+3 · 2m ago", or a hint before the first reading. */
    protected fun status(state: GlucoseState, size: Float = 13f): LayoutElementBuilders.LayoutElement {
        if (state.latest == null) return text("No readings yet", size, muted)
        val line = listOfNotNull(
            state.readings.lastDelta()?.let(state.settings.unit::formatDelta),
            state.ageMinutes()?.let { if (it < 1) "now" else "${formatAge(it)} ago" },
        ).joinToString("  ·  ")
        return text(line, size, muted)
    }

    protected fun forecast(state: GlucoseState, size: Float = 13f): LayoutElementBuilders.LayoutElement? =
        state.prediction?.points?.lastOrNull()?.let { p ->
            text("${state.settings.unit.format(p.mgdl)} in ${state.settings.horizonMinutes} min", size, palette.forecast)
        }

    /** The chart as a PNG at the screen's pixel width, [share] of the screen height tall. */
    protected fun chart(request: RequestBuilders.TileRequest, state: GlucoseState, share: Float): LayoutElementBuilders.LayoutElement {
        val device = request.deviceConfiguration
        val heightDp = device.screenHeightDp * share
        val width = (device.screenWidthDp * device.screenDensity).toInt().coerceAtLeast(200)
        val height = (heightDp * device.screenDensity).toInt()
        val png = ByteArrayOutputStream().use {
            ChartRenderer.render(state, width, height, edge = true, palette = palette).compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        val image = ResourceBuilders.ImageResource.Builder()
            .setInlineResource(
                ResourceBuilders.InlineImageResource.Builder()
                    .setData(png).setWidthPx(width).setHeightPx(height)
                    .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Image.Builder(request.scope)
            .setImageResource(image, CHART_ID)
            .setWidth(expand())
            .setHeight(dp(heightDp))
            .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FILL_BOUNDS)
            .build()
    }

    /** A drawable from the app, tinted. */
    protected fun icon(request: RequestBuilders.TileRequest, @DrawableRes id: Int, name: String, size: Float, color: Int) =
        LayoutElementBuilders.Image.Builder(request.scope)
            .setImageResource(
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(id).build())
                    .build(),
                name,
            )
            .setWidth(dp(size))
            .setHeight(dp(size))
            .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(color)).build())
            .build()

    protected fun text(value: String, size: Float, color: Int, bold: Boolean = false) = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(1)
        .setFontStyle(font(size, color, bold))
        .build()

    /** Text that the watch updates by itself between tile refreshes (clock, heart rate). */
    protected fun dynamicText(value: DynamicString, placeholder: String, widest: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(TypeBuilders.StringProp.Builder(placeholder).setDynamicValue(value).build())
            .setLayoutConstraintsForDynamicText(TypeBuilders.StringLayoutConstraint.Builder(widest).build())
            .setMaxLines(1)
            .setFontStyle(font(size, color, bold))
            .build()

    private fun font(size: Float, color: Int, bold: Boolean) = LayoutElementBuilders.FontStyle.Builder()
        .setSize(sp(size))
        .setColor(argb(color))
        .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
        .build()

    protected fun space(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    protected fun gap(width: Float) = LayoutElementBuilders.Spacer.Builder().setWidth(dp(width)).build()

    /** The chart moves with the clock as well as with new readings: a new version every minute. */
    private fun version(state: GlucoseState) = "${state.lastFetchMillis}-${System.currentTimeMillis() / 60_000}"

    private fun <T : Any> now(block: () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { it.set(block()); javaClass.simpleName }

    companion object {
        private const val CHART_ID = "chart"
        const val FRESHNESS_MS = 5 * 60_000L

        /** Every tile, for RefreshReceiver to update after a fetch. */
        val all = listOf(GlucoseTileService::class.java, GlucoseAllTileService::class.java, GlucoseLightTileService::class.java)
    }
}

/**
 * glucose-only: value and trend, change and age, the chart through the wide middle of the circle
 * (60% of the screen height), and the forecast if it is on. The class keeps its first name so a
 * tile added before the others existed stays in place.
 */
open class GlucoseTileService : GlucoseTile() {
    override fun content(request: RequestBuilders.TileRequest, state: GlucoseState): LayoutElementBuilders.LayoutElement {
        val chartAndReading = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(dp(request.deviceConfiguration.screenHeightDp * 0.6f))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(chart(request, state, 0.6f))
            .addContent(column()
                .addContent(value(state, 40f))
                .addContent(status(state))
                .build())
            .build()
        val column = column()
            .addContent(text("GlucoWatch", 12f, accent))
            .addContent(space(2f))
            .addContent(chartAndReading)
        forecast(state)?.let { column.addContent(space(4f)).addContent(it) }
        return column.build()
    }
}

/** glucose-light: the glance-first layout on an off-white surface. */
class GlucoseLightTileService : GlucoseAllTileService() {
    override val light = true
    override val palette get() = ChartRenderer.Palette.GLUCOSE_LIGHT
}

/**
 * glucose-all and glucose-light: the clock on top, glucose over a large range chart, then
 * heart rate and the watch battery in one quiet row. Clock and heart rate update on the watch; heart rate
 * needs the permission the app asks for in Settings, and shows "--" until then.
 */
open class GlucoseAllTileService : GlucoseTile() {
    override val palette get() = ChartRenderer.Palette.GLUCOSE_ALL

    override fun content(request: RequestBuilders.TileRequest, state: GlucoseState): LayoutElementBuilders.LayoutElement {
        val primary = if (light) 0xFF202624.toInt() else 0xFFF5F5F3.toInt()
        val gray = if (light) 0xFF68716C.toInt() else 0xFFB8BAB9.toInt()
        val red = if (light) Brand.LIGHT_LOW else Brand.LOW
        val footer = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(icon(request, R.drawable.ic_heart, "heart", 14f, red))
            .addContent(gap(4f))
            .addContent(dynamicText(heartRate(), "--", "000", 14f, red))
            .addContent(gap(10f))
            .addContent(icon(request, R.drawable.ic_battery, "battery", 14f, gray))
            .addContent(gap(4f))
            .addContent(text("${battery()}%", 13f, primary))
        val chartAndReading = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(dp(request.deviceConfiguration.screenHeightDp * 0.55f))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(chart(request, state, 0.55f))
            .addContent(column()
                .addContent(glucoseValue(state, 36f))
                .addContent(status(state, 11f))
                .build())
            .build()
        val content = column()
            .addContent(dynamicText(clock(), "--:--", "00:00", 36f, primary, bold = true))
            .addContent(space(1f))
            .addContent(chartAndReading)
            .addContent(space(2f))
            .addContent(footer.build())
            .build()
        return content
    }

    private fun glucoseValue(state: GlucoseState, size: Float): LayoutElementBuilders.LayoutElement {
        val latest = state.latest ?: return text("---", size, if (light) Brand.LIGHT_MUTED else 0xFF858989.toInt(), bold = true)
        val color = if (state.isStale()) if (light) Brand.LIGHT_MUTED else 0xFF858989.toInt()
            else ChartRenderer.glanceColorFor(latest.mgdl.toDouble(), state, latest.trend, palette)
        return text("${state.settings.unit.format(latest.mgdl.toDouble())} ${latest.trend.arrow}", size, color, bold = true)
    }

    private fun heartRate(): DynamicString =
        PlatformHealthSources.heartRateBpm().format(DynamicFloat.FloatFormatter.Builder().setMaxFractionDigits(0).build())

    /** The watch's own clock, in its 12 or 24 hour setting. */
    private fun clock(): DynamicString {
        val time = DynamicInstant.platformTimeWithSecondsPrecision().atZone(ZoneId.systemDefault())
        val twoDigits = DynamicInt32.IntFormatter.Builder().setMinIntegerDigits(2).build()
        val hour = if (DateFormat.is24HourFormat(this)) time.hour.format(twoDigits)
        else DynamicInt32.onCondition(time.hour.rem(12).eq(0)).use(12).elseUse(time.hour.rem(12)).format()
        return hour.concat(DynamicString.constant(":")).concat(time.minute.format(twoDigits))
    }

    private fun battery() = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}
