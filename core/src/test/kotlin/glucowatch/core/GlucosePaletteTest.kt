package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlucosePaletteTest {
    @Test fun `in range is green and the extremes are red`() {
        assertEquals(GlucosePalette.GREEN, GlucosePalette.ramp(120))
        assertEquals(GlucosePalette.GREEN, GlucosePalette.ramp(80))
        assertEquals(GlucosePalette.RED, GlucosePalette.ramp(40))
        assertEquals(GlucosePalette.RED, GlucosePalette.ramp(350))
    }

    @Test fun `each anchor returns its own colour exactly`() {
        assertEquals(GlucosePalette.YELLOW, GlucosePalette.ramp(185))
        assertEquals(GlucosePalette.ORANGE, GlucosePalette.ramp(225))
        assertEquals(GlucosePalette.ORANGE, GlucosePalette.ramp(58))
    }

    @Test fun `every colour on the ramp is opaque`() {
        (20..420).forEach { assertEquals(0xFF, (GlucosePalette.ramp(it) ushr 24) and 0xFF, "alpha at $it") }
    }

    @Test fun `the ramp moves away from green as glucose leaves the range`() {
        fun green(c: Int) = (c shr 8) and 0xFF
        fun red(c: Int) = (c shr 16) and 0xFF
        // Going high, red rises; going low, green falls.
        assertTrue(red(GlucosePalette.ramp(250)) > red(GlucosePalette.ramp(170)))
        assertTrue(green(GlucosePalette.ramp(50)) < green(GlucosePalette.ramp(75)))
    }

    @Test fun `withAlpha keeps the colour and replaces only the alpha`() {
        assertEquals(0x334FBF85, GlucosePalette.withAlpha(GlucosePalette.GREEN, 0x33))
    }
}
