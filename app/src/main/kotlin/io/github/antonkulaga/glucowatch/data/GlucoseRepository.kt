package io.github.antonkulaga.glucowatch.data

import android.content.Context
import android.content.SharedPreferences
import android.security.NetworkSecurityPolicy
import android.util.Log
import glucowatch.core.CacheFormat
import glucowatch.core.CareLinkClient
import glucowatch.core.ConnectionCheck
import glucowatch.core.DemoData
import glucowatch.core.DirectAddress
import glucowatch.core.DohResolver
import glucowatch.core.FailureKind
import glucowatch.core.FailureRecord
import glucowatch.core.GlucoseReading
import glucowatch.core.LoopStatus
import glucowatch.core.NetworkFailure
import glucowatch.core.NightscoutAddress
import glucowatch.core.Prediction
import glucowatch.core.Predictors
import glucowatch.core.ProxyEndpoint
import glucowatch.core.SourceAccount
import glucowatch.core.SourceSync
import glucowatch.core.SyncCache
import glucowatch.core.Treatment
import glucowatch.core.ShareException
import glucowatch.core.ShareFallbackPolicy
import glucowatch.core.UrlConnectionTransport
import glucowatch.core.mergeTreatments
import glucowatch.core.Probe
import glucowatch.core.link.PhoneLink
import glucowatch.core.link.WatchLinkClient
import java.net.URI
import java.net.URL
import java.net.URLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Snapshot of what the UI and complications show. [treatments] and [loop] come from Nightscout or
 * CareLink (or demo data), from the main source and from [Settings.extras] together.
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
    /** The last fetch failures, oldest first, for the connection check. */
    val failures: List<FailureRecord> = emptyList(),
) {
    val latest get() = readings.lastOrNull()

    fun ageMinutes(now: Long = System.currentTimeMillis()): Long? = latest?.let { (now - it.timeMillis) / 60_000 }

    fun isStale(now: Long = System.currentTimeMillis()) = latest?.let { now - it.timeMillis > STALE_AFTER_MS } ?: true

    /** The loop's IOB and COB, or null once it has not reported for [LoopStatus.STALE_MINUTES]. */
    fun freshLoop(now: Long = System.currentTimeMillis()) = loop?.takeUnless { it.isStale(now) }

    companion object {
        const val STALE_MINUTES = 10
        const val STALE_AFTER_MS = STALE_MINUTES * 60_000L
    }
}

/**
 * Fetches readings (Share, Nightscout, CareLink, the phone app or demo), keeps a 24 h cache on disk
 * and computes the optional forecast. Sources in [Settings.extras] add their insulin and carbs.
 */
