package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import kotlin.math.roundToInt

/**
 * The two REST APIs of a Nightscout server (cgm-remote-monitor).
 * v1 is the classic one every uploader and follower app uses; it can be read without a token
 * when the site is public. v3 (Nightscout 14+) always needs an access token.
 */
enum class NightscoutApi(val label: String) {
    V1("API v1 (classic)"),
    V3("API v3");

    companion object {
        /** Accepts `v1`, `1`, `v3`, `3` in any case. */
        fun parse(value: String): NightscoutApi = when (value.trim().lowercase().removePrefix("api").removePrefix("v")) {
            "1" -> V1
            "3" -> V3
            else -> throw IllegalArgumentException("Unknown Nightscout API '$value': use v1 or v3")
        }
    }
}

sealed class NightscoutException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(message: String) : NightscoutException(message)
    class Server(val status: Int, message: String) : NightscoutException(message)
    class Network(cause: Throwable) : NightscoutException("Network error: ${cause.message}", cause)
}

/**
 * A Nightscout address as the user typed it. The scheme is optional (https is assumed), a pasted
 * API path such as `/api/v1/` is dropped, and a token or secret in the address is picked up:
 * `https://site/?token=reader-0123456789abcdef` (share links) or `https://SECRET@site/api/v1/` (xDrip+).
 */
data class NightscoutAddress(val baseUrl: String, val secret: String?) {
    companion object {
        fun parse(input: String): NightscoutAddress {
            val text = input.trim().let { if ("://" in it) it else "https://$it" }
            val uri = try {
                URI(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a web address: '$input'")
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "https" || scheme == "http") { "Nightscout address must start with https://" }
            val host = uri.host ?: throw IllegalArgumentException("Not a web address: '$input'")
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().replace(Regex("/api(/.*)?$"), "").trimEnd('/')
            val token = uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
                part.takeIf { it.startsWith("token=") }?.substringAfter('=')
            }
            val secret = (uri.rawUserInfo ?: token)?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
            return NightscoutAddress("$scheme://$host$port$path/", secret)
        }
    }
}

/**
 * Read-only Nightscout client: CGM entries, treatments (insulin, carbs) and the loop's device status.
 *
 * [secret] is either an access token (Admin tools → Subjects, role `readable`) or, for v1 only,
 * the API secret. v1 sends a token as `?token=` and a secret as its SHA-1 in the `api-secret`
 * header. v3 trades the token for a JWT at `/api/v2/authorization/request/` and sends it as a
 * Bearer header; [jwt] can be persisted between runs and is renewed when the server rejects it.
 */
