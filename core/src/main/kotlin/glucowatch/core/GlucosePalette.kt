package glucowatch.core

/**
 * The palette both apps draw with: black, grey and white for everything that is not glucose, and
 * colour kept for one meaning — where the glucose sits. The values are the watch face's
 * (`watchface/src/main/res/raw/watchface.xml`) and the glucose-all tile's, so the face, the tiles,
 * the watch app and the phone app agree.
 *
 * Plain ARGB ints rather than Android colours, so this stays free of Android and is testable here.
 */
object GlucosePalette {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GREY = 0xFF9AA3A8.toInt()

    const val GREEN = 0xFF4FBF85.toInt()
    const val YELLOW = 0xFFF2C94C.toInt()
    const val ORANGE = 0xFFF0923A.toInt()
    const val RED = 0xFFF0525A.toInt()

    /** Darker versions that keep their contrast on the light tile's off-white background. */
    const val LIGHT_BACKGROUND = 0xFFF7FAF9.toInt()
    const val LIGHT_TEXT = 0xFF1A1A1A.toInt()
    const val LIGHT_GREY = 0xFF60717C.toInt()
    const val LIGHT_GREEN = 0xFF2D8A5B.toInt()
    const val LIGHT_YELLOW = 0xFFB7791F.toInt()
    const val LIGHT_ORANGE = 0xFFDE7C22.toInt()
    const val LIGHT_RED = 0xFFC4121B.toInt()

    private val ANCHORS = listOf(
        45 to RED, 58 to ORANGE, 68 to YELLOW, 80 to GREEN,
        160 to GREEN, 185 to YELLOW, 225 to ORANGE, 280 to RED,
    )

    /**
     * A reading as a colour code: green through the target range, yellow as it drifts out, orange
     * further out, red at either extreme. Interpolated between the anchors rather than stepped, so
     * a run drifting high shades over before it crosses 180 mg/dL.
     */
    fun ramp(mgdl: Int): Int {
        if (mgdl <= ANCHORS.first().first) return ANCHORS.first().second
        if (mgdl >= ANCHORS.last().first) return ANCHORS.last().second
        val upper = ANCHORS.indexOfFirst { mgdl <= it.first }
        val (fromLevel, fromColor) = ANCHORS[upper - 1]
        val (toLevel, toColor) = ANCHORS[upper]
        return blend(fromColor, toColor, (mgdl - fromLevel).toFloat() / (toLevel - fromLevel))
    }

    /** [color] with its alpha replaced by [alpha] (0–255), for washes and faint guides. */
    fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private fun blend(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 255
            val b = (to shr shift) and 255
            return (a + (b - a) * amount).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
