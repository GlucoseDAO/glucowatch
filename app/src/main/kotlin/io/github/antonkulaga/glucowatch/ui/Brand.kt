package io.github.antonkulaga.glucowatch.ui

import android.content.res.ColorStateList
import android.widget.Button

/**
 * GlucoseDAO colours, from the theme of its posters and from its logo, lifted where needed so they
 * read on a black watch screen. The posters also give them meanings, which the watch keeps:
 * glucose is teal, basal and bolus orange, carbohydrates green, the model's forecast purple.
 *
 *   poster     on the watch
 *   #0B7285 -> TEAL (fills), TEAL_BRIGHT (glucose in range), TEAL_LIGHT (labels)
 *   #DE7C22 -> INSULIN
 *   #2D8A5B -> CARBS
 *   #7556A8 -> FORECAST
 *   #17324D -> SURFACE (a darker navy behind pills and secondary buttons)
 *   #60717C -> MUTED
 *   logo red #E0141E -> LOW
 *
 * The light tile uses darker green, amber and red on an off-white background so the same
 * glucose-state meanings stay readable. The other LIGHT_* colors are from the poster palette.
 */
object Brand {
    const val TEAL = 0xFF0B7285.toInt()
    const val TEAL_BRIGHT = 0xFF2FB3C6.toInt()
    const val TEAL_LIGHT = 0xFF6CCBD8.toInt()
    const val INSULIN = 0xFFF0923A.toInt()
    const val CARBS = 0xFF4FBF85.toInt()
    const val FORECAST = 0xFFA78BDB.toInt()
    const val HIGH = 0xFFF2C94C.toInt()
    const val LOW = 0xFFF0525A.toInt()
    const val SURFACE = 0xFF13263A.toInt()
    const val MUTED = 0xFF8FA0AA.toInt()
    const val TEXT = 0xFFE6EDF0.toInt()

    // Light surfaces: darker green, amber and red keep their contrast on white.
    const val NAVY = 0xFF17324D.toInt()
    const val LIGHT_MUTED = 0xFF60717C.toInt()
    const val LIGHT_INSULIN = 0xFFDE7C22.toInt()
    const val LIGHT_CARBS = 0xFF2D8A5B.toInt()
    const val LIGHT_FORECAST = 0xFF7556A8.toInt()
    const val LIGHT_HIGH = 0xFFB7791F.toInt()
    const val LIGHT_LOW = 0xFFC4121B.toInt()
    const val LIGHT_BACKGROUND = 0xFFF7FAF9.toInt()

    fun style(button: Button, primary: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (primary) TEAL else SURFACE)
        button.setTextColor(if (primary) 0xFFFFFFFF.toInt() else TEAL_LIGHT)
        button.isAllCaps = false
    }
}