class NightscoutClient(
    address: String,
    secret: String = "",
    private val api: NightscoutApi = NightscoutApi.V1,
    var jwt: String? = null,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    val baseUrl: String
    private val secret: String

    init {
        val parsed = NightscoutAddress.parse(address)
        baseUrl = parsed.baseUrl
        this.secret = secret.trim().ifEmpty { parsed.secret.orEmpty() }
    }

    /** Glucose entries (`sgv`) at or after [sinceMillis], oldest first. */
    fun readings(sinceMillis: Long, maxCount: Int = MAX_COUNT): List<GlucoseReading> {
        val docs = when (api) {
            NightscoutApi.V1 -> getV1("api/v1/entries/sgv.json", "count" to "$maxCount", "find[date][\$gte]" to "$sinceMillis")
            NightscoutApi.V3 -> getV3(
                "api/v3/entries", "type\$eq" to "sgv", "date\$gte" to "$sinceMillis", "sort\$desc" to "date",
                "limit" to "${maxCount.coerceAtMost(V3_MAX_LIMIT)}", "fields" to "date,mills,dateString,sgv,direction,trend,type",
            )
        }
        return docs.mapNotNull(::parseEntry).distinctBy { it.timeMillis }.sortedBy { it.timeMillis }
    }

    /** Boluses, temp basals and carbs entered at or after [sinceMillis], oldest first. */
    fun treatments(sinceMillis: Long, maxCount: Int = MAX_COUNT): List<Treatment> {
        val since = isoMillis(sinceMillis)
        val docs = when (api) {
            NightscoutApi.V1 -> getV1("api/v1/treatments.json", "count" to "$maxCount", "find[created_at][\$gte]" to since)
            NightscoutApi.V3 -> getV3(
                "api/v3/treatments", "created_at\$gte" to since, "sort\$desc" to "created_at",
                "limit" to "${maxCount.coerceAtMost(V3_MAX_LIMIT)}",
                "fields" to "date,mills,created_at,eventType,insulin,carbs,isSMB,type,automatic,isValid,bolus,absolute,rate,percent,duration",
            )
        }
        return docs.mapNotNull(::parseTreatment).sortedBy { it.timeMillis }
    }

    /** IOB, COB and forecast from the loop's uploads at or after [sinceMillis], or null if there are none. */
    fun loopStatus(sinceMillis: Long, maxCount: Int = 10): LoopStatus? {
        val since = isoMillis(sinceMillis)
        val docs = when (api) {
            NightscoutApi.V1 -> getV1("api/v1/devicestatus.json", "count" to "$maxCount", "find[created_at][\$gte]" to since)
            NightscoutApi.V3 -> getV3(
                "api/v3/devicestatus", "created_at\$gte" to since, "sort\$desc" to "created_at",
                "limit" to "$maxCount", "fields" to "date,mills,created_at,device,openaps,loop",
            )
        }
        return parseLoopStatus(docs)
    }

    private fun getV1(path: String, vararg params: Pair<String, String>): List<JsonObject> {
        val headers = mutableMapOf<String, String>()
        val query = params.toMutableList()
        when {
            secret.isEmpty() -> Unit
            ACCESS_TOKEN.matches(secret) -> query += "token" to secret
            else -> headers["api-secret"] = sha1(secret)
        }
        val json = get(url(path, query), headers)
        return (json as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: throw NightscoutException.Server(200, "Not a Nightscout API response")
    }

    private fun getV3(path: String, vararg params: Pair<String, String>): List<JsonObject> {
        val url = url(path, params.toList())
        val cached = jwt
        val json = try {
            get(url, bearer(cached ?: requestJwt()))
        } catch (e: NightscoutException.Unauthorized) {
            if (cached == null) throw e
            get(url, bearer(requestJwt()))
        }
        // Nightscout 15 wraps results as {"status":200,"result":[...]}; 14.x returned the bare array.
        val result = (json as? JsonObject)?.get("result") ?: json
        return (result as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: throw NightscoutException.Server(200, "Not a Nightscout API v3 response")
    }

    private fun requestJwt(): String {
        jwt = null
        if (secret.isEmpty()) throw NightscoutException.Unauthorized("API v3 needs an access token")
        val json = try {
            get(url("api/v2/authorization/request/" + URLEncoder.encode(secret, "UTF-8"), emptyList()), emptyMap())
        } catch (e: NightscoutException.Unauthorized) {
            throw NightscoutException.Unauthorized("Nightscout rejected the token. API v3 needs an access token, not the API secret")
        }
        val token = (json as? JsonObject)?.string("token")
            ?: throw NightscoutException.Server(200, "Nightscout returned no JWT")
        jwt = token
        return token
    }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun get(url: String, headers: Map<String, String>): JsonElement {
        val response = try {
            transport.execute(HttpRequest.get(url, headers))
        } catch (e: IOException) {
            throw NightscoutException.Network(e)
        }
        val json = try {
            Json.parseToJsonElement(response.body.ifBlank { "null" })
        } catch (e: Exception) {
            null
        }
        if (response.status in 200..299) {
            return json ?: throw NightscoutException.Server(response.status, "Not a Nightscout API response (HTTP ${response.status})")
        }
        val message = (json as? JsonObject)?.string("message")
        throw when (response.status) {
            401 -> NightscoutException.Unauthorized(
                if (secret.isEmpty()) "This Nightscout is private: enter an access token"
                else "Nightscout refused the token or API secret",
            )
            403 -> NightscoutException.Unauthorized("The token may not read this data${message?.let { " ($it)" }.orEmpty()}")
            404 -> NightscoutException.Server(
                404,
                if (api == NightscoutApi.V3) "No API v3 at this address: pick API v1 or check the address"
                else "No Nightscout API at this address",
            )
            else -> NightscoutException.Server(response.status, "Nightscout: HTTP ${response.status}${message?.let { ": $it" }.orEmpty()}")
        }
    }

    private fun url(path: String, params: List<Pair<String, String>>): String =
        baseUrl + path + params.joinToString("&", prefix = if (params.isEmpty()) "" else "?") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

    companion object {
        /** First fetch: a day of 1-minute readings (Libre via xDrip+ or Juggluco) fits. */
        const val MAX_COUNT = 1500
        private const val V3_MAX_LIMIT = 1000

        /** Nightscout access tokens are `<up to 10 letters of the subject>-<16 hex digits>`. */
        private val ACCESS_TOKEN = Regex("^[a-z0-9_]{0,10}-[0-9a-f]{16}$")

        private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        private val ISO_NO_COLON = DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendOffset("+HHMM", "Z").toFormatter()

        private fun isoMillis(millis: Long) = ISO_MILLIS.format(Instant.ofEpochMilli(millis))

        internal fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Epoch milliseconds, or an ISO 8601 time with `Z`, `+01:00`, `+0100` or no offset (read as UTC). */
        internal fun parseTime(text: String): Long? {
            text.toDoubleOrNull()?.let { return it.toLong() }
            for (format in listOf(DateTimeFormatter.ISO_OFFSET_DATE_TIME, ISO_NO_COLON)) {
                runCatching { return OffsetDateTime.parse(text, format).toInstant().toEpochMilli() }
            }
            return runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }

        internal fun parseEntry(obj: JsonObject): GlucoseReading? {
            if ((obj.string("type") ?: "sgv") != "sgv") return null
            // Values under 39 are sensor error codes, not glucose.
            val sgv = obj.double("sgv")?.roundToInt()?.takeIf { it >= 39 } ?: return null
            val time = obj.time("date", "mills", "dateString", "sysTime") ?: return null
            val trend = obj.string("direction")?.let(Trend::parse)?.takeIf { it != Trend.None }
                ?: obj.string("trend")?.let(Trend::parse)
                ?: Trend.None
            return GlucoseReading(time, sgv, trend)
        }

        internal fun parseTreatment(obj: JsonObject): Treatment? {
            if (obj.bool("isValid") == false || obj.string("type").equals("PRIMING", ignoreCase = true)) return null
            val time = obj.time("date", "mills", "created_at", "timestamp") ?: return null
            if (obj.string("eventType").equals("Temp Basal", ignoreCase = true)) {
                val duration = obj.double("duration")?.takeIf { it.isFinite() && it >= 0 } ?: return null
                val percent = obj.double("percent")?.takeIf { it.isFinite() && it >= -100 }
                val rate = (obj.double("absolute") ?: obj.double("rate").takeIf { percent == null })
                    ?.takeIf { it.isFinite() && it >= 0 }
                if (duration > 0 && rate == null && percent == null) return null
                return Treatment(time, insulinKind = InsulinKind.BASAL, basalRate = rate,
                    basalPercent = percent.takeIf { rate == null }, durationMinutes = duration)
            }
            val insulin = obj.double("insulin")?.takeIf { it > 0 } ?: 0.0
            val carbs = obj.double("carbs")?.takeIf { it > 0 } ?: 0.0
            if (insulin == 0.0 && carbs == 0.0) return null
            // AAPS: isSMB or type SMB; iAPS/Trio: eventType SMB; Loop: automatic.
            val automatic = obj.string("eventType").equals("SMB", ignoreCase = true) ||
                obj.string("type").equals("SMB", ignoreCase = true) ||
                obj.bool("isSMB") == true || obj.bool("automatic") == true ||
                (obj["bolus"] as? JsonObject)?.bool("isSMB") == true
            return Treatment(time, insulin, carbs, automatic)
        }

        /** Combines device status documents (any order) into the loop's latest state. */
        internal fun parseLoopStatus(docs: List<JsonObject>): LoopStatus? =
            docs.mapNotNull(::parseDeviceStatus)
                .sortedBy { it.timeMillis }
                .fold(null as LoopStatus?) { older, newer -> newer.mergedOnto(older) }

        internal fun parseDeviceStatus(doc: JsonObject): LoopStatus? {
            val created = doc.time("date", "mills", "created_at")
            (doc["openaps"] as? JsonObject)?.let { return parseOpenAps(it, created) }
            (doc["loop"] as? JsonObject)?.let { return parseLoop(it, created) }
            return null
        }

        /** oref0/oref1 as uploaded by AAPS, Trio, iAPS and OpenAPS rigs. */
        private fun parseOpenAps(openaps: JsonObject, created: Long?): LoopStatus? {
            val iobObj = when (val iob = openaps["iob"]) {
                is JsonArray -> iob.firstOrNull() as? JsonObject
                is JsonObject -> iob
                else -> null
            }
            val determinations = listOfNotNull(openaps["suggested"] as? JsonObject, openaps["enacted"] as? JsonObject)
            fun JsonObject.at() = time("timestamp", "deliverAt") ?: created ?: 0L
            val latest = determinations.maxByOrNull { it.at() }
            val withForecast = determinations.filter { it["predBGs"] is JsonObject }.maxByOrNull { it.at() }

            val cob = latest?.double("COB")
            val curves = (withForecast?.get("predBGs") as? JsonObject).orEmpty().mapNotNull { (name, values) ->
                (values as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
                    ?.takeIf { it.isNotEmpty() }?.let { name to it }
            }.toMap()
            val order = if ((cob ?: 0.0) > 0) listOf("COB", "UAM", "IOB", "ZT", "aCOB", "values")
            else listOf("UAM", "IOB", "COB", "ZT", "aCOB", "values")
            val name = order.firstOrNull { it in curves } ?: curves.keys.firstOrNull()

            val time = listOfNotNull(iobObj?.time("time", "timestamp"), latest?.at()).maxOrNull() ?: created ?: return null
            val status = LoopStatus(
                timeMillis = time,
                iob = iobObj?.double("iob") ?: latest?.double("IOB"),
                cob = cob,
                eventualMgdl = latest?.double("eventualBG"),
                forecast = name?.let { points(withForecast!!.at(), curves.getValue(it)) }.orEmpty(),
                forecastName = name,
            )
            return status.takeIf { it.iob != null || it.cob != null || it.forecast.isNotEmpty() }
        }

        /** Loop (iOS). */
        private fun parseLoop(loop: JsonObject, created: Long?): LoopStatus? {
            val iobObj = loop["iob"] as? JsonObject
            val predicted = loop["predicted"] as? JsonObject
            val values = (predicted?.get("values") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }.orEmpty()
            val start = predicted?.time("startDate")
            val time = loop.time("timestamp") ?: iobObj?.time("timestamp") ?: created ?: return null
            val status = LoopStatus(
                timeMillis = time,
                iob = iobObj?.double("iob"),
                cob = (loop["cob"] as? JsonObject)?.double("cob"),
                eventualMgdl = values.lastOrNull(),
                forecast = if (start != null) points(start, values) else emptyList(),
                forecastName = if (start != null && values.isNotEmpty()) "Loop" else null,
            )
            return status.takeIf { it.iob != null || it.cob != null || it.forecast.isNotEmpty() }
        }

        private fun points(start: Long, values: List<Double>) =
            values.mapIndexed { i, v -> PredictedPoint(start + i * 5 * 60_000L, v) }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.double(key: String) = string(key)?.toDoubleOrNull()
        private fun JsonObject.bool(key: String) = string(key)?.toBooleanStrictOrNull()
        private fun JsonObject.time(vararg keys: String) = keys.firstNotNullOfOrNull { key -> string(key)?.let(::parseTime) }
    }
}
