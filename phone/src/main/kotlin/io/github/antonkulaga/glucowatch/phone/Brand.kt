package io.github.antonkulaga.glucowatch.phone

import android.content.res.ColorStateList
import android.widget.Button
import glucowatch.core.GlucosePalette

/**
 * The phone's colours. The values are [GlucosePalette]'s, which the watch face, tiles and watch
 * app use too, so the two screens read as one product. What is phone-only here are the two panel
 * greys and which colour each part of the dashboard takes.
 *
 * Colour means one thing: where the glucose sits. Chrome is black, grey and white; red is also the
 * accent for anything switched off, and for the heart.
 */
object Brand {
    const val BACKGROUND = GlucosePalette.BLACK

    /** Panels on the Connect, Model and Watch tabs. Today sits directly on black. */
    const val SURFACE = 0xFF141414.toInt()

    /** Tappable rows: the food button, the heart-rate row, a found model. */
    const val ROW = 0xFF1C1C1C.toInt()

    const val TEXT = GlucosePalette.WHITE
    const val MUTED = GlucosePalette.GREY

    const val GREEN = GlucosePalette.GREEN
    const val YELLOW = GlucosePalette.YELLOW
    const val ORANGE = GlucosePalette.ORANGE
    const val RED = GlucosePalette.RED

    /** The forecast is the model's guess, not a measurement: grey, and dashed in the chart. */
    const val FORECAST = 0xFF8C9196.toInt()
    const val CARBS = GlucosePalette.GREEN

    /** Insulin is drawn in white and hangs from the top of the chart, away from the carbs below. */
    const val INSULIN = GlucosePalette.WHITE
    const val HEART = GlucosePalette.RED

    /** The watch's default target range, which the chart shades and the status pill reads. */
    const val TARGET_LOW = 70
    const val TARGET_HIGH = 180

    fun glucoseColor(mgdl: Int) = GlucosePalette.ramp(mgdl)

    fun style(button: Button, primary: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (primary) ROW else SURFACE)
        button.setTextColor(if (primary) TEXT else MUTED)
        button.isAllCaps = false
    }
}
