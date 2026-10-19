package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.content.SharedPreferences
import android.security.NetworkSecurityPolicy
import android.util.Log
import glucowatch.core.CacheFormat
import glucowatch.core.CombinedSourceSync
import glucowatch.core.ExportCsv
import glucowatch.core.ExportLog
import glucowatch.core.ExportRow
import glucowatch.core.GlucoseReport
import glucowatch.core.SyncSource
import glucowatch.core.DemoData
import glucowatch.core.DexcomNotification
import glucowatch.core.ExportWindow
import glucowatch.core.GlucoseReading
import glucowatch.core.LoopStatus
import glucowatch.core.NightscoutAddress
import glucowatch.core.OnnxPredictor
import glucowatch.core.ForecastReviewer
import glucowatch.core.GlucoseDaoPredictor
import glucowatch.core.HuggingFaceModel
import glucowatch.core.Prediction
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
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** What the phone screen shows and what it relays. */
data class PhoneState(
    val settings: PhoneSettings,
    val readings: List<GlucoseReading>,
    val treatments: List<Treatment>,
    val loop: LoopStatus?,
    val lastError: String?,
    val lastFetchMillis: Long,
    val therapy: glucowatch.core.ForecastTherapy = glucowatch.core.ForecastTherapy(),
) {
    val latest get() = readings.lastOrNull()
}

/**
 * Fetches the phone's own source through [SourceSync] and keeps a day of it, like the watch does.
 * The visible dashboard and a watch's sync trigger fetches; there is no background polling job.
 */
