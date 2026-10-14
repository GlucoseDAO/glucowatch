package glucowatch.core

import java.net.URI

/** Manual, patient-account portals. No credentials or access tokens enter these URLs. */
enum class ExportPortal(val label: String, val source: String) {
    CARELINK("CareLink Personal", ExportRow.SOURCE_CARELINK),
    CLARITY("Dexcom Clarity", ExportRow.SOURCE_DEXCOM_CLARITY);

    /** No wildcard for the whole web: only CSV-producing provider frames get the message channel. */
    fun captureOrigins(country: String, region: Region): Set<String> = when (this) {
        CARELINK -> listOf("minimed.eu", "minimed.com", "medtronic.com", "medtronic.eu")
            .flatMap { listOf("https://$it", "https://*.$it") }.toSet()
        CLARITY -> setOf(url(country, region))
    }

    fun url(country: String, region: Region): String = when (this) {
        CARELINK -> if (country.equals("US", true)) "https://carelink.minimed.com" else "https://carelink.minimed.eu"
        CLARITY -> when (region) {
            Region.US -> "https://clarity.dexcom.com"
            Region.OUS -> "https://clarity.dexcom.eu"
            Region.JP -> "https://clarity.dexcom.jp"
        }
    }

    fun permits(address: String, country: String, region: Region, download: Boolean = false): Boolean {
        val origin = origin(address) ?: return false
        val host = URI(origin).host
        return when (this) {
            CARELINK -> listOf("minimed.eu", "minimed.com", "medtronic.com", "medtronic.eu")
                .any { host == it || host.endsWith(".$it") }
            CLARITY -> if (download) origin == url(country, region)
                else listOf("dexcom.com", "dexcom.eu", "dexcom.jp")
                    .any { host == it || host.endsWith(".$it") }
        }
    }

    companion object {
        fun sequence(content: ExportContent, latest: LatestDevice): List<ExportPortal> {
            val first = if (latest == LatestDevice.MINIMED_PUMP) CARELINK else CLARITY
            return listOf(first, if (first == CARELINK) CLARITY else CARELINK).filter {
                if (it == CARELINK) content.pump else content.sensor
            }
        }

        fun origin(address: String): String? = runCatching {
            val uri = URI(address)
            if (uri.scheme != "https" || uri.userInfo != null || uri.host == null ||
                (uri.port != -1 && uri.port != 443)) null else "https://${uri.host.lowercase()}"
        }.getOrNull()
    }
}
