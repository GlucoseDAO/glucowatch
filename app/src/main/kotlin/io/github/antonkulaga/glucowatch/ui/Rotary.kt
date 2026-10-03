package io.github.antonkulaga.glucowatch.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.roundToInt

/**
 * The bezel and the crown send [MotionEvent.AXIS_SCROLL]. A plain [ScrollView] does not move
 * for that, so every screen the face opens has to ask for it.
 *
 * Positive means the same direction that scrolls a list downward.
 */
internal fun MotionEvent.rotarySteps(): Float? {
    if (action != MotionEvent.ACTION_SCROLL || !isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return null
    return -getAxisValue(MotionEvent.AXIS_SCROLL)
}

internal fun ScrollView.attachRotary() {
    isFocusable = true
    isFocusableInTouchMode = true
    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    setOnGenericMotionListener { _, event -> onRotaryScroll(event) }
}

internal fun ScrollView.onRotaryScroll(event: MotionEvent): Boolean {
    val steps = event.rotarySteps() ?: return false
    val delta = steps * ViewConfiguration.get(context).scaledVerticalScrollFactor
    scrollBy(0, delta.roundToInt())
    return true
}

/** The day chart is wider than the screen. One detent moves a short distance along it, not a whole screen. */
internal fun HorizontalScrollView.attachRotaryHorizontal() {
    isFocusable = true
    isFocusableInTouchMode = true
    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    setOnGenericMotionListener { _, event -> onRotaryScrollHorizontal(event) }
}

internal fun HorizontalScrollView.onRotaryScrollHorizontal(event: MotionEvent): Boolean {
    val steps = event.rotarySteps() ?: return false
    if (width <= 0) return false
    val factor = ViewConfiguration.get(context).scaledVerticalScrollFactor
    val step = factor.coerceIn(width * 0.04f, width * 0.12f)
    // The same turn that scrolls a list downward walks back through the day, toward older readings.
    scrollBy((-steps * step).roundToInt(), 0)
    return true
}
