package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DexcomNotificationTest {
    private val now = 1_790_000_000_000L
    private fun parse(vararg fields: String, pkg: String = "com.dexcom.g6", time: Long = now) =
        DexcomNotification.parse(pkg, fields.toList(), time, now)

    @Test fun `reads standard mgdl notification and double arrow`() {
        assertEquals(GlucoseReading(now, 123, Trend.DoubleUp), parse("123 mg/dL ↑↑", "Dexcom G6"))
        assertEquals(Trend.DoubleDown, parse("123 ⇊")?.trend)
    }

    @Test fun `custom layout splits value units arrow and clock across views`() {
        assertEquals(GlucoseReading(now, 99, Trend.Flat),
            parse("5,5", "mmol/L", "→", "14:35", "G6", pkg = "com.dexcom.g6.region1.mmol"))
    }

    @Test fun `explicit units override package defaults without using display settings`() {
        assertEquals(99, parse("5.5 mmol/L")?.mgdl)
        assertEquals(180, parse("180 mg/dL", pkg = "com.dexcom.g6.region1.mmol")?.mgdl)
        assertEquals(99, parse("5.5", pkg = "com.dexcom.g6.region1.mmol")?.mgdl)
    }

    @Test fun `never treats alert thresholds countdowns or connection warnings as current glucose`() {
        for (fields in listOf(
            listOf("Low alert at 70 mg/dL"), listOf("High alert", "180 mg/dL"),
            listOf("Urgent Low Soon", "80"), listOf("120", "Signal loss"),
            listOf("LOW"), listOf("HIGH"), listOf("G6 app is running"),
            listOf("12:30"), listOf("5 minutes ago"), listOf("Sensor warmup", "120"),
            listOf("35 mg/dL"), listOf("401 mg/dL"), listOf("5.5"),
        )) assertNull(parse(*fields.toTypedArray()), fields.toString())
        assertNull(parse("12", pkg = "com.dexcom.g6.region1.mmol"))
    }

    @Test fun `ambiguous values or units are rejected but repeated views are fine`() {
        assertNull(parse("120", "180"))
        assertNull(parse("120 mg/dL", "6.7 mmol/L"))
        assertEquals(120, parse("120", "120 mg/dL", "→")?.mgdl)
        assertEquals(Trend.None, parse("120", "↑", "↓")?.trend)
    }

    @Test fun `package spoof suffixes Follow and unrelated apps are not collected`() {
        for (pkg in listOf("com.dexcom.g6.fake", "com.dexcom.follow", "com.example.chat", "com.dexcom.g7")) {
            assertFalse(DexcomNotification.supports(pkg))
            assertNull(parse("120 mg/dL", pkg = pkg))
        }
        assertTrue(DexcomNotification.supports("com.dexcom.g6.region11.mmol"))
    }

    @Test fun `stale missing and future post times never become fresh readings`() {
        assertNull(parse("120", time = 0))
        assertNull(parse("120", time = now + 1))
        assertNull(parse("120", time = now - DexcomNotification.MAX_AGE_MS - 1))
        assertEquals(now - 60_000, parse("120", time = now - 60_000)?.timeMillis)
    }

    @Test fun `duplicates and repaints are dropped but equal readings five minutes later are retained`() {
        val reading = GlucoseReading(now, 120, Trend.None)
        assertTrue(DexcomNotification.isNew(reading, null))
        assertFalse(DexcomNotification.isNew(reading, reading))
        assertFalse(DexcomNotification.isNew(reading.copy(timeMillis = now - 1), reading))
        assertFalse(DexcomNotification.isNew(reading.copy(timeMillis = now + 5_000), reading))
        assertTrue(DexcomNotification.isNew(reading.copy(timeMillis = now + 300_000), reading))
    }
}
