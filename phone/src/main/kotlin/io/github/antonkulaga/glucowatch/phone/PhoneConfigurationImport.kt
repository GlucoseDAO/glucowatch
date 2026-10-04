package io.github.antonkulaga.glucowatch.phone

import android.content.Context
import android.net.Uri
import glucowatch.core.GlucoseUnit
import glucowatch.core.ImportedConfiguration
import glucowatch.core.NightscoutApi
import glucowatch.core.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads only the supplied config location. Source/HF credentials are never attached to this request. */
class PhoneConfigurationImport(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun file(uri: Uri): ImportedConfiguration = withContext(Dispatchers.IO) {
        val bytes = try {
            (resolver.openInputStream(uri) ?: error("Cannot open file")).use(::bounded)
        } catch (_: Exception) { error("Could not read configuration file (maximum 64 KiB)") }
        decode(bytes)
    }

    suspend fun url(address: String): ImportedConfiguration = withContext(Dispatchers.IO) {
        var url = try { URL(address.trim()) } catch (_: Exception) { error("Enter a configuration HTTPS URL") }
        repeat(4) {
            require(url.userInfo == null && (url.protocol == "https" ||
                (BuildConfig.DEBUG && url.protocol == "http" && url.host in setOf("10.0.2.2", "localhost")))) {
                "Configuration URLs must use HTTPS"
            }
            val connection = try {
                (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Accept", "text/plain, application/yaml, text/yaml")
                }
            } catch (_: Exception) { error("Could not open configuration URL") }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("Missing redirect"))
                } else {
                    require(status == 200) { "Configuration server returned HTTP $status" }
                    require(connection.contentLengthLong <= ImportedConfiguration.MAX_BYTES) { "Configuration exceeds 64 KiB" }
                    return@withContext decode(connection.inputStream.use(::bounded))
                }
            } catch (e: IllegalArgumentException) { throw e }
            catch (e: IllegalStateException) { throw e }
            catch (_: Exception) { error("Could not download configuration; check the URL and connection") }
            finally { connection.disconnect() }
        }
        error("Too many configuration redirects")
    }

    private fun decode(bytes: ByteArray): ImportedConfiguration {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { error("Configuration must be UTF-8 text") }
        return ImportedConfiguration.parse(text)
    }

    private fun bounded(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= ImportedConfiguration.MAX_BYTES) { "Configuration exceeds 64 KiB" }
            output.write(chunk, 0, count)
        }
    }
}

/** Omitted settings keep their current value. Account changes use PhoneRepository's cache guard. */
fun ImportedConfiguration.applyTo(old: PhoneSettings): PhoneSettings = old.copy(
    source = source ?: old.source,
    username = this["DEXCOM_USERNAME"] ?: old.username,
    password = this["DEXCOM_PASSWORD"] ?: old.password,
    region = this["DEXCOM_REGION"]?.let(Region::parse) ?: old.region,
    nightscoutUrl = this["NIGHTSCOUT_URL"] ?: old.nightscoutUrl,
    nightscoutToken = this["NIGHTSCOUT_TOKEN"] ?: old.nightscoutToken,
    nightscoutApi = this["NIGHTSCOUT_API"]?.let(NightscoutApi::parse) ?: old.nightscoutApi,
    unit = this["GLUCOWATCH_UNIT"]?.let(GlucoseUnit::parse) ?: old.unit,
    alsoFrom = alsoFrom ?: old.alsoFrom,
    heartTrack = this["GLUCOWATCH_HEART_TRACK"]?.toBoolean() ?: old.heartTrack,
    dexcomNotifications = this["DEXCOM_NOTIFICATIONS"]?.toBoolean() ?: old.dexcomNotifications,
    huggingFaceToken = this["HF_TOKEN"] ?: old.huggingFaceToken,
    modelAddress = this["HF_MODEL_ADDRESS"] ?: old.modelAddress,
    carelinkCountry = this["CARELINK_COUNTRY"]?.uppercase() ?: old.carelinkCountry,
    carelinkAccount = carelinkToken?.subject ?: old.carelinkAccount,
)
