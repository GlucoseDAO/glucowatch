package io.github.antonkulaga.glucowatch.ui

import android.content.res.ColorStateList
import android.widget.Button
import glucowatch.core.GlucosePalette

/**
 * The watch app's colours: black, neutral grey and white, with glucose in green, yellow and red.
 * The values are [GlucosePalette]'s, shared with the watch face and the phone app.
 *
 * Insulin, carbs and the forecast keep GlucoseDAO's poster meanings on the charts that show them:
 * insulin orange, carbohydrates green, the model's forecast purple.
 *
 * The light tile uses darker green, amber and red on an off-white background so the same
 * glucose-state meanings stay readable.
 */
object Brand {
    const val GREEN = GlucosePalette.GREEN
    const val HIGH = GlucosePalette.YELLOW
    const val LOW = GlucosePalette.RED
    const val INSULIN = GlucosePalette.ORANGE
    const val CARBS = GlucosePalette.GREEN
    const val FORECAST = 0xFFA78BDB.toInt()

    /** Behind pills and secondary buttons. */
    const val SURFACE = 0xFF1C1C1C.toInt()
    const val MUTED = GlucosePalette.GREY
    const val TEXT = GlucosePalette.WHITE

    // Light surfaces: darker green, amber and red keep their contrast on off-white.
    const val LIGHT_TEXT = GlucosePalette.LIGHT_TEXT
    const val LIGHT_MUTED = GlucosePalette.LIGHT_GREY
    const val LIGHT_INSULIN = GlucosePalette.LIGHT_ORANGE
    const val LIGHT_CARBS = GlucosePalette.LIGHT_GREEN
    const val LIGHT_FORECAST = 0xFF7556A8.toInt()
    const val LIGHT_HIGH = GlucosePalette.LIGHT_YELLOW
    const val LIGHT_LOW = GlucosePalette.LIGHT_RED
    const val LIGHT_BACKGROUND = GlucosePalette.LIGHT_BACKGROUND

    fun style(button: Button, primary: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (primary) 0xFF2A2A2A.toInt() else SURFACE)
        button.setTextColor(if (primary) TEXT else MUTED)
        button.isAllCaps = false
    }
}