class GlucoseRepository(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(context)
    private val cache = appContext.getSharedPreferences("cache", Context.MODE_PRIVATE)
    private val carelink = CareLinkTokenStore(context)

    fun state(): GlucoseState {
        val settings = settingsStore.load()
        val now = System.currentTimeMillis()
        val demo = settings.source == DataSource.DEMO
        val extras = if (demo) emptyList() else settings.extras
        val readings = if (demo) DemoData.readings(now) else CacheFormat.decodeReadings(cached(settings, SourceSync.READINGS))
        val treatments = if (demo) DemoData.treatments(now) else mergeTreatments(
            (listOf(cached(settings, SourceSync.TREATMENTS)) + extras.map { cachedExtra(settings, it, SourceSync.TREATMENTS) })
                .map(CacheFormat::decodeTreatments),
        )
        // Loop reports of all sources, newest values winning (a pump's IOB next to a sensor's readings).
        val loop = if (demo) DemoData.loopStatus(now) else
            (listOf(cached(settings, SourceSync.LOOP)) + extras.map { cachedExtra(settings, it, SourceSync.LOOP) })
                .mapNotNull(CacheFormat::decodeLoop).sortedBy { it.timeMillis }
                .fold(null as LoopStatus?) { older, newer -> newer.mergedOnto(older) }
        val phoneForecast = CacheFormat.decodePrediction(cached(settings, FORECAST))
        return GlucoseState(
            settings = settings,
            readings = readings,
            prediction = predict(settings, readings, loop, phoneForecast),
            lastError = listOfNotNull(cache.getString("error", null), cache.getString(EXTRA_ERROR, null).takeIf { extras.isNotEmpty() })
                .joinToString("\n").ifEmpty { null },
            lastFetchMillis = cache.getLong("fetchedAt", 0),
            treatments = treatments,
            loop = loop,
            relayedSource = cached(settings, RELAYED_SOURCE).ifEmpty { null }.takeIf { settings.source == DataSource.PHONE },
            failures = NetworkFailure.decode(cache.getString(FAILURES, "").orEmpty()),
        )
    }

    /** Downloads new readings. Never throws: errors are stored and shown in the app. */
    suspend fun refresh(): GlucoseState = lock.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            val pairing = PhonePairingStore(appContext).load()?.takeIf { it.phoneId == settings.phoneId }
            val missing = problemOf(settings, settings.source, pairing)
            // Readings of another account or server never serve as history for this one.
            if (settings.source != DataSource.DEMO && cache.getString(ACCOUNT, null) != settings.accountKey) {
                cache.edit().clear().putString(ACCOUNT, settings.accountKey).apply()
            }
            when {
                settings.source == DataSource.DEMO -> Unit
                missing != null -> saveError(missing)
                else -> runCatching { fetch(settings, pairing) }
                    .onSuccess(::saveError)
                    .onFailure { recordFailure(settings, it) }
            }
            if (settings.source != DataSource.DEMO) refreshExtras(settings)
            cache.edit().putLong("fetchedAt", System.currentTimeMillis()).apply()
            state()
        }
    }

    /** Why [of] cannot be fetched yet, as the message for the settings screen, or null when it can. */
    private fun problemOf(settings: Settings, of: DataSource, pairing: PhonePairing?): String? = when (of) {
        DataSource.DEMO -> null
        DataSource.SHARE -> "Enter Dexcom username and password in settings".takeUnless { settings.hasCredentials }
        DataSource.NIGHTSCOUT -> nightscoutProblem(settings.nightscoutUrl)
        DataSource.CARELINK -> "Sign in to CareLink in the phone app, then get the sign-in in settings"
            .takeIf { carelink.load()?.subject.let { it == null || it != settings.carelinkAccount } }
        DataSource.PHONE -> "Pair with the phone in settings".takeIf { pairing == null }
    }

    /**
     * Insulin, carbs and active insulin from [Settings.extras], each into its own part of the
     * cache. A failing extra never hides the main source's readings; its error is shown beside them.
     */
    private fun refreshExtras(settings: Settings) {
        val prefixes = settings.extras.map { extraPrefix(settings, it) }.toSet()
        cache.all.keys.filter { it.startsWith(EXTRA) && prefixes.none(it::startsWith) }.takeIf { it.isNotEmpty() }?.let { stale ->
            cache.edit().apply { stale.forEach(::remove) }.apply()
        }
        val errors = settings.extras.mapNotNull { extra ->
            problemOf(settings, extra, null)?.let { return@mapNotNull "${extra.label}: $it" }
            runCatching { SourceSync(extraCache(settings, extra)).fetch(settings.account(extra, carelink)!!, settings.chartHours, glucose = false) }
                .exceptionOrNull()
                ?.let {
                    Log.w(TAG, "$extra (extra) refresh failed", it)
                    "${extra.label}: ${NetworkFailure.describe(it)}"
                }
        }
        cache.edit().putString(EXTRA_ERROR, errors.joinToString("\n").ifEmpty { null }).apply()
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

    /**
     * Share, Nightscout and CareLink through [SourceSync]; the phone app over Bluetooth. Returns
     * what the phone reported as failing on its side, which is shown but is no failure of the watch.
     */
    private fun fetch(settings: Settings, pairing: PhonePairing?): String? {
        val account = settings.account(settings.source, carelink)
        when {
            settings.source == DataSource.SHARE -> fetchShareWithWatchFallback(settings)
            account != null -> SourceSync(syncCache(settings)).fetch(account, settings.chartHours)
            else -> return fetchPhone(settings, pairing!!)
        }
        return null
    }

    /**
     * At seven minutes, walk the other ways to Dexcom in turn: another connected route, a resolver
     * the network does not control, then a proxy the user configured.
     *
     * The order follows what each step can actually fix. A second route helps when this one is
     * filtered; DoH helps only when the name did not resolve, so it runs on a [FailureKind.DNS]
     * failure and never speculatively; a proxy is last, since it moves the whole request through
     * a third party.
     */
    private fun fetchShareWithWatchFallback(settings: Settings) {
        val account = settings.account(DataSource.SHARE, carelink)!!
        val primary = runCatching { SourceSync(syncCache(settings)).fetch(account, settings.chartHours) }
        val error = primary.exceptionOrNull()
        // Another route cannot fix bad credentials or an account lockout; avoid extra logins.
        if (error is ShareException.AuthFailed || error is ShareException.TooManyAttempts) primary.getOrThrow()

        val now = System.currentTimeMillis()
        val newest = CacheFormat.decodeReadings(cached(settings, SourceSync.READINGS)).lastOrNull()?.timeMillis ?: 0L
        val lastAttempt = cache.getLong(FALLBACK_ATTEMPT, 0L)
            .takeIf { cache.getString(ACCOUNT, null) == settings.accountKey && it > 0L }
        if (!ShareFallbackPolicy.shouldAttempt(primary.isSuccess, newest.takeIf { it > 0L }, now, lastAttempt)) {
            primary.getOrThrow()
            return
        }
        store(settings) { putLong(FALLBACK_ATTEMPT, now) }

        val alternate = WatchNetwork(appContext).alternate()
        if (alternate != null && tryRoute(settings, account, "alternate route") { alternate.openConnection(it) }) return
        if (resolveAndFetch(settings, account, error)) return

        val proxy = settings.shareProxy.trim().takeIf { it.isNotEmpty() }?.let(ProxyEndpoint::parse)
        val reached = when {
            proxy != null -> tryRoute(settings, account, "configured proxy") { it.openConnection(proxy.javaProxy()) }
            // Without a second route there is nothing else to vary but the attempt itself.
            alternate == null -> tryRoute(settings, account, "default route") { it.openConnection() }
            else -> false
        }
        if (reached) return
        // Keep the original error when every route failed; a successful primary fetch remains valid.
        primary.getOrThrow()
    }

    /**
     * Resolves Dexcom over HTTPS and connects straight to the address, for a network whose own DNS
     * will not answer. Only worth trying after a name lookup actually failed: an address the
     * network blocks stays blocked however the app learned it.
     */
    private fun resolveAndFetch(settings: Settings, account: SourceAccount, error: Throwable?): Boolean {
        if (!settings.shareDoh || NetworkFailure.classify(error) != FailureKind.DNS) return false
        val host = URI(settings.region.baseUrl).host ?: return false
        val addresses = runCatching { DohResolver(settings.dohEndpoint).resolve(host) }
            .onFailure { Log.w(TAG, "DoH lookup failed", it) }
            .getOrDefault(emptyList())
        // A name behind a load balancer has several; the first that answers is enough.
        return addresses.take(MAX_DOH_ADDRESSES).any { address ->
            val direct = runCatching { DirectAddress(address) }.getOrNull()
            direct != null && tryRoute(settings, account, "DoH address $address", direct::openConnection)
        }
    }

    /**
     * One more Share fetch over [open]; true when it brought data in. A rejected login is rethrown
     * instead of retried on the next route: no route can fix it, and repeated attempts lock the
     * Dexcom account.
     */
    private fun tryRoute(settings: Settings, account: SourceAccount, what: String, open: (URL) -> URLConnection): Boolean {
        val result = runCatching {
            SourceSync(syncCache(settings), UrlConnectionTransport(openConnection = open)).fetch(account, settings.chartHours)
        }
        val failure = result.exceptionOrNull()
        if (failure is ShareException.AuthFailed || failure is ShareException.TooManyAttempts) throw failure
        if (result.isSuccess) Log.i(TAG, "Share fallback succeeded via $what")
        else Log.i(TAG, "Share fallback via $what failed: ${NetworkFailure.describe(failure)}")
        return result.isSuccess
    }

    /** [cached] and [store] for [SourceSync], so its writes follow the same account rule. */
    private fun syncCache(settings: Settings) = object : SyncCache {
        override fun get(key: String): String? =
            if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.getString(key, null) else null

        override fun put(values: Map<String, String?>) = store(settings) { values.forEach { (key, value) -> putString(key, value) } }
    }

    /**
     * An extra source's own part of the cache: keys under [extraPrefix], which names the extra's
     * account, so its data is read only while the settings still use that account, and a late
     * write for an extra the user removed or changed is dropped.
     */
    private fun cachedExtra(settings: Settings, extra: DataSource, key: String): String =
        if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.getString(extraPrefix(settings, extra) + key, "") ?: "" else ""

    private fun extraCache(settings: Settings, extra: DataSource) = object : SyncCache {
        private val prefix = extraPrefix(settings, extra)

        override fun get(key: String): String? =
            if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.getString(prefix + key, null) else null

        override fun put(values: Map<String, String?>) {
            val now = settingsStore.load()
            if (extra !in now.extras || extraPrefix(now, extra) != prefix) return
            store(settings) { values.forEach { (key, value) -> putString(prefix + key, value) } }
        }
    }

    private fun extraPrefix(settings: Settings, extra: DataSource) = "$EXTRA${settings.keyOf(extra)}|"

    /**
     * The phone sends its whole day on every sync. Readings merge onto the cache while the phone
     * stays on one account ([glucowatch.core.link.LinkSnapshot.upstream]) and replace it when the
     * phone switched. Treatments, loop and forecast are the phone's current ones, already merged
     * from the phone's glucose source and its extras (a Dexcom sensor and a CareLink pump).
     * Returns the phone's own fetch error: one source failing there still relays the other.
     */
    private fun fetchPhone(settings: Settings, pairing: PhonePairing): String? {
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
        return snapshot.error?.let { "Phone: $it" }
    }

    /** Android refuses plain http unless the network security config allows the host (debug builds: the emulator's host). */
    private fun nightscoutProblem(url: String): String? {
        if (url.isBlank()) return "Enter the Nightscout address in settings"
        val address = runCatching { NightscoutAddress.parse(url) }.getOrElse { return it.message }
        val host = URI(address.baseUrl).host
        return if (address.baseUrl.startsWith("http://") && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) {
            "Use an https:// Nightscout address"
        } else {
            null
        }
    }

    private fun predict(settings: Settings, readings: List<GlucoseReading>, loop: LoopStatus?, phoneForecast: Prediction?): Prediction? {
        if (!settings.predictionEnabled || readings.isEmpty()) return null
        if (settings.predictorId == LoopStatus.MODEL_ID && hasLoopForecast(settings)) {
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

    /**
     * Keeps the layer the fetch died at, not only its text. Which layer it is says whether another
     * route could have helped: a name that does not resolve, a connection that never opens and a
     * handshake that is refused each point at a different thing on the way to the server.
     */
    private fun recordFailure(settings: Settings, error: Throwable) {
        val kind = NetworkFailure.classify(error)
        Log.w(TAG, "${settings.source} refresh failed (${kind.name})", error)
        saveError(NetworkFailure.describe(error))
        val record = FailureRecord(System.currentTimeMillis(), kind, WatchNetwork(appContext).transport(), error.message.orEmpty())
        cache.edit().putString(FAILURES, NetworkFailure.append(cache.getString(FAILURES, "").orEmpty(), record)).apply()
    }

    /** The layered check behind the settings screen's "Connection check". */
    fun checkConnection(): Pair<List<Pair<String, String>>, List<Probe>> {
        val settings = settingsStore.load()
        val facts = WatchNetwork(appContext).facts()
        val target = when (settings.source) {
            DataSource.SHARE -> settings.region.baseUrl
            DataSource.NIGHTSCOUT -> runCatching { NightscoutAddress.parse(settings.nightscoutUrl).baseUrl }.getOrNull()
            DataSource.CARELINK -> if (carelink.load()?.country == "US") "https://carelink.minimed.com/" else CareLinkClient.DISCOVERY_URL
            else -> null
        } ?: return facts to emptyList()
        return facts to ConnectionCheck().run(target, DohResolver(settings.dohEndpoint))
    }

    companion object {
        private const val TAG = "GlucoRepo"
        private const val ACCOUNT = "account"
        private const val FORECAST = "forecast"
        private const val UPSTREAM = "upstream"
        private const val RELAYED_SOURCE = "relayedSource"
        private const val FALLBACK_ATTEMPT = "fallbackAttempt"
        private const val FAILURES = "failures"
        private const val EXTRA = "also:"
        private const val EXTRA_ERROR = "extraError"
        private const val MAX_DOH_ADDRESSES = 3

        /** Sources that can carry a loop's forecast: Nightscout, and the phone app when it reads Nightscout. */
        val LOOP_SOURCES = setOf(DataSource.NIGHTSCOUT, DataSource.PHONE)

        /** A loop forecast can arrive from the main source or from Nightscout as an extra. */
        fun hasLoopForecast(settings: Settings) = settings.source in LOOP_SOURCES || DataSource.NIGHTSCOUT in settings.extras
        private val lock = Mutex()
    }
}
