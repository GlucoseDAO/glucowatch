package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A CareLink sign-in: the OAuth tokens of Medtronic's CarePartner app (Auth0), which GlucoWatch
 * refreshes itself. The first sign-in needs a browser, because the page has a reCAPTCHA; see
 * docs/carelink.md. [country] picks CareLink's EU or US servers. The refresh token rotates, so a
 * sign-in belongs to one device: two devices refreshing the same one log each other out.
 */
data class CareLinkToken(
    val country: String,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
) {
    /** The account's stable id (`sub` of the access token), or null if the token is not a JWT. */
    val subject: String? get() = claims()?.string("sub")

    fun expiresSoon(now: Long) = now >= expiresAt - 60_000L

    /** JSON with the same fields scripts/carelink_login.py writes. */
    fun encode(): String = buildJsonObject {
        put("country", country)
        put("client_id", clientId)
        put("access_token", accessToken)
        put("refresh_token", refreshToken)
        put("expires_at", expiresAt)
    }.toString()

    private fun claims(): JsonObject? = runCatching {
        val payload = accessToken.split('.')[1]
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))) as JsonObject
    }.getOrNull()

    override fun toString() = "CareLinkToken(country=$country, subject=$subject, expiresAt=$expiresAt)"

    companion object {
        fun decode(text: String?): CareLinkToken? {
            val obj = runCatching { Json.parseToJsonElement(text ?: return null) as JsonObject }.getOrNull() ?: return null
            return CareLinkToken(
                country = obj.string("country")?.uppercase() ?: return null,
                clientId = obj.string("client_id") ?: return null,
                accessToken = obj.string("access_token") ?: return null,
                refreshToken = obj.string("refresh_token") ?: return null,
                expiresAt = obj.string("expires_at")?.toLongOrNull() ?: 0L,
            )
        }
    }
}

/**
 * Where the app keeps its [CareLinkToken]. [replace] stores [new] only while the stored token is
 * still [old], so a refresh that finishes after the user signed in again cannot bring back the
 * old sign-in.
 */
interface CareLinkLogin {
    fun load(): CareLinkToken?

    fun replace(old: CareLinkToken, new: CareLinkToken)
}

sealed class CareLinkException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The sign-in is gone (refresh token revoked, expired or used on another device): sign in again. */
    class SignInNeeded(message: String) : CareLinkException(message)
    class Server(val status: Int, message: String) : CareLinkException(message)
    class Network(cause: Throwable) : CareLinkException("Network error: ${cause.message}", cause)
}

/** One `display/message` answer: the last day the pump uploaded to CareLink. */
data class CareLinkData(
    val readings: List<GlucoseReading>,
    val treatments: List<Treatment>,
    /** Active insulin as the pump computed it; CareLink has no carbs on board and no forecast. */
    val loop: LoopStatus?,
    /** When the phone app (the conduit) last uploaded, corrected like the readings. */
    val lastUploadMillis: Long?,
)

/**
 * Read-only client for the CareLink CarePartner API, the one Medtronic's CarePartner app and
 * xDrip+ use. Two JSON files describe the servers ([DISCOVERY_URL] and the Auth0 configuration it
 * points at); [config] keeps what was learnt from them and [session] the account's role and
 * patient, so later runs skip those requests.
 */
