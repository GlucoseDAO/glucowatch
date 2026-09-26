package glucowatch.core.link

import glucowatch.core.CacheFormat
import glucowatch.core.CareLinkToken
import glucowatch.core.GlucoseReading
import glucowatch.core.LoopStatus
import glucowatch.core.NightscoutApi
import glucowatch.core.Prediction
import glucowatch.core.Region
import glucowatch.core.Treatment
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * The link between the watch app and the optional phone app, over a Bluetooth RFCOMM socket.
 * The watch opens one connection per request and the phone answers it. Pairing agrees on a key
 * (see [PairingKeyPair]); every later request and answer is sealed with it. docs/phone-link.md
 * describes the exchange.
 */
object PhoneLink {
    /** The phone's RFCOMM service record. Fixed: another value breaks every installed pairing. */
    val SERVICE_UUID: UUID = UUID.fromString("7d0c6a8e-2f41-4b9a-b3e5-1c9f0a6d42e7")
    const val SERVICE_NAME = "GlucoWatch"
    // Version 2 carries basal kind, rate and duration in the treatment payload.
    const val VERSION = 2
    const val ID_BYTES = 16

    /** [Prediction.modelId] of a forecast made on the phone; also the watch setting that selects it. */
    const val MODEL_ID = "phone"

    internal const val PAIR = 1
    internal const val SYNC = 2
    internal const val ACCOUNT = 3

    /** Added after [VERSION] 1 shipped: a phone app without it answers "Unknown request". */
    internal const val CARELINK = 4
    internal const val OK = 0
    internal const val ERROR = 1
    internal const val MAX_MESSAGE = 1 shl 20
    private const val CHALLENGE_BYTES = 16
    private const val REQUEST = 1
    private const val ANSWER = 2

    /** Binds a sealed message to its direction, its kind and the watch, so none can be replayed as another. */
    internal fun aad(direction: Int, kind: Int, watchId: ByteArray) = byteArrayOf(direction.toByte(), kind.toByte()) + watchId

    /** Asks with a fresh challenge and checks that the sealed answer carries it back. */
    internal fun request(link: Frames, watchId: ByteArray, key: ByteArray, kind: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val challenge = LinkCrypto.randomBytes(CHALLENGE_BYTES)
        val sealed = LinkCrypto.seal(key, aad(REQUEST, kind, watchId), message { write(challenge); body() })
        link.send(message { writeInt(VERSION); writeByte(kind); write(watchId); writeBlob(sealed) })
        val answer = LinkCrypto.open(key, aad(ANSWER, kind, watchId), link.receiveOk().read { readBlob() })
        if (!answer.copyOfRange(0, CHALLENGE_BYTES).contentEquals(challenge)) throw LinkException("The phone answered another request")
        return answer.copyOfRange(CHALLENGE_BYTES, answer.size)
    }

    /** Opens a watch's request and seals [answer] to it under the same challenge. */
    internal fun answer(link: Frames, watchId: ByteArray, key: ByteArray, kind: Int, sealed: ByteArray, answer: (DataInputStream) -> ByteArray) {
        val plain = LinkCrypto.open(key, aad(REQUEST, kind, watchId), sealed)
        val challenge = plain.copyOfRange(0, CHALLENGE_BYTES)
        val payload = plain.copyOfRange(CHALLENGE_BYTES, plain.size).read { answer(this) }
        link.ok { writeBlob(LinkCrypto.seal(key, aad(ANSWER, kind, watchId), challenge + payload)) }
    }
}

/** The phone's own sources, which a watch can copy. */
enum class LinkSource { DEMO, SHARE, NIGHTSCOUT, CARELINK }

/** The phone's login, sent when the user copies it to the watch. */
data class LinkAccount(
    val source: LinkSource,
    val username: String = "",
    val password: String = "",
    val region: Region = Region.OUS,
    val nightscoutUrl: String = "",
    val nightscoutToken: String = "",
    val nightscoutApi: NightscoutApi = NightscoutApi.V1,
)

/** What the phone relays: the last day from its own source, and a forecast if the watch asked for one. */
data class LinkSnapshot(
    /** Stands for the phone's account and changes with it, so the watch never merges two accounts. */
    val upstream: String,
    /** "Dexcom Share", "Nightscout" or "Demo data", for the watch's source line. */
    val sourceLabel: String,
    val readings: List<GlucoseReading>,
    val treatments: List<Treatment> = emptyList(),
    val loop: LoopStatus? = null,
    val forecast: Prediction? = null,
    /** Why the phone's last fetch failed, if it did. */
    val error: String? = null,
)

