package glucowatch.core

/** An account to fetch from. Demo data needs none. */
sealed interface SourceAccount {
    data class Share(val region: Region, val username: String, val password: String) : SourceAccount

    data class Nightscout(val url: String, val token: String, val api: NightscoutApi) : SourceAccount

    /** Medtronic CareLink, signed in once in a browser; [login] keeps the rotating tokens. */
    data class CareLink(val login: CareLinkLogin) : SourceAccount
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
 * Fetches from Dexcom Share, Nightscout or CareLink and merges the result into a [SyncCache], under
 * the keys [READINGS], [TREATMENTS] and [LOOP] in [CacheFormat]. The watch app and the phone app
 * both use it.
 *
 * A second source that only adds insulin, carbs and active insulin (a pump on CareLink next to a
 * Dexcom sensor) is fetched with `glucose = false` into a cache of its own: readings always come
 * from one source, since two sensors never agree closely enough to share a chart.
 *
 * [retainMs] is how far back the cache keeps readings and treatments. The watch keeps a day. The
 * phone keeps longer so the user can drag back through history: Dexcom Share only ever serves the
 * last 24 hours, so a longer phone history is built up fetch by fetch, while Nightscout's first run
 * backfills as far as one request allows.
 */
class SourceSync(
    private val cache: SyncCache,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val retainMs: Long = DAY_MS,
) {
    init {
        require(retainMs >= DAY_MS) { "Keep at least a day, the most Dexcom Share returns" }
    }

    fun fetch(account: SourceAccount, chartHours: Int, now: Long = System.currentTimeMillis(), glucose: Boolean = true) = when (account) {
        is SourceAccount.Share -> if (glucose) fetchShare(account, now) else Unit
        is SourceAccount.Nightscout -> fetchNightscout(account, chartHours, now, glucose)
        is SourceAccount.CareLink -> fetchCareLink(account, now, glucose)
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
        cache.put(mapOf(READINGS to CacheFormat.encodeReadings(merge(old, fresh, now, retainMs)), sessionKey to client.sessionId))
    }

    /**
     * Readings: the whole day on first run, then only new ones (with a margin for late uploads).
     * Treatments: the chart's window again on every run, since carbs are often entered late or
     * corrected. Loop status: only documents since the last report, merged onto the cached one.
     * The server returns the newest documents first, so the count limit keeps the latest ones.
     */
    private fun fetchNightscout(account: SourceAccount.Nightscout, chartHours: Int, now: Long, glucose: Boolean) {
        val jwtKey = "jwt:${account.token.hashCode()}"
        val client = NightscoutClient(account.url, account.token, account.api, cache.get(jwtKey), transport)
        val firstRun = cache.get(TREATMENTS) == null
        try {
            if (glucose) {
                val old = CacheFormat.decodeReadings(cache.get(READINGS).orEmpty())
                val since = old.lastOrNull()?.timeMillis?.minus(15 * 60_000L) ?: (now - firstRunMs())
                val readings = merge(old, client.readings(since), now, retainMs)
                cache.put(mapOf(READINGS to CacheFormat.encodeReadings(readings)))
            }

            val oldTreatments = CacheFormat.decodeTreatments(cache.get(TREATMENTS).orEmpty())
            val window = if (firstRun) firstRunMs() else maxOf(chartHours, 3) * 3_600_000L
            val treatments = oldTreatments.filter { it.timeMillis in (now - retainMs) until (now - window) } +
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

    /**
     * CareLink answers with the pump's whole last day in one request. Readings merge onto the
     * cache like Share's; boluses and carbs of that day replace the cached ones, older ones stay.
     */
    private fun fetchCareLink(account: SourceAccount.CareLink, now: Long, glucose: Boolean) {
        val client = CareLinkClient(account.login, cache.get(CARELINK_CONFIG), cache.get(CARELINK_SESSION), transport)
        try {
            val data = client.recent(now)
            val values = mutableMapOf<String, String?>()
            if (glucose) {
                val old = CacheFormat.decodeReadings(cache.get(READINGS).orEmpty())
                values[READINGS] = CacheFormat.encodeReadings(merge(old, data.readings, now, retainMs))
            }
            val covered = now - DAY_MS
            val oldTreatments = CacheFormat.decodeTreatments(cache.get(TREATMENTS).orEmpty())
            val treatments = oldTreatments.filter { it.timeMillis in (now - retainMs) until covered } + data.treatments.filter { it.timeMillis >= covered }
            values[TREATMENTS] = CacheFormat.encodeTreatments(treatments.sortedBy { it.timeMillis })
            val loop = data.loop?.mergedOnto(CacheFormat.decodeLoop(cache.get(LOOP).orEmpty())) ?: CacheFormat.decodeLoop(cache.get(LOOP).orEmpty())
            values[LOOP] = CacheFormat.encodeLoop(loop?.takeIf { now - it.timeMillis <= DAY_MS })
            cache.put(values)
        } finally {
            cache.put(mapOf(CARELINK_CONFIG to client.config, CARELINK_SESSION to client.session))
        }
    }

    /**
     * How far a Nightscout first run looks back. One request returns at most
     * [NightscoutClient.MAX_COUNT] readings, about five days of five-minute data, so asking for
     * more would silently return only the newest part.
     */
    private fun firstRunMs() = minOf(retainMs, NIGHTSCOUT_BACKFILL_MS)

    companion object {
        const val READINGS = "readings"
        const val TREATMENTS = "treatments"
        const val LOOP = "loop"
        private const val CARELINK_CONFIG = "carelink:config"
        private const val CARELINK_SESSION = "carelink:session"
        const val DAY_MS = 24 * 3_600_000L
        private const val NIGHTSCOUT_BACKFILL_MS = 5 * DAY_MS

        /** One reading per timestamp, newest data wins, nothing older than [retainMs]. */
        fun merge(old: List<GlucoseReading>, fresh: List<GlucoseReading>, now: Long, retainMs: Long = DAY_MS) =
            (old + fresh)
                .associateBy { it.timeMillis }
                .values
                .filter { now - it.timeMillis <= retainMs }
                .sortedBy { it.timeMillis }
    }
}
