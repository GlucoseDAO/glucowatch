package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombinedSourceSyncTest {
    private val now = 1_790_000_000_000L
    private class MemoryCache : SyncCache {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(values: Map<String, String?>) { values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v } }
    }

    private fun source(host: String, cache: SyncCache) = SyncSource(host, SourceAccount.Nightscout("https://$host", "", NightscoutApi.V1), cache)

    @Test
    fun `local glucose needs no cloud request and extra sources fetch therapy only`() {
        val requests = mutableListOf<String>()
        val transport = HttpTransport { r ->
            requests += r.url
            assertTrue("pump.test" in r.url)
            assertTrue("/entries" !in r.url)
            HttpResponse(200, if ("/treatments" in r.url) """[{"date":$now,"insulin":2}]""" else "[]")
        }
        val sync = CombinedSourceSync(transport)
        assertTrue(sync.fetch(null, emptyList(), 3, now).isEmpty())
        assertTrue(requests.isEmpty())
        val pump = MemoryCache()
        assertTrue(sync.fetch(null, listOf(source("pump.test", pump)), 3, now).isEmpty())
        assertTrue(requests.isNotEmpty())
        assertEquals(2.0, CombinedSourceSync.read(MemoryCache(), listOf(pump)).treatments.single().insulin)
    }

    @Test
    fun `secondary sensor values never enter the CGM trajectory and therapy merges once`() {
        val primary = MemoryCache()
        val pump = MemoryCache()
        primary.put(mapOf(SourceSync.READINGS to CacheFormat.encodeReadings(listOf(GlucoseReading(now, 123, Trend.Flat)))))
        pump.put(mapOf(SourceSync.READINGS to CacheFormat.encodeReadings(listOf(GlucoseReading(now, 250, Trend.Flat)))))
        val dose = Treatment(now, insulin = 2.0)
        val basal = Treatment(now, insulin = 0.05, automatic = true, insulinKind = InsulinKind.BASAL)
        primary.put(mapOf(SourceSync.TREATMENTS to CacheFormat.encodeTreatments(listOf(dose))))
        pump.put(mapOf(SourceSync.TREATMENTS to CacheFormat.encodeTreatments(listOf(dose, basal)),
            SourceSync.LOOP to CacheFormat.encodeLoop(LoopStatus(now, iob = 1.5))))
        val data = CombinedSourceSync.read(primary, listOf(pump))
        assertEquals(listOf(123), data.readings.map { it.mgdl })
        assertEquals(listOf(dose, basal), data.treatments)
        assertEquals(1.5, data.loop?.iob)
        assertEquals(listOf(dose), CombinedSourceSync.read(primary, emptyList()).treatments, "Deselecting pump removes its data immediately")
        assertTrue(CombinedSourceSync.read(MemoryCache(), listOf(MemoryCache())).treatments.isEmpty(), "New accounts start empty")
    }

    @Test
    fun `a failed CGM still fetches pump treatments without requesting pump glucose`() {
        val requests = mutableListOf<String>()
        val transport = HttpTransport { r ->
            requests += r.url
            when {
                "cgm.test" in r.url -> HttpResponse(503, "")
                "/treatments" in r.url -> HttpResponse(200, """[{"eventType":"Bolus","date":$now,"insulin":2.0}]""")
                "/devicestatus" in r.url -> HttpResponse(200, "[]")
                else -> error("Should not fetch glucose from the pump")
            }
        }
        val primary = MemoryCache(); val pump = MemoryCache()
        val errors = CombinedSourceSync(transport).fetch(source("cgm.test", primary), listOf(source("pump.test", pump)), 3, now)
        assertEquals(1, errors.size)
        assertTrue(errors.single().startsWith("cgm.test:"))
        assertEquals(2.0, CombinedSourceSync.read(primary, listOf(pump)).treatments.single().insulin)
        assertTrue(requests.none { "pump.test" in it && "/entries" in it })
    }

    @Test
    fun `a pump failure leaves the CGM available and names the failing source`() {
        val transport = HttpTransport { r ->
            when {
                "pump.test" in r.url -> HttpResponse(503, "")
                "/entries" in r.url -> HttpResponse(200, """[{"date":$now,"sgv":123}]""")
                else -> HttpResponse(200, "[]")
            }
        }
        val primary = MemoryCache(); val pump = MemoryCache()
        val errors = CombinedSourceSync(transport).fetch(source("cgm.test", primary), listOf(source("pump.test", pump)), 3, now)
        assertTrue(errors.single().startsWith("pump.test:"))
        assertEquals(123, CombinedSourceSync.read(primary, listOf(pump)).readings.single().mgdl)
    }

    @Test
    fun `late secondary fetch respects account-bound cache writes`() {
        val delegate = MemoryCache()
        var active = true
        val guarded = object : SyncCache {
            override fun get(key: String) = delegate.get(key).takeIf { active }
            override fun put(values: Map<String, String?>) { if (active) delegate.put(values) }
        }
        val transport = HttpTransport { r ->
            if ("pump.test" in r.url) active = false // User switches the pump account during the request.
            if ("treatments" in r.url) HttpResponse(200, """[{"date":$now,"insulin":2}]""") else HttpResponse(200, "[]")
        }
        CombinedSourceSync(transport).fetch(source("cgm.test", MemoryCache()), listOf(source("pump.test", guarded)), 3, now)
        assertTrue(delegate.values.isEmpty())
    }
}
