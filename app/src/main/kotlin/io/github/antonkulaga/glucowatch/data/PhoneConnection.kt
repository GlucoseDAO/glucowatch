package io.github.antonkulaga.glucowatch.data

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import glucowatch.core.link.LinkCrypto
import glucowatch.core.link.LinkException
import glucowatch.core.link.PhoneLink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.Timer
import java.util.concurrent.atomic.AtomicReference
import android.os.SystemClock
import kotlin.concurrent.schedule

/** The phone app this watch paired with. [key] seals every request, [address] is the phone's Bluetooth address. */
class PhonePairing(val phoneId: String, val phoneName: String, val address: String, val key: ByteArray)

/** The pairing and the watch's own link id, in app-private storage beside the settings. */
class PhonePairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("link", Context.MODE_PRIVATE)

    /** Random, made once per install; the phone knows the watch by it. */
    val watchId: ByteArray
        get() = prefs.getString("watchId", null)?.let(Base64.getDecoder()::decode)
            ?: LinkCrypto.randomBytes(PhoneLink.ID_BYTES).also { prefs.edit().putString("watchId", Base64.getEncoder().encodeToString(it)).apply() }

    fun load(): PhonePairing? {
        val key = prefs.getString("key", null) ?: return null
        return PhonePairing(
            phoneId = prefs.getString("phoneId", "")!!,
            phoneName = prefs.getString("phoneName", "")!!,
            address = prefs.getString("address", "")!!,
            key = Base64.getDecoder().decode(key),
        )
    }

    fun save(p: PhonePairing) {
        prefs.edit()
            .putString("phoneId", p.phoneId)
            .putString("phoneName", p.phoneName)
            .putString("address", p.address)
            .putString("key", Base64.getEncoder().encodeToString(p.key))
            .apply()
    }
}

/**
 * One RFCOMM connection to GlucoWatch on the phone per call. The watch and the phone are already
 * bonded by Wear OS, so there is no scanning and no system pairing dialog.
 */
class PhoneConnection(context: Context) {
    private val appContext = context.applicationContext
    private val activeSocket = AtomicReference<BluetoothSocket?>()
    @Volatile private var cancelled = false
    private var discoveryOffset = 0

    /** Close a blocked pairing connect/read immediately when its screen cancels the search. */
    fun cancel() {
        cancelled = true
        runCatching { activeSocket.getAndSet(null)?.close() }
    }

    /** BLUETOOTH_CONNECT, shown to the user as "Nearby devices". */
    fun hasPermission() = appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Connects to the phone at [address], or with a null address to each bonded phone in turn,
     * and runs [block] on the first that answers. A refusal from the phone app ([LinkException])
     * ends the search; a device without the app is skipped.
     */
    fun <T> open(address: String?, timeoutMs: Long = TIMEOUT_MS, totalTimeoutMs: Long? = null,
                 block: (BluetoothDevice, InputStream, OutputStream) -> T): T {
        if (cancelled) throw IOException("Pairing cancelled")
        val deadline = totalTimeoutMs?.let { SystemClock.elapsedRealtime() + it }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("This watch has no Bluetooth")
        if (!hasPermission()) throw IOException("Allow Nearby devices for GlucoWatch in Settings")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val bonded = try {
            adapter.bondedDevices.orEmpty().toList()
        } catch (e: SecurityException) {
            throw IOException("Allow Nearby devices for GlucoWatch in Settings")
        }
        val candidates = if (address != null) {
            bonded.filter { it.address == address }.ifEmpty { throw IOException("The paired phone is not connected to this watch") }
        } else {
            // Phones first; a watch is bonded to few devices, but earbuds each cost a timeout.
            val ordered = bonded.sortedByDescending { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            // An unreachable old phone must not consume every retry's entire connection budget.
            val offset = if (ordered.isEmpty()) 0 else discoveryOffset++ % ordered.size
            ordered.drop(offset) + ordered.take(offset)
        }
        if (candidates.isEmpty()) throw IOException("Connect this watch to your phone in Galaxy Wearable first, then try pairing again.")
        var last: IOException? = null
        for (device in candidates) {
            if (cancelled) throw IOException("Pairing cancelled")
            val remaining = deadline?.let { it - SystemClock.elapsedRealtime() } ?: timeoutMs
            if (remaining <= 0) break
            try {
                return connect(device, minOf(timeoutMs, remaining), block)
            } catch (e: LinkException) {
                throw e
            } catch (e: IOException) {
                Log.d(TAG, "No GlucoWatch on ${device.address}: ${e.message}")
                last = e
            }
        }
        throw IOException("GlucoPhone did not answer. On the phone, open GlucoPhone → Watch → Pair a watch and allow Nearby devices.", last)
    }

    private fun <T> connect(device: BluetoothDevice, timeoutMs: Long, block: (BluetoothDevice, InputStream, OutputStream) -> T): T {
        val socket = try {
            device.createRfcommSocketToServiceRecord(PhoneLink.SERVICE_UUID)
        } catch (e: SecurityException) {
            throw IOException("Allow Nearby devices for GlucoWatch in Settings")
        }
        activeSocket.set(socket)
        // RFCOMM sockets have no timeouts of their own: closing the socket ends a blocked connect or read.
        val watchdog = Timer("phone-link", true).apply { schedule(timeoutMs) { runCatching { socket.close() } } }
        try {
            if (cancelled) throw IOException("Pairing cancelled")
            socket.connect()
            return block(device, socket.inputStream, socket.outputStream)
        } catch (e: SecurityException) {
            throw IOException("Allow Nearby devices for GlucoWatch in Settings")
        } finally {
            watchdog.cancel()
            activeSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "GlucoPhone"

        /** Long enough for the phone to fetch from Dexcom or Nightscout before it answers. */
        const val TIMEOUT_MS = 45_000L
    }
}

fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