class PhoneRepository(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = PhoneSettingsStore(context)
    private val modelStore = PhoneModelStore(context)
    private val cache = context.applicationContext.getSharedPreferences("cache", Context.MODE_PRIVATE)
    private val carelink = PhoneCareLinkStore(context)

    fun state(): PhoneState {
        val settings = settingsStore.load()
        val now = System.currentTimeMillis()
        val demo = settings.source == LinkSource.DEMO
        val combined = CombinedSourceSync.read(syncCache(settings), settings.extras.map { syncCache(settings, it) })
        return PhoneState(
            settings = settings,
            readings = if (demo) DemoData.readings(now, HISTORY_HOURS) else combined.readings,
            treatments = if (demo) DemoData.treatments(now, HISTORY_HOURS) else combined.treatments,
            loop = if (demo) DemoData.loopStatus(now) else combined.loop,
            lastError = listOfNotNull(
                notificationProblem(settings, combined.readings.lastOrNull(), now),
                cached(settings, "error").takeIf { cache.getString(PLAN, null) == settings.configurationKey }?.ifEmpty { null },
            ).joinToString("\n").ifEmpty { null },
            lastFetchMillis = cached(settings, "fetchedAt").toLongOrNull() ?: 0L,
            therapy = combined.therapy,
        )
    }

    /** Fetches unless the last fetch for this account is younger than [maxAgeMs]. Never throws. */
    suspend fun refresh(maxAgeMs: Long = 0): PhoneState = lock.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            val now = System.currentTimeMillis()
            if (cache.getString(ACCOUNT, null) != settings.accountKey) {
                store(settings) { clear() }
            }
            val recent = cache.getString(PLAN, null) == settings.configurationKey &&
                now - (cached(settings, "fetchedAt").toLongOrNull() ?: 0L) < maxAgeMs
            when {
                settings.source == LinkSource.DEMO || recent -> Unit
                else -> {
                    val prefixes = settings.extras.map { prefix(settings, it) }
                    store(settings) {
                        cache.all.keys.filter { it.startsWith(EXTRA) && prefixes.none(it::startsWith) }.forEach(::remove)
                    }
                    fun selected(of: LinkSource) = SyncSource(settings.label(of), settings.account(of, carelink)!!,
                        syncCache(settings, of), problem(settings, of))
                    val errors = CombinedSourceSync(retainMs = HISTORY_MS, includeProfiles = settings.predictorId == OnnxPredictor.ID)
                        .fetch(if (settings.usesDexcomNotifications) null else selected(settings.source),
                            settings.extras.map(::selected), CHART_HOURS, now)
                    backfillNotifications(settings, now)
                    store(settings) {
                        putString("error", errors.joinToString("\n").ifEmpty { null })
                        putLong("fetchedAt", now)
                        putString(PLAN, settings.configurationKey)
                    }
                }
            }
            state()
        }
    }

    fun clearCache() {
        cache.edit().clear().apply()
    }

    /**
     * Changes the configured source under the fetch/listener lock. When requested, glucose already
     * on the chart is moved to the new account cache; treatments and connection state are fetched
     * anew from the selected sources. Old fetches and notification callbacks cannot write across
     * this transaction because they use this lock and [store]'s configuration guard.
     */
    suspend fun changeSettings(new: PhoneSettings, keepGlucoseHistory: Boolean, updateLogin: (() -> Unit)? = null) = lock.withLock {
        val old = settingsStore.load()
        // Config imports replace a pump session under the same lock as source fetches.
        updateLogin?.invoke()
        if (old.accountKey == new.accountKey) {
            settingsStore.save(new)
            return@withLock
        }
        val history = if (keepGlucoseHistory && old.source != LinkSource.DEMO && new.source != LinkSource.DEMO) {
            CombinedSourceSync.read(syncCache(old), emptyList()).readings
        } else emptyList()
        settingsStore.save(new)
        cache.edit().clear().putString(ACCOUNT, new.accountKey).apply()
        if (history.isNotEmpty()) {
            syncCache(new).put(mapOf(SourceSync.READINGS to CacheFormat.encodeReadings(history)))
        }
    }

    /** Uses the same lock and account guard as remote fetches; a queued callback cannot cross a source switch. */
    suspend fun acceptNotification(settings: PhoneSettings, packageName: String, reading: GlucoseReading?) = lock.withLock {
        if (!settings.usesDexcomNotifications || settingsStore.load().configurationKey != settings.configurationKey ||
            !DexcomNotificationService.hasAccess(appContext)) return@withLock
        if (cache.getString(ACCOUNT, null) != settings.accountKey) store(settings) { clear() }
        val origin = cached(settings, "notificationPackage")
        if (origin.isNotEmpty() && origin != packageName) {
            store(settings) { putString("notificationError", "More than one G6 app is sending readings. Keep Quick Glance enabled in only one app.") }
            return@withLock
        }
        if (reading == null) {
            store(settings) { putString("notificationError", "G6 notification has no readable current glucose. Check Quick Glance in the G6 app.") }
            return@withLock
        }
        val now = System.currentTimeMillis()
        if (now - reading.timeMillis > DexcomNotification.MAX_AGE_MS) return@withLock
        val sourceCache = syncCache(settings)
        val previous = CacheFormat.decodeReadings(sourceCache.get(SourceSync.READINGS).orEmpty())
        if (!DexcomNotification.isNew(reading, previous.lastOrNull())) return@withLock
        sourceCache.put(mapOf(SourceSync.READINGS to CacheFormat.encodeReadings(
            SourceSync.merge(previous, listOf(reading), now, HISTORY_MS))))
        store(settings) {
            putString("notificationPackage", packageName)
            remove("notificationError")
        }
    }

    private fun notificationProblem(settings: PhoneSettings, latest: GlucoseReading?, now: Long): String? {
        if (!settings.usesDexcomNotifications) return null
        return when {
            !DexcomNotificationService.hasAccess(appContext) -> "Allow GlucoPhone notification access in Connect."
            cached(settings, "notificationError").isNotEmpty() -> cached(settings, "notificationError")
            latest == null -> "Waiting for the next G6 notification. Enable Quick Glance in the G6 app; this can take five minutes."
            now - latest.timeMillis > DexcomNotification.MAX_AGE_MS -> "No recent G6 notification. Check the G6 app and notification access."
            else -> null
        }
    }

    fun forecast(state: PhoneState, horizonMinutes: Int): Prediction? {
        val latest = state.latest ?: return null
        if (System.currentTimeMillis() - latest.timeMillis > 15 * 60_000L) return null
        val predictor = if (state.settings.predictorId == OnnxPredictor.ID) modelStore.predictor()
            else Predictors.byId(state.settings.predictorId)
        return predictor?.let { runCatching {
            val minutes = if (it.id == OnnxPredictor.ID) minOf(horizonMinutes, it.defaultHorizonMinutes) else horizonMinutes
            if (it is GlucoseDaoPredictor) it.predict(state.readings, state.treatments, minutes, state.therapy)
                else it.predict(state.readings, minutes)
        }
            .onFailure { Log.w(TAG, "Predictor ${state.settings.predictorId} failed", it) }
            .getOrNull() }?.let(::present)
    }

    /** The name a stored forecast should keep: the GlucoseDao label when one was imported. */
    fun present(prediction: Prediction): Prediction {
        val repo = modelStore.origin?.substringBefore(" ·")?.takeIf { '/' in it }
        val label = when {
            prediction.modelId == OnnxPredictor.ID && repo != null -> HuggingFaceModel.choiceLabel(repo)
            prediction.modelId == OnnxPredictor.ID -> "Imported model"
            else -> ForecastReviewer.title(prediction.modelId)
        }
        return if (label == prediction.modelId) prediction else prediction.copy(modelId = label)
    }

    /**
     * G6 notifications only carry the latest value. When a Dexcom login is saved, a hole in that
     * series is filled from Share, which is where the official app uploads the points it recovered
     * after the transmitter was out of range. A Share failure leaves the notification points as they are.
     */
    private fun backfillNotifications(settings: PhoneSettings, now: Long) {
        if (!settings.usesDexcomNotifications || settings.username.isBlank() || settings.password.isBlank()) return
        val source = syncCache(settings)
        val old = CacheFormat.decodeReadings(source.get(SourceSync.READINGS).orEmpty())
        if (SourceSync.refetchFrom(old, now) == null) return
        runCatching {
            SourceSync(source, retainMs = HISTORY_MS).fetch(settings.account(LinkSource.SHARE, carelink)!!, CHART_HOURS, now)
        }.onFailure { Log.w(TAG, "Dexcom Share backfill failed", it) }
    }

    fun defaultForecastMinutes(state: PhoneState): Int =
        if (state.settings.predictorId == OnnxPredictor.ID) modelStore.predictor()?.defaultHorizonMinutes ?: 120 else 30

    /** For a watch's sync: fresh data (at most [FRESH_MS] old) and, if asked, a forecast from the phone's model. */
    fun snapshot(horizonMinutes: Int): LinkSnapshot {
        val state = runBlocking { refresh(maxAgeMs = FRESH_MS) }
        val settings = state.settings
        val forecast = if (horizonMinutes <= 0 || state.readings.isEmpty()) null else forecast(state, horizonMinutes)
        // The phone keeps two weeks for its own chart; the watch caches a day, so send it a day.
        // That keeps the Bluetooth message the size it always was.
        val dayAgo = System.currentTimeMillis() - SourceSync.DAY_MS
        return LinkSnapshot(
            upstream = LinkCrypto.sha256(settings.configurationKey.toByteArray()).copyOf(12).let(Base64.getEncoder()::encodeToString),
            sourceLabel = settings.sourceLabel,
            readings = state.readings.filter { it.timeMillis >= dayAgo },
            treatments = state.treatments.filter { it.timeMillis >= dayAgo },
            loop = state.loop,
            forecast = forecast,
            error = state.lastError.takeIf { settings.source != LinkSource.DEMO },
        )
    }

    private fun problem(settings: PhoneSettings, of: LinkSource): String? = when (of) {
        LinkSource.DEMO -> null
        LinkSource.SHARE -> "Enter the Dexcom username and password".takeIf { settings.username.isBlank() || settings.password.isBlank() }
        LinkSource.CARELINK -> "Sign in to CareLink in Connect".takeIf {
            settings.carelinkAccount.isEmpty() || carelink.load()?.subject != settings.carelinkAccount
        }
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
        if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.all[key]?.toString().orEmpty() else ""

    private fun store(settings: PhoneSettings, write: SharedPreferences.Editor.() -> Unit) {
        if (settingsStore.load().configurationKey != settings.configurationKey) return
        cache.edit().apply(write).putString(ACCOUNT, settings.accountKey).apply()
    }

    private fun prefix(settings: PhoneSettings, of: LinkSource): String =
        if (of == settings.source) "" else EXTRA + of.name + ":" +
            LinkCrypto.sha256(settings.keyOf(of).toByteArray()).joinToString("") { "%02x".format(it) } + ":"

    private fun syncCache(settings: PhoneSettings, of: LinkSource = settings.source) = object : SyncCache {
        private val prefix = prefix(settings, of)
        override fun get(key: String): String? =
            if (cache.getString(ACCOUNT, null) == settings.accountKey) cache.getString(prefix + key, null) else null

        override fun put(values: Map<String, String?>) = store(settings) { values.forEach { (key, value) -> putString(prefix + key, value) } }
    }

    /**
     * CareLink and Dexcom rows inside [window], from the caches [refresh] fills. Call it after a
     * refresh, and only once [window] has been accepted. Demo mode has nothing to export.
     */
    suspend fun exportTables(window: ExportWindow): PhoneExport = lock.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            val zone = ZoneId.systemDefault()
            if (settings.source == LinkSource.DEMO) return@withContext PhoneExport.empty(zone)
            val dexcomReadings = if (settings.source == LinkSource.SHARE) {
                CacheFormat.decodeReadings(syncCache(settings).get(SourceSync.READINGS).orEmpty())
            } else {
                emptyList()
            }
            val dexcomSource = if (settings.dexcomNotifications) ExportRow.SOURCE_DEXCOM_NOTIFICATIONS else ExportRow.SOURCE_DEXCOM_SHARE
            val carelinkOf = when {
                settings.source == LinkSource.CARELINK -> settings.source
                LinkSource.CARELINK in settings.extras -> LinkSource.CARELINK
                else -> null
            }
            val carelinkCache = carelinkOf?.let { syncCache(settings, it) }
            val carelinkReadings = carelinkCache?.get(SourceSync.READINGS)?.let(CacheFormat::decodeReadings).orEmpty()
            val carelinkTreatments = carelinkCache?.get(SourceSync.TREATMENTS)?.let(CacheFormat::decodeTreatments).orEmpty()
            val carelinkLoop = carelinkCache?.get(SourceSync.LOOP)?.let(CacheFormat::decodeLoop)
            val logged = ExportLog.decode(carelinkCache?.get(SourceSync.CARELINK_EXPORT).orEmpty()) +
                ExportLog.decode(carelinkCache?.get(SourceSync.CARELINK_DEVICE).orEmpty())
            val glucose = dexcomReadings.ifEmpty { carelinkReadings }
            val glucoseSource = when {
                dexcomReadings.isNotEmpty() && settings.dexcomNotifications -> "Dexcom G6 notifications"
                dexcomReadings.isNotEmpty() -> "Dexcom Share"
                carelinkReadings.isNotEmpty() -> "CareLink"
                else -> "no cached glucose"
            }
            PhoneExport(
                carelinkRows = ExportLog.prefer(logged, ExportRow.fromCareLinkCache(carelinkReadings, carelinkTreatments, carelinkLoop)),
                dexcomRows = dexcomReadings.map { ExportRow.fromReading(it, dexcomSource) },
                glucose = glucose,
                glucoseSource = glucoseSource,
                pump = carelinkTreatments,
                zone = zone,
                hasCareLink = carelinkOf != null,
                hasDexcom = settings.source == LinkSource.SHARE,
            ).within(window)
        }
    }

    /** A move, under the same lock as refresh: only one device may refresh a CareLink session. */
    fun handOverCareLink(): String? = runBlocking { lock.withLock {
        val token = carelink.take() ?: return@withLock null
        val old = settingsStore.load()
        settingsStore.save(old.copy(carelinkAccount = ""))
        token.encode()
    } }

    companion object {
        private const val TAG = "GlucoPhoneRepo"
        private const val ACCOUNT = "account"
        private const val PLAN = "plan"
        private const val EXTRA = "also:"

        /** The watch shows up to this many hours; the phone keeps the same treatment window. */
        private const val CHART_HOURS = 6

        /**
         * How far back the phone's chart can be dragged. Dexcom Share serves a day at a time, so a
         * longer history accumulates over repeated fetches; Nightscout backfills on the first run.
         */
        const val HISTORY_HOURS = 14 * 24
        private const val HISTORY_MS = HISTORY_HOURS * 3_600_000L

        /** A watch that polls every minute while a reading is late still costs at most two fetches a minute. */
        private const val FRESH_MS = 30_000L
        private val lock = Mutex()
    }
}

