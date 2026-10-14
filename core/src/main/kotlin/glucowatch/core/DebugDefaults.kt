package glucowatch.core

import java.security.MessageDigest

/** Reapply a changed developer default, while keeping app edits when the build default is unchanged. */
object DebugDefaults {
    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun shouldApply(saved: String?, previousFingerprint: String?, value: String): Boolean =
        if (previousFingerprint == null) saved.isNullOrBlank() && value.isNotBlank()
        else previousFingerprint != fingerprint(value)
}
