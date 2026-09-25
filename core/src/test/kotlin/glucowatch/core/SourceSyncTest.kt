package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceSyncTest {
    private class MapCache : SyncCache {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(values: Map<String, String?>) = values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }

    private fun reading(t: Long, v: Int) =
        """{"WT":"Date($t)","ST":"Date($t)","DT":"Date($t-0000)","Value":$v,"Trend":"Flat"}"""

    @Test
    fun `share keeps the session and merges new readings onto the cache`() {
        val now = 1_745_000_000_000L
        var batch = listOf(reading(now - 600_000, 100), reading(now - 300_000, 105))
        val minutes = mutableListOf<String>()
        val transport = HttpTransport { request ->
            when {
                "AuthenticatePublisherAccount" in request.url -> HttpResponse(200, "\"11111111-2222-3333-4444-555555555555\"")
                "LoginPublisherAccountById" in request.url -> HttpResponse(200, "\"66666666-7777-8888-9999-000000000000\"")
                else -> {
                    minutes += request.url.substringAfter("minutes=").substringBefore("&")
                    HttpResponse(200, batch.joinToString(",", "[", "]"))
                }
            }
        }
        val cache = MapCache()
        val sync = SourceSync(cache, transport)
        val account = SourceAccount.Share(Region.OUS, "user", "pass")

        sync.fetch(account, chartHours = 3, now = now)
        batch = listOf(reading(now + 300_000, 110))
        sync.fetch(account, chartHours = 3, now = now + 320_000)

        assertEquals(listOf(100, 105, 110), CacheFormat.decodeReadings(cache.map.getValue(SourceSync.READINGS)).map { it.mgdl })
        assertEquals("66666666-7777-8888-9999-000000000000", cache.map["session:OUS:user"])
        // First run asks for the whole day, the second only for what is new.
        assertEquals("1440", minutes[0])
        assertTrue(minutes[1].toInt() < 30)
    }

    @Test
    fun `forecast cache format round-trips`() {
        val p = Prediction("phone", listOf(PredictedPoint(1, 100.5, 90.0, 110.0), PredictedPoint(2, 101.0)))
        assertEquals(p, CacheFormat.decodePrediction(CacheFormat.encodePrediction(p)))
        assertEquals(null, CacheFormat.decodePrediction(CacheFormat.encodePrediction(null)))
    }
}
