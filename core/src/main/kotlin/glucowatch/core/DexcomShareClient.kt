package glucowatch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Dexcom Share servers. The application ids are the public ids used by Dexcom's own apps
 * (same values as pydexcom, xDrip+, Nightscout). No developer registration is needed.
 */
enum class Region(val label: String, val baseUrl: String, val applicationId: String) {
    OUS("Outside US (EU)", "https://shareous1.dexcom.com/ShareWebServices/Services/", "d89443d2-327c-4a6f-89e5-496bbb0317db"),
    US("United States", "https://share2.dexcom.com/ShareWebServices/Services/", "d89443d2-327c-4a6f-89e5-496bbb0317db"),
    JP("Japan", "https://share.dexcom.jp/ShareWebServices/Services/", "d8665ade-9673-4e27-9ff6-92db4ce13d13");

    companion object {
        /** Accepts `eu`/`ous`, `us`, `jp` in any case (as used in `.env`, CLI flags and adb extras). */
        fun parse(value: String): Region = when (value.trim().lowercase()) {
            "eu", "ous" -> OUS
            "us" -> US
            "jp" -> JP
            else -> throw IllegalArgumentException("Unknown Dexcom region '$value': use eu, us or jp")
        }
    }
}

sealed class ShareException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(message: String) : ShareException(message)
    class TooManyAttempts : ShareException("Too many login attempts, wait and try again")
    class SessionInvalid(code: String) : ShareException("Session invalid ($code)")
    class Server(val code: String?, message: String) : ShareException("Dexcom: ${code ?: "error"}: $message")
    class Network(cause: Throwable) : ShareException("Network error: ${cause.message}", cause)
}

data class HttpResponse(val status: Int, val body: String)

fun interface HttpTransport {
    fun postJson(url: String, body: String): HttpResponse
}

/** Plain HttpURLConnection transport: works on the JVM and on Android without extra dependencies. */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 10_000,
) : HttpTransport {
    override fun postJson(url: String, body: String): HttpResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "glucowatch")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpResponse(status, text)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Minimal Dexcom Share client: login → sessionId → latest glucose values.
 * [sessionId] can be persisted between runs; it is refreshed automatically when it expires.
 */
class DexcomShareClient(
    private val region: Region,
    private val username: String,
    private val password: String,
    var sessionId: String? = null,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    /** Readings from the last [minutes] (max 1440), oldest first. */
    fun readings(minutes: Int = MAX_MINUTES, maxCount: Int = MAX_COUNT): List<GlucoseReading> {
        require(minutes in 1..MAX_MINUTES) { "minutes must be in 1..$MAX_MINUTES" }
        require(maxCount in 1..MAX_COUNT) { "maxCount must be in 1..$MAX_COUNT" }
        val current = sessionId
        if (current != null) {
            try {
                return fetchReadings(current, minutes, maxCount)
            } catch (_: ShareException.SessionInvalid) {
                sessionId = null
            }
        }
        return fetchReadings(login(), minutes, maxCount)
    }

    fun latest(): GlucoseReading? = readings(minutes = 30, maxCount = 1).lastOrNull()

    /** Performs the two-step login and stores the new session id. */
    fun login(): String {
        val accountId = post(
            "General/AuthenticatePublisherAccount",
            buildJsonObject {
                put("accountName", username)
                put("password", password)
                put("applicationId", region.applicationId)
            }.toString(),
        ).asUuid("account id")
        val session = post(
            "General/LoginPublisherAccountById",
            buildJsonObject {
                put("accountId", accountId)
                put("password", password)
                put("applicationId", region.applicationId)
            }.toString(),
        ).asUuid("session id")
        sessionId = session
        return session
    }

    private fun fetchReadings(session: String, minutes: Int, maxCount: Int): List<GlucoseReading> {
        val query = "sessionId=${URLEncoder.encode(session, "UTF-8")}&minutes=$minutes&maxCount=$maxCount"
        val json = post("Publisher/ReadPublisherLatestGlucoseValues?$query", "{}")
        val array = json as? JsonArray ?: throw ShareException.Server(null, "Unexpected readings payload")
        return array.mapNotNull { parseReading(it.jsonObject) }.sortedBy { it.timeMillis }
    }

    private fun post(endpoint: String, body: String): JsonElement {
        val response = try {
            transport.postJson(region.baseUrl + endpoint, body)
        } catch (e: IOException) {
            throw ShareException.Network(e)
        }
        val json = try {
            Json.parseToJsonElement(response.body.ifBlank { "null" })
        } catch (e: Exception) {
            throw ShareException.Server(null, "HTTP ${response.status}: invalid JSON")
        }
        if (response.status !in 200..299) throw toError(json, response.status)
        return json
    }

    private fun toError(json: JsonElement, status: Int): ShareException {
        val obj = json as? JsonObject
        val code = obj?.get("Code")?.jsonPrimitive?.contentOrNull
        val message = obj?.get("Message")?.jsonPrimitive?.contentOrNull ?: "HTTP $status"
        return when {
            code == "SessionIdNotFound" || code == "SessionNotValid" -> ShareException.SessionInvalid(code)
            code == "AccountPasswordInvalid" -> ShareException.AuthFailed("Wrong username or password")
            code == "SSO_AuthenticateMaxAttemptsExceeded" -> ShareException.TooManyAttempts()
            code == "SSO_InternalError" && message.contains("Cannot Authenticate") ->
                ShareException.AuthFailed("Account not found in this region")
            else -> ShareException.Server(code, message)
        }
    }

    private fun JsonElement.asUuid(what: String): String {
        val value = (this as? JsonPrimitive)?.contentOrNull
        if (value == null || !UUID_REGEX.matches(value) || value == ZERO_UUID) {
            throw ShareException.AuthFailed("Dexcom returned no valid $what")
        }
        return value
    }

    companion object {
        const val MAX_MINUTES = 1440
        const val MAX_COUNT = 288
        private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")
        private val DATE_REGEX = Regex("""Date\((\d+)""")

        internal fun parseReading(obj: JsonObject): GlucoseReading? {
            val time = listOf("WT", "ST", "DT").firstNotNullOfOrNull { key ->
                obj[key]?.jsonPrimitive?.contentOrNull?.let { DATE_REGEX.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            } ?: return null
            val value = obj["Value"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return null
            val trend = obj["Trend"]?.jsonPrimitive?.contentOrNull?.let(Trend::parse) ?: Trend.None
            return GlucoseReading(time, value, trend)
        }
    }
}
