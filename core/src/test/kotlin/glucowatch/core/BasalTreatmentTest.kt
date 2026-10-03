package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasalTreatmentTest {
    private fun parse(fields: String) = NightscoutClient.parseTreatment(
        Json.parseToJsonElement("""{"eventType":"Temp Basal","date":1000,$fields}""").jsonObject,
    )

    @Test
    fun `zero rate suspension and temp cancellation are retained without becoming boluses`() {
        val suspension = parse(""""absolute":0,"duration":30,"insulin":5""")!!
        assertEquals(0.0, suspension.basalRate)
        assertEquals(0.0, suspension.insulin)
        assertFalse(suspension.isBolus)
        val cancellation = parse(""""duration":0""")!!
        assertEquals("Temp basal ended", cancellation.basalDescription())
        assertTrue(cancellation.isBasal)
    }

    @Test
    fun `percentage adjustment stays a percentage without a basal profile`() {
        val reduced = parse(""""percent":-50,"duration":30""")!!
        assertEquals(-50.0, reduced.basalPercent)
        assertNull(reduced.basalRate)
        assertEquals("Temp basal -50% for 30 min", reduced.basalDescription())
        assertEquals(0.0, parse(""""percent":0,"duration":30""")?.basalPercent)
        assertEquals(-100.0, parse(""""percent":-100,"duration":30""")?.basalPercent)
        assertEquals(1.2, parse(""""rate":1.2,"duration":30""")?.basalRate)
    }

    @Test
    fun `invalid basal entries do not masquerade as boluses`() {
        listOf(
            """"absolute":-1,"duration":30,"insulin":2""",
            """"absolute":1,"duration":-30""",
            """"absolute":"NaN","duration":30""",
            """"percent":-101,"duration":30""",
            """"duration":30""",
            """"absolute":1,"duration":30,"isValid":false""",
        ).forEach { assertNull(parse(it), it) }
    }

    @Test
    fun `an active temp says it is on, the rate or percent, and the time left`() {
        val start = 1_000_000L
        val percent = Treatment(start, insulinKind = InsulinKind.BASAL, basalPercent = 130.0,
            percentOfProfile = true, durationMinutes = 90.0)
        val now = start + 30 * 60_000L
        assertEquals(percent, listOf(percent).activeTempBasal(now))
        assertEquals("Temp basal on · 130% · 1h left", percent.activeTempLabel(now))
        val units = Treatment(start, insulinKind = InsulinKind.BASAL, basalRate = 1.2, durationMinutes = 40.0)
        assertEquals("Temp basal on · 1.2 U/h · 10m left", units.activeTempLabel(start + 30 * 60_000))
        assertEquals("Temp basal on · 1.2 U/h · <1m left", units.activeTempLabel(start + 40 * 60_000 - 1))
        assertNull(listOf(percent).activeTempBasal(start + 90 * 60_000))
        val cancel = Treatment(start + 10 * 60_000, insulinKind = InsulinKind.BASAL, durationMinutes = 0.0)
        assertNull(listOf(percent, cancel).activeTempBasal(start + 20 * 60_000))
        val reported = Treatment(start + 5 * 60_000, insulinKind = InsulinKind.BASAL, basalRate = 0.8)
        assertEquals(percent, listOf(percent, reported).activeTempBasal(now))
        assertEquals(listOf(percent), CacheFormat.decodeTreatments(CacheFormat.encodeTreatments(listOf(percent))))
    }

    @Test
    fun `basal injections and pump pulses never replace last manual bolus`() {
        val bolus = Treatment(1, insulin = 2.0)
        val basal = Treatment(2, insulin = 10.0, insulinKind = InsulinKind.BASAL)
        val pulse = Treatment(3, insulin = 0.05, automatic = true, insulinKind = InsulinKind.BASAL)
        val future = basal.copy(timeMillis = 50)
        val list = listOf(bolus, basal, pulse, future)
        assertEquals(bolus, list.lastManualBolus(10))
        assertEquals(pulse, list.lastBasal(10))
        assertNull(listOf(basal, pulse).lastManualBolus(10))
    }

    @Test
    fun `merge preserves separate kinds and nearby basal pulses`() {
        val bolus = Treatment(1000, insulin = 0.05, automatic = true)
        val basal = bolus.copy(insulinKind = InsulinKind.BASAL)
        val next = basal.copy(timeMillis = 61_000)
        assertEquals(listOf(bolus, basal, next), mergeTreatments(listOf(listOf(bolus, basal, next), listOf(basal))))
    }

    @Test
    fun `cache and relay representation preserve basal dose rate percentage and cancellation`() {
        val entries = listOf(
            Treatment(1, insulin = 2.0),
            Treatment(2, insulin = 8.0, insulinKind = InsulinKind.BASAL),
            Treatment(3, insulin = 0.05, automatic = true, insulinKind = InsulinKind.BASAL),
            parse(""""absolute":0,"duration":30""")!!,
            parse(""""percent":-50,"duration":30""")!!,
            parse(""""duration":0""")!!,
        )
        assertEquals(entries, CacheFormat.decodeTreatments(CacheFormat.encodeTreatments(entries)))
        assertEquals(listOf(Treatment(1, insulin = 2.0)), CacheFormat.decodeTreatments("1,2.0,0.0,0"))
        assertTrue(CacheFormat.decodeTreatments("1,2.0,0.0,0,UNKNOWN,,,").isEmpty())
    }

    @Test
    fun `v3 requests all basal fields and parses them`() {
        val transport = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse {
                val query = java.net.URLDecoder.decode(request.url, "UTF-8")
                assertTrue("absolute,rate,percent,duration" in query)
                return HttpResponse(200, """{"result":[{"eventType":"Temp Basal","date":1000,"absolute":0.8,"duration":30}]}""")
            }
        }
        val client = NightscoutClient("https://example.test", "watch-0123456789abcdef", NightscoutApi.V3, jwt = "cached", transport = transport)
        assertEquals(0.8, client.treatments(0).single().basalRate)
    }
}