/** A pairing both sides have agreed on, before the watch's user confirms the code. */
class PairingOffer(val phoneId: ByteArray, val phoneName: String, val keys: PairingKeys)

/** The watch's end. Each call is one connection: [input] and [output] are the socket's streams. */
class WatchLinkClient(private val watchId: ByteArray) {
    init {
        require(watchId.size == PhoneLink.ID_BYTES)
    }

    fun pair(input: InputStream, output: OutputStream): PairingOffer {
        val link = Frames(input, output)
        val own = PairingKeyPair.generate()
        link.send(message { writeInt(PhoneLink.VERSION); writeByte(PhoneLink.PAIR); write(watchId); writeBlob(own.commitment) })
        val phone = link.receiveOk()
        val (phoneId, phonePublic, phoneName) = phone.read { Triple(readBytes(PhoneLink.ID_BYTES), readBlob(), readText()) }
        link.send(message { writeBlob(own.publicKey) })
        link.receiveOk()
        return PairingOffer(phoneId, phoneName, own.agree(phonePublic, watchId, phoneId, own.publicKey, phonePublic))
    }

    /** [horizonMinutes] above 0 asks the phone for its forecast too. */
    fun sync(input: InputStream, output: OutputStream, key: ByteArray, horizonMinutes: Int): LinkSnapshot =
        PhoneLink.request(Frames(input, output), watchId, key, PhoneLink.SYNC) { writeInt(horizonMinutes) }.read { readSnapshot() }

    fun account(input: InputStream, output: OutputStream, key: ByteArray): LinkAccount =
        PhoneLink.request(Frames(input, output), watchId, key, PhoneLink.ACCOUNT) {}.read { readAccount() }

    /** Moves the phone's CareLink sign-in to the watch; the phone keeps no copy (see [PhoneLinkHandler.handOverCareLink]). */
    fun carelink(input: InputStream, output: OutputStream, key: ByteArray): CareLinkToken {
        val text = try {
            PhoneLink.request(Frames(input, output), watchId, key, PhoneLink.CARELINK) {}.read { readText() }
        } catch (e: LinkException) {
            if (e.message.orEmpty().startsWith("Unknown request")) throw LinkException("Update the phone app: this one cannot pass on a CareLink sign-in")
            throw e
        }
        return CareLinkToken.decode(text) ?: throw LinkException("The phone has no CareLink sign-in. Sign in to CareLink in the phone app first")
    }
}

/** What the phone app provides to [PhoneLinkServer]. Called on the connection's thread. */
interface PhoneLinkHandler {
    val phoneId: ByteArray
    val phoneName: String

    /** Whether the user started pairing on the phone. A watch that asks at any other time is turned away. */
    fun pairingOpen(): Boolean

    /** Both screens now show [keys]'s code. The phone keeps the key only once its user confirms. */
    fun offerPairing(watchId: ByteArray, keys: PairingKeys)

    /** The key of a watch this phone has paired with, or null. */
    fun keyFor(watchId: ByteArray): ByteArray?

    fun snapshot(horizonMinutes: Int): LinkSnapshot

    fun account(): LinkAccount

    /**
     * The phone's CareLink sign-in as [CareLinkToken.encode] JSON, which the phone then forgets, or
     * null without one. CareLink rotates the refresh token on every use, so a sign-in kept on two
     * devices logs both out at the second refresh: it moves, it is not copied.
     */
    fun handOverCareLink(): String? = null
}

