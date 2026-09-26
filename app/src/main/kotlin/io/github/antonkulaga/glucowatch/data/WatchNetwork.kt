package io.github.antonkulaga.glucowatch.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * What the watch's connection looks like right now, and which other route it could try.
 *
 * A watch without its own SIM reaches the internet either over Wi-Fi or over the Bluetooth link to
 * the phone, so the phone's carrier is the one that decides whether Dexcom is reachable. The
 * facts below are what separates a network that filters DNS from one that has no IPv4 route at all.
 */
class WatchNetwork(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** `wifi`, `bluetooth`, `cellular`, `ethernet`, or empty when nothing is connected. */
    fun transport(): String = manager?.activeNetwork?.let { label(manager.getNetworkCapabilities(it)) }.orEmpty()

    /**
     * A connected, validated route that is not the default one. On a watch with no SIM this is
     * usually Wi-Fi while Bluetooth is the default, or the other way round.
     */
    @Suppress("DEPRECATION") // A short fetch needs a synchronous snapshot of the already connected networks.
    fun alternate(): Network? = manager?.let { cm ->
        val active = cm.activeNetwork
        cm.allNetworks.firstOrNull { network ->
            if (network == active) return@firstOrNull false
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                ROUTES.any(caps::hasTransport)
        }
    }

    /** Lines for the connection check, in the order they are worth reading. */
    fun facts(): List<Pair<String, String>> {
        val cm = manager ?: return listOf("Network" to "unavailable")
        val active = cm.activeNetwork ?: return listOf("Network" to "not connected")
        val caps = cm.getNetworkCapabilities(active)
        val link = cm.getLinkProperties(active)
        return buildList {
            add("Route" to (label(caps).ifEmpty { "unknown" } + if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "" else ", not validated"))
            add("Addresses" to addresses(link))
            add("DNS" to link?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.joinToString(" ").ifEmpty { "none" })
            add("Private DNS" to privateDns(link))
            link?.nat64Prefix?.let { add("NAT64" to "$it — this network puts IPv4 servers behind IPv6") }
            add("Other route" to (alternate()?.let { label(cm.getNetworkCapabilities(it)) } ?: "none"))
        }
    }

    /** An IPv6-only link is the usual reason a server that answers on Wi-Fi is unreachable on mobile data. */
    private fun addresses(link: LinkProperties?): String {
        val all = link?.linkAddresses.orEmpty()
        if (all.isEmpty()) return "none"
        val v4 = all.any { it.address is Inet4Address }
        val v6 = all.any { it.address !is Inet4Address }
        return when {
            v4 && v6 -> "IPv4 and IPv6"
            v4 -> "IPv4 only"
            else -> "IPv6 only — an IPv4-only server is reachable only through NAT64"
        }
    }

    private fun privateDns(link: LinkProperties?) = when {
        link == null -> "unknown"
        link.privateDnsServerName != null -> "${link.privateDnsServerName} (encrypted)"
        link.isPrivateDnsActive -> "on, automatic"
        else -> "off — the network's own resolver answers"
    }

    private fun label(caps: NetworkCapabilities?) = when {
        caps == null -> ""
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> ""
    }

    private companion object {
        /** A watch with no SIM reaches the internet over Bluetooth, so that route counts too. */
        val ROUTES = listOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
            NetworkCapabilities.TRANSPORT_CELLULAR,
        )
    }
}