/** What an export writes. Glucose for the PDF is Dexcom when that is the chart source, otherwise CareLink. */
data class PhoneExport(
    val carelinkRows: List<ExportRow>,
    val dexcomRows: List<ExportRow>,
    val glucose: List<GlucoseReading>,
    val glucoseSource: String,
    val pump: List<Treatment>,
    val zone: ZoneId,
    val hasCareLink: Boolean,
    val hasDexcom: Boolean,
) {
    fun carelinkCsv(): String = ExportCsv.write(carelinkRows, zone)

    fun combinedCsv(): String = ExportCsv.write(carelinkRows + dexcomRows, zone)

    val rows get() = (carelinkRows + dexcomRows).sortedBy { it.timeMillis }

    fun report(window: ExportWindow): GlucoseReport = GlucoseReport.build(glucose, pump, glucoseSource, zone,
        window = window, events = rows)

    fun select(content: glucowatch.core.ExportContent): PhoneExport {
        return copy(
            carelinkRows = carelinkRows.filter(content::includes),
            dexcomRows = dexcomRows.filter(content::includes),
            glucose = if (content.sensor) glucose else emptyList(),
            pump = if (content.pump) pump else emptyList(),
        )
    }

    fun withHistory(dexcom: List<ExportRow>?, carelink: List<ExportRow>?): PhoneExport {
        val care = carelink ?: carelinkRows
        val dex = dexcom ?: dexcomRows
        val dexReadings = dex.mapNotNull(ExportRow::toReading)
        val readings = dexReadings.ifEmpty { care.mapNotNull(ExportRow::toReading) }
        val treatments = if (carelink == null) pump else care.mapNotNull { row ->
            when (row.kind) {
                ExportRow.KIND_BOLUS -> Treatment(row.timeMillis, insulin = row.insulinU ?: 0.0, automatic = row.automatic == true)
                ExportRow.KIND_BASAL_DELIVERY -> Treatment(row.timeMillis, insulin = row.insulinU ?: 0.0, insulinKind = glucowatch.core.InsulinKind.BASAL)
                ExportRow.KIND_MEAL -> Treatment(row.timeMillis, carbs = row.carbsG ?: 0.0)
                ExportRow.KIND_REPORTED_BASAL, ExportRow.KIND_TEMP_BASAL -> Treatment(row.timeMillis,
                    insulinKind = glucowatch.core.InsulinKind.BASAL, basalRate = row.basalRateUPerH,
                    basalPercent = row.basalPercent, durationMinutes = row.durationMin, percentOfProfile = row.percentOfProfile == true)
                else -> null
            }
        }
        return copy(carelinkRows = care, dexcomRows = dex, glucose = readings, pump = treatments,
            glucoseSource = if (dexReadings.isNotEmpty()) { if (dexcom != null) "Dexcom Clarity" else glucoseSource }
                else if (carelink != null) "CareLink CSV" else if (care.isNotEmpty()) "CareLink" else "no sensor readings",
            hasCareLink = care.isNotEmpty(), hasDexcom = dex.isNotEmpty())
    }

    private fun daysIn(rows: List<ExportRow>) = rows.map { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }.toSet()

    fun hasGaps(window: ExportWindow): Boolean {
        val n = java.time.temporal.ChronoUnit.DAYS.between(window.start, window.end).toInt() + 1
        val sensor = daysIn(rows.filter { it.kind == ExportRow.KIND_GLUCOSE && it.glucoseMgdl != null })
        val pumpDays = daysIn(carelinkRows.filter { it.kind !in setOf(ExportRow.KIND_GLUCOSE, ExportRow.KIND_CALIBRATION, ExportRow.KIND_METER) })
        return (window.content.sensor && sensor.size < n) || (window.content.pump && pumpDays.size < n)
    }

    fun coverage(window: ExportWindow): String {
        val n = java.time.temporal.ChronoUnit.DAYS.between(window.start, window.end).toInt() + 1
        val sensor = daysIn(rows.filter { it.kind == ExportRow.KIND_GLUCOSE && it.glucoseMgdl != null }).size
        val pumpDays = daysIn(carelinkRows.filter { it.kind !in setOf(ExportRow.KIND_GLUCOSE, ExportRow.KIND_CALIBRATION, ExportRow.KIND_METER) }).size
        return "Requested ${window.start} to ${window.end} ($n days). " +
            listOfNotNull(if (window.content.sensor) "Sensor data on $sensor/$n days" else null,
                if (window.content.pump) "Pump events on $pumpDays/$n days" else null).joinToString(". ") +
            ". A day with events may still have gaps; rates are not counted as delivered insulin."
    }

    /** Drops every event outside the accepted window. Both series use the same dates. */
    fun within(window: ExportWindow) = copy(
        carelinkRows = carelinkRows.filter { window.contains(it.timeMillis, zone) },
        dexcomRows = dexcomRows.filter { window.contains(it.timeMillis, zone) },
        glucose = glucose.filter { window.contains(it.timeMillis, zone) },
        pump = pump.filter { window.contains(it.timeMillis, zone) },
    )

    fun withinInterval(start: Long, endInclusive: Long) = copy(
        carelinkRows = carelinkRows.filter { it.timeMillis in start..endInclusive },
        dexcomRows = dexcomRows.filter { it.timeMillis in start..endInclusive },
        glucose = glucose.filter { it.timeMillis in start..endInclusive },
        pump = pump.filter { it.timeMillis in start..endInclusive },
    )

    companion object {
        fun empty(zone: ZoneId) = PhoneExport(
            emptyList(), emptyList(), emptyList(), "no cached glucose", emptyList(), zone, false, false,
        )
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
