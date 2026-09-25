package io.github.antonkulaga.glucowatch.data

import android.content.Context
import android.content.SharedPreferences
import android.security.NetworkSecurityPolicy
import android.util.Log
import glucowatch.core.CacheFormat
import glucowatch.core.DemoData
import glucowatch.core.GlucoseReading
import glucowatch.core.LoopStatus
import glucowatch.core.NightscoutAddress
import glucowatch.core.Prediction
import glucowatch.core.Predictors
import glucowatch.core.SourceSync
import glucowatch.core.SyncCache
import glucowatch.core.Treatment
import glucowatch.core.link.PhoneLink
import glucowatch.core.link.WatchLinkClient
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Snapshot of what the UI and complications show. [treatments] and [loop] come from Nightscout (or demo data).
 * [relayedSource] is the phone app's own source ("Dexcom Share", "Nightscout") when the phone relays.
 */
data class GlucoseState(
    val settings: Settings,
    val readings: List<GlucoseReading>,
    val prediction: Prediction?,
    val lastError: String?,
    val lastFetchMillis: Long,
    val treatments: List<Treatment> = emptyList(),
    val loop: LoopStatus? = null,
    val relayedSource: String? = null,
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

/** Fetches readings (Share, Nightscout, the phone app or demo), keeps a 24 h cache on disk and computes the optional forecast. */
class GlucoseRepository(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(context)
    private val cache = appContext.getSharedPreferences("cache", Context.MODE_PRIVATE)

    fun state(): GlucoseState {
        val settings = settingsStore.load()
        val now = System.currentTimeMillis()
        val demo = settings.source == DataSource.DEMO
        val readings = if (demo) DemoData.readings(now) else CacheFormat.decodeReadings(cached(settings, SourceSync.READINGS))
        val treatments = if (demo) DemoData.treatments(now) else CacheFormat.decodeTreatments(cached(settings, SourceSync.TREATMENTS))
        val loop = if (demo) DemoData.loopStatus(now) else CacheFormat.decodeLoop(cached(settings, SourceSync.LOOP))
        val phoneForecast = CacheFormat.decodePrediction(cached(settings, FORECAST))
        return GlucoseState(
            settings = settings,
            readings = readings,
            prediction = predict(settings, readings, loop, phoneForecast),
            lastError = cache.getString("error", null),
            lastFetchMillis = cache.getLong("fetchedAt", 0),
            treatments = treatments,
            loop = loop,
            relayedSource = cached(settings, RELAYED_SOURCE).ifEmpty { null }.takeIf { settings.source == DataSource.PHONE },
        )
    }

    /** Downloads new readings. Never throws: errors are stored and shown in the app. */
    suspend fun refresh(): GlucoseState = lock.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            val pairing = PhonePairingStore(appContext).load()?.takeIf { it.phoneId == settings.phoneId }
            val missing = when (settings.source) {
                DataSource.DEMO -> null
                DataSource.SHARE -> "Enter Dexcom username and password in settings".takeUnless { settings.hasCredentials }
                DataSource.NIGHTSCOUT -> nightscoutProblem(settings.nightscoutUrl)
                DataSource.PHONE -> "Pair with the phone in settings".takeIf { pairing == null }
            }
            // Readings of another account or server never serve as history for this one.
            if (settings.source != DataSource.DEMO && cache.getString(ACCOUNT, null) != settings.accountKey) {
                cache.edit().clear().putString(ACCOUNT, settings.accountKey).apply()
            }
            when {
                settings.source == DataSource.DEMO -> Unit
                missing != null -> saveError(missing)
                else -> runCatching { fetch(settings, pairing) }
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

    /** Share and Nightscout through [SourceSync]; the phone app over Bluetooth. */
    private fun fetch(settings: Settings, pairing: PhonePairing?) {
        val account = settings.account
        if (account != null) SourceSync(syncCache(settings)).fetch(account, settings.chartHours) else fetchPhone(settings, pairing!!)
    }

    /** [cached] and [store] for [SourceSync], so its writes follow the same account rule. */
    private fun syncCache(settings: Settings) = object : SyncCache {
        override fun get(key: String): String? =
            if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.getString(key, null) else null

        override fun put(values: Map<String, String?>) = store(settings) { values.forEach { (key, value) -> putString(key, value) } }
    }

    /**
     * The phone sends its whole day on every sync. Readings merge onto the cache while the phone
     * stays on one account ([glucowatch.core.link.LinkSnapshot.upstream]) and replace it when the
     * phone switched. Treatments, loop and forecast are the phone's current ones.
     */
    private fun fetchPhone(settings: Settings, pairing: PhonePairing) {
        val horizon = if (settings.predictionEnabled && settings.predictorId == PhoneLink.MODEL_ID) settings.horizonMinutes else 0
        val watchId = PhonePairingStore(appContext).watchId
        val snapshot = PhoneConnection(appContext).open(pairing.address) { _, input, output ->
            WatchLinkClient(watchId).sync(input, output, pairing.key, horizon)
        }
        val now = System.currentTimeMillis()
        val old = if (cached(settings, UPSTREAM) == snapshot.upstream) CacheFormat.decodeReadings(cached(settings, SourceSync.READINGS)) else emptyList()
        store(settings) {
            putString(SourceSync.READINGS, CacheFormat.encodeReadings(SourceSync.merge(old, snapshot.readings, now)))
            putString(SourceSync.TREATMENTS, CacheFormat.encodeTreatments(snapshot.treatments))
            putString(SourceSync.LOOP, CacheFormat.encodeLoop(snapshot.loop))
            putString(FORECAST, CacheFormat.encodePrediction(snapshot.forecast))
            putString(UPSTREAM, snapshot.upstream)
            putString(RELAYED_SOURCE, snapshot.sourceLabel)
        }
        snapshot.error?.let { throw IOException("Phone: $it") }
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

    private fun predict(settings: Settings, readings: List<GlucoseReading>, loop: LoopStatus?, phoneForecast: Prediction?): Prediction? {
        if (!settings.predictionEnabled || readings.isEmpty()) return null
        if (settings.predictorId == LoopStatus.MODEL_ID && settings.source in LOOP_SOURCES) {
            // Only the loop's own forecast; no silent fallback to another model.
            return loop?.prediction(readings.last().timeMillis, settings.horizonMinutes)
        }
        if (settings.predictorId == PhoneLink.MODEL_ID && settings.source == DataSource.PHONE) {
            // Only the phone's forecast, and only if it starts from the latest reading.
            val last = readings.last().timeMillis
            val first = phoneForecast?.points?.firstOrNull()?.timeMillis ?: return null
            if (first <= last || first - last > 10 * 60_000L) return null
            val end = last + settings.horizonMinutes * 60_000L + 150_000L
            return phoneForecast.points.filter { it.timeMillis <= end }.ifEmpty { null }?.let { Prediction(phoneForecast.modelId, it) }
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
        private const val ACCOUNT = "account"
        private const val FORECAST = "forecast"
        private const val UPSTREAM = "upstream"
        private const val RELAYED_SOURCE = "relayedSource"

        /** Sources that can carry a loop's forecast: Nightscout, and the phone app when it reads Nightscout. */
        val LOOP_SOURCES = setOf(DataSource.NIGHTSCOUT, DataSource.PHONE)
        private val lock = Mutex()
    }
}
