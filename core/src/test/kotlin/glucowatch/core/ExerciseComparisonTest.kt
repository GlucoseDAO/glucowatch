package glucowatch.core

import java.time.ZoneId
import kotlin.test.*

class ExerciseComparisonTest {
    @Test fun absentSensorAndPumpDataStayUnavailable() {
        val p = ExerciseComparison.period(emptyList(), emptyList(), ZoneId.of("UTC"), 1000, 2000)
        assertNull(p.mean); assertNull(p.bolus); assertNull(p.basal.units); assertNull(p.carbs)
    }
    @Test fun glucoseUsesObservedSamplesAndHalfOpenBoundaries() {
        val p = ExerciseComparison.period(listOf(GlucoseReading(1000, 70, Trend.None), GlucoseReading(1500, 180, Trend.None), GlucoseReading(2000, 300, Trend.None)), emptyList(), ZoneId.of("UTC"), 1000, 2000)
        assertEquals(125.0, p.mean); assertEquals(2, p.ranges.inRange); assertEquals(2, p.readings.size)
    }
}
