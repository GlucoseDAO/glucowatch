package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.content.SharedPreferences
import android.security.NetworkSecurityPolicy
import android.util.Log
import glucowatch.core.CacheFormat
import glucowatch.core.DemoData
import glucowatch.core.GlucoseReading
import glucowatch.core.LoopStatus
import glucowatch.core.NightscoutAddress
import glucowatch.core.Predictors
import glucowatch.core.SourceSync
import glucowatch.core.SyncCache
import glucowatch.core.Treatment
import glucowatch.core.link.LinkCrypto
import glucowatch.core.link.LinkSnapshot
import glucowatch.core.link.LinkSource
import glucowatch.core.link.PhoneLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

/** What the phone screen shows and what it relays. */
data class PhoneState(
    val settings: PhoneSettings,
    val readings: List<GlucoseReading>,
    val treatments: List<Treatment>,
    val loop: LoopStatus?,
    val lastError: String?,
    val lastFetchMillis: Long,
) {
    val latest get() = readings.lastOrNull()
}

/**
 * Fetches the phone's own source through [SourceSync] and keeps a day of it, like the watch does.
 * A watch's sync triggers the fetch, so the phone schedules nothing of its own.
 */
class PhoneRepository(context: Context) {
    private val settingsStore = PhoneSettingsStore(context)
    private val cache = context.applicationContext.getSharedPreferences("cache", Context.MODE_PRIVATE)

    fun state(): PhoneState {
        val settings = settingsStore.load()
        val now = System.currentTimeMillis()
        val demo = settings.source == LinkSource.DEMO
        return PhoneState(
            settings = settings,
            readings = if (demo) DemoData.readings(now) else CacheFormat.decodeReadings(cached(settings, SourceSync.READINGS)),
            treatments = if (demo) DemoData.treatments(now) else CacheFormat.decodeTreatments(cached(settings, SourceSync.TREATMENTS)),
            loop = if (demo) DemoData.loopStatus(now) else CacheFormat.decodeLoop(cached(settings, SourceSync.LOOP)),
            lastError = cache.getString("error", null),
            lastFetchMillis = cache.getLong("fetchedAt", 0),
        )
    }

    /** Fetches unless the last fetch for this account is younger than [maxAgeMs]. Never throws. */
    suspend fun refresh(maxAgeMs: Long = 0): PhoneState = lock.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            val now = System.currentTimeMillis()
            if (cache.getString(ACCOUNT, null) != settings.accountKey) {
                cache.edit().clear().putString(ACCOUNT, settings.accountKey).apply()
            }
            val account = settings.account
            val recent = now - cache.getLong("fetchedAt", 0) < maxAgeMs
            when {
                account == null || recent -> Unit
                else -> {
                    val missing = problem(settings)
                    val result = if (missing != null) Result.failure(IllegalStateException(missing))
                    else runCatching { SourceSync(syncCache(settings)).fetch(account, CHART_HOURS) }
                    result.exceptionOrNull()?.let { Log.w(TAG, "${settings.source} refresh failed", it) }
                    cache.edit()
                        .putString("error", result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
                        .putLong("fetchedAt", now)
                        .apply()
                }
            }
            state()
        }
    }

    fun clearCache() {
        cache.edit().clear().apply()
    }

    /** For a watch's sync: fresh data (at most [FRESH_MS] old) and, if asked, a forecast from the phone's model. */
    fun snapshot(horizonMinutes: Int): LinkSnapshot {
        val state = runBlocking { refresh(maxAgeMs = FRESH_MS) }
        val settings = state.settings
        val forecast = if (horizonMinutes <= 0 || state.readings.isEmpty()) null
        else runCatching { Predictors.byId(settings.predictorId).predict(state.readings, horizonMinutes) }
            .onFailure { Log.w(TAG, "Predictor ${settings.predictorId} failed", it) }
            .getOrNull()
        return LinkSnapshot(
            upstream = LinkCrypto.sha256(settings.accountKey.toByteArray()).copyOf(12).let(Base64.getEncoder()::encodeToString),
            sourceLabel = settings.sourceLabel,
            readings = state.readings,
            treatments = state.treatments,
            loop = state.loop,
            forecast = forecast,
            error = state.lastError.takeIf { settings.source != LinkSource.DEMO },
        )
    }

    private fun problem(settings: PhoneSettings): String? = when (settings.source) {
        LinkSource.DEMO -> null
        LinkSource.SHARE -> "Enter the Dexcom username and password".takeIf { settings.username.isBlank() || settings.password.isBlank() }
        LinkSource.NIGHTSCOUT -> {
            val url = settings.nightscoutUrl
            if (url.isBlank()) "Enter the Nightscout address"
            else runCatching { NightscoutAddress.parse(url) }.fold(
                onSuccess = { address ->
                    val host = java.net.URI(address.baseUrl).host
                    "Use an https:// Nightscout address".takeIf {
                        address.baseUrl.startsWith("http://") && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
                    }
                },
                onFailure = { it.message },
            )
        }
    }

    /** Same rule as the watch: data belongs to one [PhoneSettings.accountKey], a late write for another is dropped. */
    private fun cached(settings: PhoneSettings, key: String): String =
        if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.getString(key, "") ?: "" else ""

    private fun store(settings: PhoneSettings, write: SharedPreferences.Editor.() -> Unit) {
        if (settingsStore.load().accountKey != settings.accountKey) return
        cache.edit().apply(write).putString(ACCOUNT, settings.accountKey).apply()
    }

    private fun syncCache(settings: PhoneSettings) = object : SyncCache {
        override fun get(key: String): String? =
            if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.getString(key, null) else null

        override fun put(values: Map<String, String?>) = store(settings) { values.forEach { (key, value) -> putString(key, value) } }
    }

    companion object {
        private const val TAG = "GlucoPhoneRepo"
        private const val ACCOUNT = "account"

        /** The watch shows up to this many hours; the phone keeps the same treatment window. */
        private const val CHART_HOURS = 6

        /** A watch that polls every minute while a reading is late still costs at most two fetches a minute. */
        private const val FRESH_MS = 30_000L
        private val lock = Mutex()
    }
}

/** Watches this phone has paired with: watch id (hex) to its name and key. */
class PairedWatches(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("watches", Context.MODE_PRIVATE)
    private val link = context.applicationContext.getSharedPreferences("link", Context.MODE_PRIVATE)

    class Watch(val id: String, val name: String, val key: ByteArray)

    /** Random, made once per install; sent to the watch when pairing. */
    val phoneId: ByteArray
        get() = link.getString("phoneId", null)?.let(Base64.getDecoder()::decode)
            ?: LinkCrypto.randomBytes(PhoneLink.ID_BYTES).also { link.edit().putString("phoneId", Base64.getEncoder().encodeToString(it)).apply() }

    fun all(): List<Watch> = prefs.all.mapNotNull { (id, value) ->
        val text = value as? String ?: return@mapNotNull null
        Watch(id, text.substringAfter('|'), Base64.getDecoder().decode(text.substringBefore('|')))
    }.sortedBy { it.name }

    fun keyFor(watchId: ByteArray): ByteArray? = all().firstOrNull { it.id == watchId.toHex() }?.key

    fun add(watchId: ByteArray, name: String, key: ByteArray) {
        prefs.edit().putString(watchId.toHex(), Base64.getEncoder().encodeToString(key) + "|" + name).apply()
    }

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }
}

fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
