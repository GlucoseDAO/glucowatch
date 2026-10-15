package io.github.antonkulaga.glucowatch.phone

/** Approved printable report palette; intentionally independent of the watch UI palette. */
internal object ReportStyle {
    const val WIDTH = 612
    const val HEIGHT = 792
    const val MARGIN = 22f
    const val INK = 0xFF000000.toInt()
    const val GREY = 0xFF858585.toInt()
    const val GRID = 0xFFD6D6D6.toInt()
    const val GREEN = 0xFF58A618.toInt()
    const val YELLOW = 0xFFFED65C.toInt()
    const val ORANGE = 0xFFE36A00.toInt()
    const val LOW = 0xFFFD8C80.toInt()
    const val VERY_LOW = 0xFFA60006.toInt()
    const val HIGH_RULE = 0xFFFFA43C.toInt()
    const val LOW_RULE = 0xFFFF514B.toInt()
    const val BOLUS = 0xFFD52B35.toInt()
    const val BASAL = 0xFF168BD3.toInt()
    const val LAYER_ALPHA = 64 // 25%, rounded to an 8-bit alpha.
    const val INTERSECTION_ALPHA = 28 // Normalizes two 25% layers to a visible 50% intersection.
    const val RADIUS = 2f
    const val HOUR_GAP = 2.2f
    const val DISPLAY_MINUTES = 5
}
