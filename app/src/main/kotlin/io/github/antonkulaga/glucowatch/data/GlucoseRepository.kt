package io.github.antonkulaga.glucowatch.data

import android.content.Context
import android.content.SharedPreferences
import android.security.NetworkSecurityPolicy
import android.util.Log
import glucowatch.core.CacheFormat
import glucowatch.core.DemoData
import glucowatch.core.DexcomShareClient
import glucowatch.core.GlucoseReading
import glucowatch.core.LoopStatus
import glucowatch.core.NightscoutAddress
import glucowatch.core.NightscoutClient
import glucowatch.core.Prediction
import glucowatch.core.Predictors
import glucowatch.core.Treatment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Snapshot of what the UI and complications show. [treatments] and [loop] come from Nightscout (or demo data). */
data class GlucoseState(
    val settings: Settings,
    val readings: List<GlucoseReading>,
    val prediction: Prediction?,
    val lastError: String?,
    val lastFetchMillis: Long,
    val treatments: List<Treatment> = emptyList(),
    val loop: LoopStatus? = null,
) {
    val latest get() = readings.lastOrNull()

    fun ageMinutes(now: Long = System.currentTimeMillis()): Long? = latest?.let { (now - it.timeMillis) / 60_000 }

    fun isStale(now: Long = System.currentTimeMillis()) = (ageMinutes(now) ?: Long.MAX_VALUE) > STALE_MINUTES

    /** The loop's IOB and COB, or null once it has not reported for [LoopStatus.STALE_MINUTES]. */
    fun freshLoop(now: Long = System.currentTimeMillis()) = loop?.takeUnless { it.isStale(now) }

    companion object {
        const val STALE_MINUTES = 12
    }
}

/** Fetches readings (Share, Nightscout or demo), keeps a 24 h cache on disk and computes the optional forecast. */
class GlucoseRepository(context: Context) {
    private val settingsStore = SettingsStore(context)
    private val cache = context.applicationContext.getSharedPreferences("cache", Context.MODE_PRIVATE)

    fun state(): GlucoseState {
        val settings = settingsStore.load()
        val now = System.currentTimeMillis()
        val demo = settings.source == DataSource.DEMO
        val readings = if (demo) DemoData.readings(now) else CacheFormat.decodeReadings(cached(settings, "readings"))
        val treatments = if (demo) DemoData.treatments(now) else CacheFormat.decodeTreatments(cached(settings, "treatments"))
        val loop = if (demo) DemoData.loopStatus(now) else CacheFormat.decodeLoop(cached(settings, "loop"))
        return GlucoseState(
            settings = settings,
            readings = readings,
            prediction = predict(settings, readings, loop),
            lastError = cache.getString("error", null),
            lastFetchMillis = cache.getLong("fetchedAt", 0),
            treatments = treatments,
            loop = loop,
        )
    }

