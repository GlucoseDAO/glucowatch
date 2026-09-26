package glucowatch.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Which layer a fetch died at. A network that interferes with traffic leaves a different trace for
 * each layer it touches, so keeping them apart is what separates DNS tampering from a blocked route
 * from TLS interception. [docs/carrier-blocking.md] explains what each one points at.
 */
enum class FailureKind(val label: String, val hint: String) {
    DNS("Name not resolved", "The network's DNS gave no answer for this host."),
    ROUTE("Server unreachable", "The name resolved but the connection never opened."),
    RESET("Connection reset", "Something on the path closed the connection."),
    TLS("TLS refused", "The secure handshake failed or the certificate was not Dexcom's."),
    TIMEOUT("No answer", "The server accepted the connection but sent nothing back."),
    SERVER("Server error", "The server answered with an error."),
    AUTH("Login rejected", "Another route cannot fix this; check the account."),
    OTHER("Failed", "");

    /** Kinds a different route, resolver or proxy can plausibly get around. */
    val routable get() = this == DNS || this == ROUTE || this == RESET || this == TLS || this == TIMEOUT
}

/** Classifies a fetch failure and keeps a short history of them, for the connection check. */
object NetworkFailure {
    const val HISTORY = 20
    private const val MAX_DETAIL = 120

    fun classify(error: Throwable?): FailureKind {
        var current = error
        var guard = 0
        while (current != null && guard++ < 12) {
            kindOf(current)?.let { return it }
            current = current.cause.takeIf { it !== current }
        }
        return FailureKind.OTHER
    }

    private fun kindOf(e: Throwable): FailureKind? {
        val message = e.message.orEmpty()
        return when {
            e is ShareException.AuthFailed || e is ShareException.TooManyAttempts -> FailureKind.AUTH
            e is ShareException.Server -> FailureKind.SERVER
            e is CareLinkException.SignInNeeded || e is NightscoutException.Unauthorized -> FailureKind.AUTH
            e is CareLinkException.Server || e is NightscoutException.Server -> FailureKind.SERVER
            e is UnknownHostException -> FailureKind.DNS
            e is SSLPeerUnverifiedException || e is SSLHandshakeException -> FailureKind.TLS
            e is SSLException -> if (isReset(message)) FailureKind.RESET else FailureKind.TLS
            e is NoRouteToHostException || e is ConnectException -> FailureKind.ROUTE
            // Android reports a connect timeout as "failed to connect to host/ip (port 443) after 8000ms".
            e is SocketTimeoutException -> if ("failed to connect" in message) FailureKind.ROUTE else FailureKind.TIMEOUT
            e is SocketException -> if (isReset(message)) FailureKind.RESET else FailureKind.ROUTE
            else -> null
        }
    }

    private fun isReset(message: String) = "reset" in message.lowercase() || "broken pipe" in message.lowercase()

    /** One line for the app's error field: the layer, then the original text for the details screen. */
    fun describe(error: Throwable?): String {
        val kind = classify(error)
        val detail = error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName
        return if (detail == null) kind.label else "${kind.label}: $detail"
    }

    /** `time,kind,transport,detail` rows, newest last, at most [HISTORY]; separators stripped from the text. */
    fun encode(records: List<FailureRecord>): String =
        records.takeLast(HISTORY).joinToString(";") { r ->
            "${r.timeMillis},${r.kind.name},${clean(r.transport)},${clean(r.detail)}"
        }

    fun decode(text: String): List<FailureRecord> =
        text.split(';').mapNotNull { row ->
            val p = row.split(',', limit = 4)
            if (p.size != 4) return@mapNotNull null
            FailureRecord(
                timeMillis = p[0].toLongOrNull() ?: return@mapNotNull null,
                kind = runCatching { FailureKind.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null,
                transport = p[2],
                detail = p[3],
            )
        }

    fun append(text: String, record: FailureRecord): String = encode(decode(text) + record)

    private val SEPARATORS = Regex("[,;\\r\\n]")

    private fun clean(value: String) = value.replace(SEPARATORS, " ").take(MAX_DETAIL).trim()
}

data class FailureRecord(
    val timeMillis: Long,
    val kind: FailureKind,
    /** How the watch was connected: `wifi`, `bluetooth`, `cellular`, or empty when unknown. */
    val transport: String,
    val detail: String,
)
