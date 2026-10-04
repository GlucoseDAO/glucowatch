package glucowatch.core

import kotlin.test.*

class ForecastTherapyTest {
    private val step = ForecastFeatures.STEP
    private val origin = 100 * step
    private val context = ForecastTherapy(listOf(TherapyRange(origin - 10 * step, origin)),
        listOf(BasalProfile(0, "UTC", listOf(BasalEntry(0, 1f)))))

    @Test fun `logged absence is zero but uncovered slots stay missing`() {
        val (_, bolus, carbs) = ForecastFeatures.therapy(origin - 12 * step, 13, origin, listOf(Treatment(origin, insulin=2.5, carbs=30.0)), context)
        assertTrue(bolus[0].isNaN()); assertTrue(carbs[1].isNaN())
        assertEquals(0f, bolus[3]); assertEquals(0f, carbs[3])
        assertEquals(2.5f, bolus.last()); assertEquals(30f, carbs.last())
    }
    @Test fun `basal dose uses U per hour and keeps explicitly reported zero pulses`() {
        val (basal, _, _) = ForecastFeatures.therapy(origin-step, 2, origin,
            listOf(Treatment(origin-step, insulin=0.1, insulinKind=InsulinKind.BASAL),
                Treatment(origin, insulin=0.0, insulinKind=InsulinKind.BASAL)), context)
        assertEquals(1.2f, basal[0], .00001f); assertEquals(0f, basal[1])
    }
    @Test fun `temp basal integrates bin edges and cancellation restores scheduled rate`() {
        val treatments = listOf(Treatment(origin-5*step/2, insulinKind=InsulinKind.BASAL, basalRate=2.0,durationMinutes=20.0),
            Treatment(origin-step, insulinKind=InsulinKind.BASAL,durationMinutes=0.0))
        val (basal, _, _) = ForecastFeatures.therapy(origin-2*step,3,origin,treatments,context)
        assertEquals(1.5f,basal[0]); assertEquals(2f,basal[1]); assertEquals(1f,basal[2])
    }
    @Test fun `later treatment and profile cannot influence earlier inputs`() {
        val early = ForecastFeatures.therapy(origin-2*step,3,origin,emptyList(),context)
        val late = ForecastFeatures.therapy(origin-2*step,3,origin,
            listOf(Treatment(origin+1,insulin=90.0,carbs=100.0), Treatment(origin+1,insulinKind=InsulinKind.BASAL,basalRate=9.0,durationMinutes=20.0)),
            context.copy(profiles=context.profiles+BasalProfile(origin+1,"UTC",listOf(BasalEntry(0,9f)))))
        early.zip(late).forEach { (a,b) -> assertContentEquals(a,b) }
    }
    @Test fun `profile switches and local schedule follow effective time and timezone`() {
        val profiles=ForecastTherapy.profiles("""[{"mills":1,"defaultProfile":"x","store":{"x":{"timezone":"Europe/Bucharest","basal":[{"time":"00:00","value":1},{"time":"03:00","value":2}]}}}]""")
        assertEquals(2f,profiles.single().rate(java.time.Instant.parse("2026-10-03T00:30:00Z").toEpochMilli()))
    }
}
