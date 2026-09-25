package io.github.antonkulaga.glucowatch.data

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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

    /** BLUETOOTH_CONNECT, shown to the user as "Nearby devices". */
    fun hasPermission() = appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Connects to the phone at [address], or with a null address to each bonded phone in turn,
     * and runs [block] on the first that answers. A refusal from the phone app ([LinkException])
     * ends the search; a device without the app is skipped.
     */
    fun <T> open(address: String?, timeoutMs: Long = TIMEOUT_MS, block: (BluetoothDevice, InputStream, OutputStream) -> T): T {
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
            bonded.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }.ifEmpty { bonded }
        }
        var last: IOException? = null
        for (device in candidates) {
            try {
                return connect(device, timeoutMs, block)
            } catch (e: LinkException) {
                throw e
            } catch (e: IOException) {
                Log.d(TAG, "No GlucoWatch on ${device.address}: ${e.message}")
                last = e
            }
        }
        throw IOException("GlucoWatch on the phone did not answer. Is it installed and Bluetooth on?", last)
    }

    private fun <T> connect(device: BluetoothDevice, timeoutMs: Long, block: (BluetoothDevice, InputStream, OutputStream) -> T): T {
        val socket = try {
            device.createRfcommSocketToServiceRecord(PhoneLink.SERVICE_UUID)
        } catch (e: SecurityException) {
            throw IOException("Allow Nearby devices for GlucoWatch in Settings")
        }
        // RFCOMM sockets have no timeouts of their own: closing the socket ends a blocked connect or read.
        val watchdog = Timer("phone-link", true).apply { schedule(timeoutMs) { runCatching { socket.close() } } }
        try {
            socket.connect()
            return block(device, socket.inputStream, socket.outputStream)
        } catch (e: SecurityException) {
            throw IOException("Allow Nearby devices for GlucoWatch in Settings")
        } finally {
            watchdog.cancel()
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
