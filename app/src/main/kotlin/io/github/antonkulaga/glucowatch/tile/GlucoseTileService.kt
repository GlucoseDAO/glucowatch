package io.github.antonkulaga.glucowatch.tile

import android.graphics.Bitmap
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
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import glucowatch.core.formatAge
import glucowatch.core.lastDelta
import io.github.antonkulaga.glucowatch.chart.ChartRenderer
import io.github.antonkulaga.glucowatch.data.GlucoseRepository
import io.github.antonkulaga.glucowatch.data.GlucoseState
import io.github.antonkulaga.glucowatch.ui.Brand
import io.github.antonkulaga.glucowatch.ui.MainActivity
import java.io.ByteArrayOutputStream

/**
 * The GlucoWatch tile ("Add tiles" on the watch): value and trend, the change and age, the chart
 * from rim to rim (about 40% of the screen height, so everything sits in the wide middle of the
 * circle), and the forecast if it is on. It reads the same cache as the complications and is
 * refreshed with them (RefreshReceiver). Tapping it opens the app.
 */
class GlucoseTileService : TileService() {

    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = now {
        val state = GlucoseRepository(this).state()
        val device = request.deviceConfiguration
        val chartHeight = device.screenHeightDp * 0.4f
        TileBuilders.Tile.Builder()
            .setResourcesVersion(version(state))
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(state, chart(request, state, chartHeight), chartHeight)))
            .build()
    }

    /** The chart as a PNG at the screen's pixel width, carried in the tile's own resources. */
    private fun chart(request: RequestBuilders.TileRequest, state: GlucoseState, heightDp: Float): LayoutElementBuilders.Image.Builder {
        val device = request.deviceConfiguration
        val width = (device.screenWidthDp * device.screenDensity).toInt().coerceAtLeast(200)
        val height = (heightDp * device.screenDensity).toInt()
        val png = ByteArrayOutputStream().use {
            ChartRenderer.render(state, width, height, edge = true).compress(Bitmap.CompressFormat.PNG, 100, it)
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
        return LayoutElementBuilders.Image.Builder(request.scope).setImageResource(image, CHART_ID)
    }

    private fun layout(state: GlucoseState, chart: LayoutElementBuilders.Image.Builder, chartHeight: Float): LayoutElementBuilders.LayoutElement {
        val unit = state.settings.unit
        val latest = state.latest
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("GlucoWatch", 12f, Brand.TEAL_LIGHT))
            .addContent(space(2f))

        if (latest == null) {
            column.addContent(text("---", 40f, 0xFFFFFFFF.toInt(), bold = true))
            column.addContent(text("No readings yet", 13f, COLOR_MUTED))
        } else {
            val color = if (state.isStale()) COLOR_MUTED else ChartRenderer.colorFor(latest.mgdl.toDouble(), state)
            column.addContent(text("${unit.format(latest.mgdl.toDouble())} ${latest.trend.arrow}", 40f, color, bold = true))
            val status = listOfNotNull(
                state.readings.lastDelta()?.let(unit::formatDelta),
                state.ageMinutes()?.let { if (it < 1) "now" else "${formatAge(it)} ago" },
            ).joinToString("  ·  ")
            column.addContent(text(status, 13f, COLOR_MUTED))
        }
        column.addContent(space(6f))
        column.addContent(
            chart.setWidth(expand())
                .setHeight(dp(chartHeight))
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FILL_BOUNDS)
                .build(),
        )
        state.prediction?.points?.lastOrNull()?.let { p ->
            column.addContent(space(4f))
            column.addContent(text("${unit.format(p.mgdl)} in ${state.settings.horizonMinutes} min", 13f, ChartRenderer.COLOR_FORECAST))
        }

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
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(column.build())
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(1)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build(),
        )
        .build()

    private fun space(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    /** The chart moves with the clock as well as with new readings: a new version every minute. */
    private fun version(state: GlucoseState) = "${state.lastFetchMillis}-${System.currentTimeMillis() / 60_000}"

    private fun <T : Any> now(block: () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { it.set(block()); "GlucoseTileService" }

    companion object {
        private const val CHART_ID = "chart"
        private const val FRESHNESS_MS = 5 * 60_000L
        private const val COLOR_MUTED = 0xFF9CA3AF.toInt()
    }
}
