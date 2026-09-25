package glucowatch.core.link

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for the phone link, from the JDK and Android's platform providers only.
 * Messages are AES-256-GCM under a key both sides derived when they paired.
 */
object LinkCrypto {
    private val random = SecureRandom()

    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach(::update) }.doFinal()

    /** A fresh 12-byte nonce, then the ciphertext with its tag. [aad] is authenticated but not sent. */
    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plain)
    }

    /** Throws [LinkException] when the key, the [aad] or a single byte differs. */
    fun open(key: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) throw LinkException("Message from the other side is too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            throw LinkException("The pairing no longer matches. Pair the watch and the phone again.")
        }
    }

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
}

/**
 * One side of a pairing: an ephemeral P-256 key pair. The watch commits to its public key before
 * it sees the phone's, and reveals it after, so a device in the middle cannot pick keys that give
 * both sides the same [PairingKeys.code]. The user compares that code on both screens.
 */
class PairingKeyPair private constructor(private val pair: KeyPair) {
    /** X.509 encoding, as sent over the link. */
    val publicKey: ByteArray = pair.public.encoded

    val commitment: ByteArray get() = LinkCrypto.sha256(publicKey)

    /** [watchPublic] and [phonePublic] are both encoded keys; one of them is this side's own. */
    fun agree(otherPublic: ByteArray, watchId: ByteArray, phoneId: ByteArray, watchPublic: ByteArray, phonePublic: ByteArray): PairingKeys {
        val other = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(otherPublic)) as ECPublicKey
        } catch (e: Exception) {
            throw LinkException("The other side sent an unreadable key")
        }
        if (other.params.curve != (pair.public as ECPublicKey).params.curve) throw LinkException("The other side sent a key on another curve")
        val shared = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(other, true); generateSecret() }
        val prk = LinkCrypto.hmac(LinkCrypto.sha256(watchId, phoneId, watchPublic, phonePublic), shared)
        val key = LinkCrypto.hmac(prk, "glucowatch link key".toByteArray(), byteArrayOf(1))
        val codeBytes = LinkCrypto.hmac(prk, "glucowatch pairing code".toByteArray(), byteArrayOf(1))
        val code = (ByteBuffer.wrap(codeBytes).int.toLong() and 0xffffffffL) % 1_000_000
        return PairingKeys(key, "%06d".format(code))
    }

    companion object {
        fun generate(): PairingKeyPair =
            PairingKeyPair(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())
    }
}

/** [key] encrypts every later message; [code] is the six digits the user compares on both screens. */
class PairingKeys(val key: ByteArray, val code: String) {
    /** "123 456", easier to compare at a glance. */
    val displayCode get() = code.substring(0, 3) + " " + code.substring(3)
}

class LinkException(message: String) : java.io.IOException(message)