/** The phone's end: answers the one request that arrives on a connection. Never throws [LinkException]. */
object PhoneLinkServer {
    fun serve(input: InputStream, output: OutputStream, handler: PhoneLinkHandler) {
        val link = Frames(input, output)
        try {
            val request = DataInputStream(link.receive().inputStream())
            if (request.readInt() != PhoneLink.VERSION) {
                throw LinkException("The watch and the phone run different versions of GlucoWatch. Update both.")
            }
            val kind = request.readByte().toInt()
            val watchId = request.readBytes(PhoneLink.ID_BYTES)
            when (kind) {
                PhoneLink.PAIR -> pair(link, handler, watchId, request.readBlob())
                PhoneLink.SYNC, PhoneLink.ACCOUNT, PhoneLink.CARELINK -> {
                    val key = handler.keyFor(watchId) ?: throw LinkException("This phone does not know this watch. Pair them again.")
                    PhoneLink.answer(link, watchId, key, kind, request.readBlob()) { body ->
                        when (kind) {
                            PhoneLink.SYNC -> message { writeSnapshot(handler.snapshot(body.readInt())) }
                            PhoneLink.ACCOUNT -> message { writeAccount(handler.account()) }
                            else -> message { writeText(handler.handOverCareLink().orEmpty()) }
                        }
                    }
                }
                else -> throw LinkException("Unknown request $kind")
            }
        } catch (e: EOFException) {
            // The watch hung up; nobody is left to tell.
        } catch (e: Exception) {
            runCatching { link.error(e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun pair(link: Frames, handler: PhoneLinkHandler, watchId: ByteArray, commitment: ByteArray) {
        if (!handler.pairingOpen()) throw LinkException("On the phone, open GlucoWatch and tap Pair a watch first")
        val own = PairingKeyPair.generate()
        link.ok { write(handler.phoneId); writeBlob(own.publicKey); writeText(handler.phoneName) }
        val watchPublic = link.receive().read { readBlob() }
        if (!LinkCrypto.sha256(watchPublic).contentEquals(commitment)) throw LinkException("The watch's key does not match what it announced")
        val keys = own.agree(watchPublic, watchId, handler.phoneId, watchPublic, own.publicKey)
        handler.offerPairing(watchId, keys)
        link.ok {}
    }
}

/** Length-prefixed messages over a stream. An answer starts with [PhoneLink.OK] or [PhoneLink.ERROR]. */
internal class Frames(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)

    fun send(bytes: ByteArray) {
        output.writeBlob(bytes)
        output.flush()
    }

    fun receive(): ByteArray = input.readBlob()

    fun ok(body: DataOutputStream.() -> Unit) = send(message { writeByte(PhoneLink.OK); body() })

    fun error(text: String) = send(message { writeByte(PhoneLink.ERROR); writeText(text) })

    /** The answer after its status byte; an error answer becomes a [LinkException] with the other side's text. */
    fun receiveOk(): ByteArray {
        val answer = receive()
        if (answer.isEmpty()) throw LinkException("Empty answer from the other side")
        if (answer[0].toInt() == PhoneLink.ERROR) throw LinkException(answer.copyOfRange(1, answer.size).read { readText() })
        return answer.copyOfRange(1, answer.size)
    }
}

internal fun message(write: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { DataOutputStream(it).apply(write).flush() }.toByteArray()

internal fun <T> ByteArray.read(block: DataInputStream.() -> T): T = try {
    DataInputStream(inputStream()).block()
} catch (e: EOFException) {
    throw LinkException("Message from the other side is cut short")
}

internal fun DataOutputStream.writeBlob(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
}

internal fun DataInputStream.readBlob(): ByteArray {
    val size = readInt()
    if (size !in 0..PhoneLink.MAX_MESSAGE) throw LinkException("Message from the other side has a bad length")
    return readBytes(size)
}

internal fun DataInputStream.readBytes(size: Int) = ByteArray(size).also(::readFully)

internal fun DataOutputStream.writeText(text: String) = writeBlob(text.toByteArray(Charsets.UTF_8))

internal fun DataInputStream.readText() = String(readBlob(), Charsets.UTF_8)

private fun DataOutputStream.writeSnapshot(s: LinkSnapshot) {
    writeText(s.upstream)
    writeText(s.sourceLabel)
    writeText(s.error.orEmpty())
    writeText(CacheFormat.encodeReadings(s.readings))
    writeText(CacheFormat.encodeTreatments(s.treatments))
    writeText(CacheFormat.encodeLoop(s.loop))
    writeText(CacheFormat.encodePrediction(s.forecast))
}

private fun DataInputStream.readSnapshot() = LinkSnapshot(
    upstream = readText(),
    sourceLabel = readText(),
    error = readText().ifEmpty { null },
    readings = CacheFormat.decodeReadings(readText()),
    treatments = CacheFormat.decodeTreatments(readText()),
    loop = CacheFormat.decodeLoop(readText()),
    forecast = CacheFormat.decodePrediction(readText()),
)

private fun DataOutputStream.writeAccount(a: LinkAccount) {
    listOf(a.source.name, a.username, a.password, a.region.name, a.nightscoutUrl, a.nightscoutToken, a.nightscoutApi.name).forEach(::writeText)
}

private fun DataInputStream.readAccount(): LinkAccount {
    val f = List(7) { readText() }
    fun <E : Enum<E>> parse(values: Array<E>, name: String) = values.firstOrNull { it.name == name } ?: throw LinkException("Unknown value '$name' from the phone")
    return LinkAccount(parse(LinkSource.entries.toTypedArray(), f[0]), f[1], f[2], parse(Region.entries.toTypedArray(), f[3]), f[4], f[5], parse(NightscoutApi.entries.toTypedArray(), f[6]))
}