    /** Downloads new readings. Never throws: errors are stored and shown in the app. */
    suspend fun refresh(): GlucoseState = lock.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            val missing = when (settings.source) {
                DataSource.DEMO -> null
                DataSource.SHARE -> "Enter Dexcom username and password in settings".takeUnless { settings.hasCredentials }
                DataSource.NIGHTSCOUT -> nightscoutProblem(settings.nightscoutUrl)
            }
            // Readings of another account or server never serve as history for this one.
            if (settings.source != DataSource.DEMO && cache.getString(ACCOUNT, null) != settings.accountKey) {
                cache.edit().clear().putString(ACCOUNT, settings.accountKey).apply()
            }
            when {
                settings.source == DataSource.DEMO -> Unit
                missing != null -> saveError(missing)
                else -> runCatching { if (settings.source == DataSource.SHARE) fetchShare(settings) else fetchNightscout(settings) }
                    .onSuccess { saveError(null) }
                    .onFailure {
                        Log.w(TAG, "${settings.source} refresh failed", it)
                        saveError(it.message ?: it.javaClass.simpleName)
                    }
            }
            cache.edit().putLong("fetchedAt", System.currentTimeMillis()).apply()
            state()
        }
    }

    fun clearCache() {
        cache.edit().clear().apply()
    }

    /**
     * Cached data belongs to one account or server ([Settings.accountKey]). Data of another one is
     * ignored, so a fetch that was still running for the old account when the user switched
     * cannot mix its readings into the new one.
     */
    private fun cached(settings: Settings, key: String): String =
        if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.getString(key, "") ?: "" else ""

    /** Writes a fetch result, unless the settings moved to another account while it ran. */
    private fun store(settings: Settings, write: SharedPreferences.Editor.() -> Unit) {
        if (settingsStore.load().accountKey != settings.accountKey) return
        cache.edit().apply(write).putString(ACCOUNT, settings.accountKey).apply()
    }

    private fun fetchShare(settings: Settings) {
        val old = CacheFormat.decodeReadings(cached(settings, "readings"))
        val now = System.currentTimeMillis()
        val since = old.lastOrNull()?.timeMillis
        // Full day on first run, afterwards only what is new (plus a margin for late uploads).
        val minutes = if (since == null) DexcomShareClient.MAX_MINUTES
        else (((now - since) / 60_000) + 15).toInt().coerceIn(15, DexcomShareClient.MAX_MINUTES)
        val maxCount = (minutes / 5 + 2).coerceAtMost(DexcomShareClient.MAX_COUNT)

        val client = DexcomShareClient(
            region = settings.region,
            username = settings.username,
            password = settings.password,
            sessionId = cache.getString("session:${settings.region}:${settings.username}", null),
        )
        val fresh = client.readings(minutes, maxCount)
        store(settings) {
            putString("readings", CacheFormat.encodeReadings(merge(old, fresh, now)))
            putString("session:${settings.region}:${settings.username}", client.sessionId)
        }
    }

    /**
     * Readings: the whole day on first run, then only new ones (with a margin for late uploads).
     * Treatments: the chart's window again on every run, since carbs are often entered late or
     * corrected. Loop status: only documents since the last report, merged onto the cached one.
     * The server returns the newest documents first, so the count limit keeps the latest ones.
     */
    private fun fetchNightscout(settings: Settings) {
        val now = System.currentTimeMillis()
        val jwtKey = "jwt:${settings.accountKey}:${settings.nightscoutToken.hashCode()}"
        val client = NightscoutClient(settings.nightscoutUrl, settings.nightscoutToken, settings.nightscoutApi, cache.getString(jwtKey, null))
        val firstRun = !cache.contains("treatments")
        try {
            val old = CacheFormat.decodeReadings(cached(settings, "readings"))
            val since = old.lastOrNull()?.timeMillis?.minus(15 * 60_000L) ?: (now - DAY_MS)
            val readings = merge(old, client.readings(since), now)
            store(settings) { putString("readings", CacheFormat.encodeReadings(readings)) }

            val oldTreatments = CacheFormat.decodeTreatments(cached(settings, "treatments"))
            val window = if (firstRun) DAY_MS else maxOf(settings.chartHours, 3) * 3_600_000L
            val treatments = oldTreatments.filter { it.timeMillis in (now - DAY_MS) until (now - window) } +
                client.treatments(now - window)

            val oldLoop = CacheFormat.decodeLoop(cached(settings, "loop"))
            // First run looks back a few hours, so a loop that went quiet still shows when it last reported.
            val loopSince = oldLoop?.timeMillis?.minus(60_000L) ?: (now - 6 * 3_600_000L)
            val loop = client.loopStatus(loopSince)?.mergedOnto(oldLoop) ?: oldLoop
            store(settings) {
                putString("treatments", CacheFormat.encodeTreatments(treatments.sortedBy { it.timeMillis }))
                putString("loop", CacheFormat.encodeLoop(loop?.takeIf { now - it.timeMillis <= DAY_MS }))
            }
        } finally {
            store(settings) { putString(jwtKey, client.jwt) }
        }
    }

    /** Android refuses plain http unless the network security config allows the host (debug builds: the emulator's host). */
    private fun nightscoutProblem(url: String): String? {
        if (url.isBlank()) return "Enter the Nightscout address in settings"
        val address = runCatching { NightscoutAddress.parse(url) }.getOrElse { return it.message }
        val host = java.net.URI(address.baseUrl).host
        return if (address.baseUrl.startsWith("http://") && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) {
            "Use an https:// Nightscout address"
        } else {
            null
        }
    }

    private fun merge(old: List<GlucoseReading>, fresh: List<GlucoseReading>, now: Long) =
        (old + fresh)
            .associateBy { it.timeMillis }
            .values
            .filter { now - it.timeMillis <= DAY_MS }
            .sortedBy { it.timeMillis }

    private fun predict(settings: Settings, readings: List<GlucoseReading>, loop: LoopStatus?): Prediction? {
        if (!settings.predictionEnabled || readings.isEmpty()) return null
        if (settings.predictorId == LoopStatus.MODEL_ID && settings.source == DataSource.NIGHTSCOUT) {
            // Only the loop's own forecast; no silent fallback to another model.
            return loop?.prediction(readings.last().timeMillis, settings.horizonMinutes)
        }
        return runCatching { Predictors.byId(settings.predictorId).predict(readings, settings.horizonMinutes) }
            .onFailure { Log.w(TAG, "Predictor ${settings.predictorId} failed", it) }
            .getOrNull()
    }

    private fun saveError(message: String?) {
        cache.edit().putString("error", message).apply()
    }

    companion object {
        private const val TAG = "GlucoRepo"
        private const val DAY_MS = 24 * 3_600_000L
        private const val ACCOUNT = "account"
        private val lock = Mutex()
    }
}