class CareLinkClient(
    private val login: CareLinkLogin,
    var config: String? = null,
    var session: String? = null,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    /** The last day of readings, boluses, delivered basal pulses, carbs and active insulin. */
    fun recent(now: Long = System.currentTimeMillis()): CareLinkData {
        val servers = servers()
        val who = who(servers)
        val body = buildJsonObject {
            put("username", who.username)
            put("role", who.role)
            who.patientId?.let { put("patientId", it) }
            put("appVersion", APP_VERSION)
        }.toString()
        val json = call(HttpRequest("POST", servers.cumulus + "/display/message", JSON_HEADERS, body))
        val patient = (json as? JsonObject)?.get("patientData") as? JsonObject
            ?: throw CareLinkException.Server(200, "CareLink sent no pump data. Is the pump uploading through the MiniMed app?")
        return parse(patient, now)
    }

    private data class Servers(val careLink: String, val cumulus: String, val tokenUrl: String)

    private data class Who(val username: String, val role: String, val patientId: String?)

    private fun servers(): Servers {
        val token = login.load() ?: throw CareLinkException.SignInNeeded("Sign in to CareLink")
        val cached = config?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
        if (cached != null && cached.string("country") == token.country) {
            val careLink = cached.string("careLink")
            val cumulus = cached.string("cumulus")
            val tokenUrl = cached.string("tokenUrl")
            if (careLink != null && cumulus != null && tokenUrl != null) return Servers(careLink, cumulus, tokenUrl)
        }
        val disco = getJson(DISCOVERY_URL) as? JsonObject ?: throw CareLinkException.Server(200, "CareLink discovery changed")
        val region = (disco["supportedCountries"] as? JsonArray).orEmpty().firstNotNullOfOrNull {
            ((it as? JsonObject)?.get(token.country) as? JsonObject)?.string("region")
        } ?: throw CareLinkException.Server(200, "CareLink does not serve country ${token.country}")
        val cp = (disco["CP"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull { it.string("region") == region }
            ?: throw CareLinkException.Server(200, "CareLink discovery has no $region servers")
        val ssoUrl = cp.string(cp.string("UseSSOConfiguration") ?: "SSOConfiguration") ?: cp.string("SSOConfiguration")
            ?: throw CareLinkException.Server(200, "CareLink discovery has no sign-in server")
        val sso = getJson(ssoUrl) as? JsonObject ?: throw CareLinkException.Server(200, "CareLink sign-in configuration changed")
        val server = sso["server"] as? JsonObject
        val host = server?.string("hostname") ?: throw CareLinkException.Server(200, "CareLink sign-in configuration changed")
        val port = server.string("port")?.toIntOrNull()?.takeIf { it != 443 }?.let { ":$it" }.orEmpty()
        val prefix = server.string("prefix").orEmpty().trim('/').let { if (it.isEmpty()) "" else "/$it" }
        val tokenPath = (sso["system_endpoints"] as? JsonObject)?.string("token_endpoint_path") ?: "/oauth/token"
        val result = Servers(
            careLink = cp.string("baseUrlCareLink") ?: throw CareLinkException.Server(200, "CareLink discovery changed"),
            cumulus = cp.string("baseUrlCumulus") ?: throw CareLinkException.Server(200, "CareLink discovery changed"),
            tokenUrl = "https://$host$port$prefix$tokenPath",
        )
        config = buildJsonObject {
            put("country", token.country)
            put("careLink", result.careLink)
            put("cumulus", result.cumulus)
            put("tokenUrl", result.tokenUrl)
        }.toString()
        return result
    }

    /** The account's role and, for a care partner, the patient it follows (the first one). */
    private fun who(servers: Servers): Who {
        val subject = login.load()?.subject
        session?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }?.let { s ->
            val username = s.string("username")
            val role = s.string("role")
            if (username != null && role != null && s.string("subject") == subject) return Who(username, role, s.string("patientId"))
        }
        val user = call(HttpRequest.get(servers.careLink + "/users/me", JSON_HEADERS)) as? JsonObject
            ?: throw CareLinkException.Server(200, "CareLink sent no account")
        val carePartner = user.string("role").orEmpty().uppercase().startsWith("CARE_PARTNER")
        val username = user.string("username") ?: user.string("loginName") ?: user.string("preferred_username")
            ?: throw CareLinkException.Server(200, "CareLink sent no username")
        val patientId = if (carePartner) {
            val patients = call(HttpRequest.get(servers.careLink + "/links/patients", JSON_HEADERS)) as? JsonArray
            (patients?.firstOrNull() as? JsonObject)?.string("username")
                ?: throw CareLinkException.Server(200, "This care partner account follows no patient yet")
        } else {
            null
        }
        val who = Who(username, if (carePartner) "carepartner" else "patient", patientId)
        session = buildJsonObject {
            subject?.let { put("subject", it) }
            put("username", who.username)
            put("role", who.role)
            who.patientId?.let { put("patientId", it) }
        }.toString()
        return who
    }

    /** An API call with the access token, refreshed first when it is about to expire and once more on 401. */
    private fun call(request: HttpRequest): JsonElement {
        var token = fresh(login.load() ?: throw CareLinkException.SignInNeeded("Sign in to CareLink"), force = false)
        var response = execute(request.withBearer(token.accessToken))
        if (response.status == 401) {
            token = fresh(token, force = true)
            response = execute(request.withBearer(token.accessToken))
        }
        val json = runCatching { Json.parseToJsonElement(response.body.ifBlank { "null" }) }.getOrNull()
        if (response.status in 200..299 && json != null) return json
        throw when (response.status) {
            204 -> CareLinkException.Server(204, "CareLink has no data for this account. Use a care partner account that follows the patient")
            401, 403 -> CareLinkException.SignInNeeded("CareLink refused the sign-in (HTTP ${response.status}). Sign in again")
            else -> CareLinkException.Server(response.status, "CareLink: HTTP ${response.status}")
        }
    }

    private fun fresh(token: CareLinkToken, force: Boolean): CareLinkToken {
        if (!force && !token.expiresSoon(System.currentTimeMillis())) return token
        val servers = servers()
        val form = listOf("grant_type" to "refresh_token", "client_id" to token.clientId, "refresh_token" to token.refreshToken)
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val response = execute(HttpRequest("POST", servers.tokenUrl, mapOf("Content-Type" to "application/x-www-form-urlencoded"), form))
        val json = runCatching { Json.parseToJsonElement(response.body) as JsonObject }.getOrNull()
        // Auth0 answers 403 invalid_grant for a revoked, expired or reused refresh token.
        if (response.status in 400..403) {
            throw CareLinkException.SignInNeeded("CareLink sign-in expired${json?.string("error_description")?.let { ": $it" }.orEmpty()}. Sign in again")
        }
        val access = json?.string("access_token")
        if (response.status !in 200..299 || access == null) throw CareLinkException.Server(response.status, "CareLink token refresh failed (HTTP ${response.status})")
        val renewed = token.copy(
            accessToken = access,
            refreshToken = json.string("refresh_token") ?: token.refreshToken,
            expiresAt = System.currentTimeMillis() + (json.string("expires_in")?.toLongOrNull() ?: 3600L) * 1000L,
        )
        login.replace(token, renewed)
        return renewed
    }

    private fun getJson(url: String): JsonElement? {
        val response = execute(HttpRequest.get(url))
        if (response.status !in 200..299) throw CareLinkException.Server(response.status, "CareLink: HTTP ${response.status} for its configuration")
        return runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
    }

    private fun execute(request: HttpRequest): HttpResponse = try {
        transport.execute(request)
    } catch (e: IOException) {
        throw CareLinkException.Network(e)
    }

    private fun HttpRequest.withBearer(token: String) = copy(headers = headers + ("Authorization" to "Bearer $token"))

    companion object {
        const val DISCOVERY_URL = "https://clcloud.minimed.eu/connect/carepartner/v13/discover/android/3.8"
        private const val APP_VERSION = "3.8.0"
        private val JSON_HEADERS = mapOf("Content-Type" to "application/json", "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 14)")

        /**
         * Readings, boluses, basal pulses, carbs and active insulin from `patientData`. CareLink reports times
         * in the pump's clock, often labelled UTC whatever the pump's time zone; when the pump's
         * last upload time and the server's differ by whole hours, every time is moved by that much.
         */
        internal fun parse(patient: JsonObject, now: Long): CareLinkData {
            val deviceUpload = patient.time("lastConduitDateTime")
            val serverUpload = patient.time("lastConduitUpdateServerDateTime")
            val pumpClock = patient.time("medicalDeviceTime")
            val pumpUpload = patient.time("lastMedicalDeviceDataUpdateServerTime")
            // The pump and the uploading phone may use different time zones. Marker/SG/IOB
            // timestamps belong to the pump, so use its own paired clock/server anchors first.
            val localAnchor = if (pumpClock != null && pumpUpload != null) pumpClock else deviceUpload
            val serverAnchor = if (pumpClock != null && pumpUpload != null) pumpUpload else serverUpload
            val shiftHours = if (localAnchor != null && serverAnchor != null && localAnchor > 0 && serverAnchor > 0) {
                ((serverAnchor - localAnchor) / 3_600_000.0).roundToInt().takeIf { abs(it) in 1..25 } ?: 0
            } else {
                0
            }
            val shift = shiftHours * 3_600_000L
            fun JsonObject.at(vararg keys: String) = time(*keys)?.plus(shift)

            val readings = (patient["sgs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { sg ->
                // 0 marks a gap (warm-up, lost signal); under 40 are not glucose values.
                val value = sg.double("sg")?.roundToInt()?.takeIf { it >= 40 } ?: return@mapNotNull null
                val time = sg.at("timestamp", "datetime") ?: return@mapNotNull null
                GlucoseReading(time, value, Trend.None)
            }.distinctBy { it.timeMillis }.sortedBy { it.timeMillis }.filter { it.timeMillis <= now + 5 * 60_000L }
            val withTrend = readings.lastOrNull()?.let { last ->
                val trend = trend(patient.string("lastSGTrend"), readings)
                readings.dropLast(1) + last.copy(trend = trend)
            } ?: readings

            val treatments = (patient["markers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { marker ->
                val values = ((marker["data"] as? JsonObject)?.get("dataValues") as? JsonObject) ?: marker
                val time = marker.at("timestamp", "displayTime", "dateTime") ?: return@mapNotNull null
                when (marker.string("type")?.uppercase()) {
                    "INSULIN" -> {
                        val units = listOfNotNull(values.double("deliveredFastAmount"), values.double("deliveredExtendedAmount")).sum()
                            .takeIf { it > 0 } ?: values.double("insulinUnits") ?: return@mapNotNull null
                        // The 780G's automatic correction boluses; everything else the user started.
                        val automatic = values.string("activationType").equals("AUTOCORRECTION", ignoreCase = true)
                        Treatment(time, insulin = units, automatic = automatic).takeIf { units > 0 }
                    }
                    "MEAL" -> values.double("amount")?.takeIf { it > 0 }?.let { Treatment(time, carbs = it) }
                    "AUTO_BASAL_DELIVERY" -> values.double("bolusAmount")?.takeIf { it.isFinite() && it >= 0 }?.let {
                        Treatment(time, insulin = it, automatic = true, insulinKind = InsulinKind.BASAL)
                    }
                    else -> null
                }
            }.toMutableList()

            // A reported rate setting is not a delivered basal pulse or a reconstructed schedule.
            // Keep its actual upload time; never fabricate a day of basal delivery from this value.
            val basal = patient["basal"] as? JsonObject
            val basalTime = pumpUpload ?: serverUpload
            basal?.double("basalRate")?.takeIf { it.isFinite() && it >= 0 }?.let { rate ->
                if (basalTime != null) treatments += Treatment(basalTime, insulinKind = InsulinKind.BASAL, basalRate = rate)
            }

            val active = patient["activeInsulin"] as? JsonObject
            val iob = active?.double("amount")?.takeIf { it >= 0 }
            val iobTime = active?.at("datetime") ?: pumpUpload ?: serverUpload
            val loop = if (iob != null && iobTime != null) LoopStatus(timeMillis = iobTime, iob = iob) else null
            return CareLinkData(withTrend, treatments.sortedBy { it.timeMillis }, loop, pumpUpload ?: serverUpload ?: deviceUpload?.plus(shift))
        }

        /** Medtronic arrows: one per 1 mg/dL/min of change, none when steadier; mapped onto Dexcom's. */
        private fun trend(value: String?, readings: List<GlucoseReading>): Trend = when (value?.uppercase()) {
            "UP" -> Trend.FortyFiveUp
            "UP_DOUBLE" -> Trend.SingleUp
            "UP_TRIPLE" -> Trend.DoubleUp
            "DOWN" -> Trend.FortyFiveDown
            "DOWN_DOUBLE" -> Trend.SingleDown
            "DOWN_TRIPLE" -> Trend.DoubleDown
            else -> {
                val last = readings.last()
                val prev = readings.dropLast(1).lastOrNull { last.timeMillis - it.timeMillis in 4 * 60_000L..16 * 60_000L }
                if (prev == null) Trend.None
                else Trend.fromRate((last.mgdl - prev.mgdl) / ((last.timeMillis - prev.timeMillis) / 60_000.0))
            }
        }
    }
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.double(key: String) = string(key)?.toDoubleOrNull()
private fun JsonObject.time(vararg keys: String) = keys.firstNotNullOfOrNull { key -> string(key)?.let(NightscoutClient::parseTime) }
