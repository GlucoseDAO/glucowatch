package io.github.antonkulaga.glucowatch.ui

import android.content.res.ColorStateList
import android.widget.Button

/**
 * GlucoseDAO colours, taken from its posters and logo: deep teal with an orange accent, and the
 * red, grey and white of the glucose molecule in the icon. The teal is lifted for a black screen.
 * Glucose values keep their own range colours (ChartRenderer); these are for the chrome around them.
 */
object Brand {
    const val TEAL = 0xFF0E7C86.toInt()
    const val TEAL_LIGHT = 0xFF5EC2CC.toInt()
    const val ORANGE = 0xFFF08A24.toInt()
    const val SURFACE = 0xFF1C2230.toInt()

    fun style(button: Button, primary: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (primary) TEAL else SURFACE)
        button.setTextColor(if (primary) 0xFFFFFFFF.toInt() else TEAL_LIGHT)
        button.isAllCaps = false
    }
}
