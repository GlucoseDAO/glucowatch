package glucowatch.core

import kotlin.test.*

class GlucoseAlertsTest {
    private val now = 1_800_000_000_000L
    private val settings = GlucoseAlertSettings(enabled = true)
    private fun readings(vararg values: Int) = values.mapIndexed { i, v -> GlucoseReading(now - (values.lastIndex - i) * 300_000L, v, Trend.None) }
    private fun alert(vararg v: Int) = GlucoseAlerts.evaluate(readings(*v), null, settings, now)
    @Test fun disabledStaleFutureAndInvalidDataNeverAlert() {
        assertNull(GlucoseAlerts.evaluate(readings(45), null, settings.copy(enabled = false), now))
        assertNull(GlucoseAlerts.evaluate(readings(45), null, settings, now + GlucoseHistory.FRESH_MS + 1))
        assertNull(GlucoseAlerts.evaluate(readings(45).map { it.copy(timeMillis = now + 1000) }, null, settings, now))
        assertNull(alert(0)); assertNull(alert(999))
    }
    @Test fun urgentLowDoesNotRequireForecastOrSlope() { assertEquals(GlucoseAlertKind.URGENT_LOW, alert(54)?.kind); assertNull(alert(55)) }
    @Test fun rapidFallRequiresBothLowStartingValueAndRecentObservedSlope() {
        assertEquals(GlucoseAlertKind.FALLING_LOW, alert(100, 90)?.kind)
        assertNull(alert(120, 100)); assertNull(alert(91, 90)); assertNull(alert(90, 100)); assertNull(alert(80))
        assertNull(GlucoseAlerts.evaluate(listOf(GlucoseReading(now - 600_000, 110, Trend.None), GlucoseReading(now, 80, Trend.None)), null, settings, now))
    }
    @Test fun highUsesStrict220ThresholdAndIndependentToggle() {
        assertNull(alert(220)); assertEquals(GlucoseAlertKind.HIGH, alert(221)?.kind)
        assertNull(GlucoseAlerts.evaluate(readings(250), null, settings.copy(highEnabled = false), now))
        assertEquals(GlucoseAlertKind.HIGH, GlucoseAlerts.evaluate(readings(250), null, settings.copy(lowEnabled = false), now)?.kind)
    }
    @Test fun freshForecastCanWarnEarlierButOldOrDistantForecastCannot() {
        val forecast = Prediction("test", listOf(PredictedPoint(now + 300_000, 230.0)))
        val result = GlucoseAlerts.evaluate(readings(190, 200), forecast, settings, now)
        assertEquals(GlucoseAlertKind.HIGH, result?.kind); assertEquals(true, result?.predicted)
        assertNull(GlucoseAlerts.evaluate(readings(190, 200), forecast.copy(points = listOf(PredictedPoint(now - 1000, 230.0))), settings, now))
        assertNull(GlucoseAlerts.evaluate(readings(190, 200), forecast.copy(points = listOf(PredictedPoint(now + 3600_000, 230.0))), settings, now))
        assertNull(GlucoseAlerts.evaluate(readings(210, 200), forecast, settings, now))
    }
    @Test fun invalidSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { settings.copy(fallMgdlPerMinute = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { settings.copy(lowMgdl = 40) }
        assertFailsWith<IllegalArgumentException> { settings.copy(highMgdl = 100) }
    }
}
