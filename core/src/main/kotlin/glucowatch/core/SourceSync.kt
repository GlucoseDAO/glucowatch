package glucowatch.core

/** An account to fetch from. Demo data needs none. */
sealed interface SourceAccount {
    data class Share(val region: Region, val username: String, val password: String) : SourceAccount

    data class Nightscout(val url: String, val token: String, val api: NightscoutApi) : SourceAccount
}

/**
 * The cache [SourceSync] reads and writes, kept per account by the app that owns it. [get] returns
 * null for a missing key and for data of another account. [put] drops the write once the user has
 * moved to another account, so a fetch that finishes late cannot mix old data into the new one.
 * A null value removes the key.
 */
interface SyncCache {
    fun get(key: String): String?

    fun put(values: Map<String, String?>)
}

/**
 * Fetches from Dexcom Share or Nightscout and merges the result into a [SyncCache], under the keys
 * [READINGS], [TREATMENTS] and [LOOP] in [CacheFormat]. The watch app and the phone app both use it.
 */
class SourceSync(
    private val cache: SyncCache,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    fun fetch(account: SourceAccount, chartHours: Int, now: Long = System.currentTimeMillis()) = when (account) {
        is SourceAccount.Share -> fetchShare(account, now)
        is SourceAccount.Nightscout -> fetchNightscout(account, chartHours, now)
    }

    private fun fetchShare(account: SourceAccount.Share, now: Long) {
        val old = CacheFormat.decodeReadings(cache.get(READINGS).orEmpty())
        val since = old.lastOrNull()?.timeMillis
        // Full day on first run, afterwards only what is new (plus a margin for late uploads).
        val minutes = if (since == null) DexcomShareClient.MAX_MINUTES
        else (((now - since) / 60_000) + 15).toInt().coerceIn(15, DexcomShareClient.MAX_MINUTES)
        val maxCount = (minutes / 5 + 2).coerceAtMost(DexcomShareClient.MAX_COUNT)

        val sessionKey = "session:${account.region}:${account.username}"
        val client = DexcomShareClient(account.region, account.username, account.password, cache.get(sessionKey), transport)
        val fresh = client.readings(minutes, maxCount)
        cache.put(mapOf(READINGS to CacheFormat.encodeReadings(merge(old, fresh, now)), sessionKey to client.sessionId))
    }

    /**
     * Readings: the whole day on first run, then only new ones (with a margin for late uploads).
     * Treatments: the chart's window again on every run, since carbs are often entered late or
     * corrected. Loop status: only documents since the last report, merged onto the cached one.
     * The server returns the newest documents first, so the count limit keeps the latest ones.
     */
    private fun fetchNightscout(account: SourceAccount.Nightscout, chartHours: Int, now: Long) {
        val jwtKey = "jwt:${account.token.hashCode()}"
        val client = NightscoutClient(account.url, account.token, account.api, cache.get(jwtKey), transport)
        val firstRun = cache.get(TREATMENTS) == null
        try {
            val old = CacheFormat.decodeReadings(cache.get(READINGS).orEmpty())
            val since = old.lastOrNull()?.timeMillis?.minus(15 * 60_000L) ?: (now - DAY_MS)
            val readings = merge(old, client.readings(since), now)
            cache.put(mapOf(READINGS to CacheFormat.encodeReadings(readings)))

            val oldTreatments = CacheFormat.decodeTreatments(cache.get(TREATMENTS).orEmpty())
            val window = if (firstRun) DAY_MS else maxOf(chartHours, 3) * 3_600_000L
            val treatments = oldTreatments.filter { it.timeMillis in (now - DAY_MS) until (now - window) } +
                client.treatments(now - window)

            val oldLoop = CacheFormat.decodeLoop(cache.get(LOOP).orEmpty())
            // First run looks back a few hours, so a loop that went quiet still shows when it last reported.
            val loopSince = oldLoop?.timeMillis?.minus(60_000L) ?: (now - 6 * 3_600_000L)
            val loop = client.loopStatus(loopSince)?.mergedOnto(oldLoop) ?: oldLoop
            cache.put(
                mapOf(
                    TREATMENTS to CacheFormat.encodeTreatments(treatments.sortedBy { it.timeMillis }),
                    LOOP to CacheFormat.encodeLoop(loop?.takeIf { now - it.timeMillis <= DAY_MS }),
                ),
            )
        } finally {
            cache.put(mapOf(jwtKey to client.jwt))
        }
    }

    companion object {
        const val READINGS = "readings"
        const val TREATMENTS = "treatments"
        const val LOOP = "loop"
        const val DAY_MS = 24 * 3_600_000L

        /** One reading per timestamp, newest data wins, nothing older than a day. */
        fun merge(old: List<GlucoseReading>, fresh: List<GlucoseReading>, now: Long) =
            (old + fresh)
                .associateBy { it.timeMillis }
                .values
                .filter { now - it.timeMillis <= DAY_MS }
                .sortedBy { it.timeMillis }
    }
}
