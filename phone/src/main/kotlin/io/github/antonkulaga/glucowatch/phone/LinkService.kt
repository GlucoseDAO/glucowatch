package io.github.antonkulaga.glucowatch.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import glucowatch.core.link.PairingKeys
import glucowatch.core.link.PhoneLink
import glucowatch.core.link.PhoneLinkHandler
import glucowatch.core.link.PhoneLinkServer
import java.io.IOException
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

/**
 * Pairing is open for two minutes after the user taps "Pair a watch". A watch that pairs in that
 * time is [pending] until the user confirms its code here. [listener] runs on the main thread.
 */
object PairingWindow {
    class Pending(val watchId: ByteArray, val watchName: String, val keys: PairingKeys)

    private const val OPEN_MS = 2 * 60_000L
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var openUntil = 0L

    @Volatile var pending: Pending? = null
        private set

    var listener: (() -> Unit)? = null

    val isOpen get() = System.currentTimeMillis() < openUntil

    fun open() {
        openUntil = System.currentTimeMillis() + OPEN_MS
        pending = null
        notifyListener()
        main.postDelayed(::notifyListener, OPEN_MS + 500)
    }

    fun close() {
        openUntil = 0
        pending = null
        notifyListener()
    }

    internal fun offer(p: Pending) {
        pending = p
        notifyListener()
    }

    private fun notifyListener() {
        main.post { listener?.invoke() }
    }
}

/**
 * Listens on the RFCOMM service record for the watch and answers one request per connection.
 * Runs as a foreground service while a watch is paired or pairing is open, so Android keeps it.
 */
class LinkService : Service() {
    @Volatile private var running = true
    @Volatile private var server: BluetoothServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_link), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Sharing glucose with your watch")
            .setContentText("Over Bluetooth, only with watches you paired")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Android 14 and later refuse a connected-device service without the Nearby devices permission.
            Log.w(TAG, "Cannot start the link service", e)
            stopSelf()
            return
        }
        thread(name = "phone-link-server", isDaemon = true) { listen() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        super.onDestroy()
    }

    /** Accepts connections until the service stops; waits and listens again when Bluetooth is off. */
    private fun listen() {
        while (running) {
            try {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
                val socket = adapter.listenUsingRfcommWithServiceRecord(PhoneLink.SERVICE_NAME, PhoneLink.SERVICE_UUID)
                server = socket
                while (running) {
                    val client = socket.accept()
                    thread(name = "phone-link-client", isDaemon = true) { answer(client) }
                }
            } catch (e: IOException) {
                if (running) Log.d(TAG, "Listening stopped: ${e.message}; retrying")
            } catch (e: SecurityException) {
                Log.w(TAG, "No Bluetooth permission", e)
                stopSelf()
                return
            } finally {
                runCatching { server?.close() }
            }
            if (running) Thread.sleep(RETRY_MS)
        }
    }

    private fun answer(socket: BluetoothSocket) {
        val name = runCatching { socket.remoteDevice.name }.getOrNull() ?: "Watch"
        // No socket timeouts on RFCOMM: closing the socket ends a watch that stops talking.
        val watchdog = Timer("phone-link-watchdog", true).apply { schedule(TIMEOUT_MS) { runCatching { socket.close() } } }
        try {
            PhoneLinkServer.serve(socket.inputStream, socket.outputStream, Link(this, name))
        } catch (e: IOException) {
            Log.d(TAG, "Connection from $name ended: ${e.message}")
        } finally {
            watchdog.cancel()
            runCatching { socket.close() }
        }
    }

    private class Link(context: Context, private val watchName: String) : PhoneLinkHandler {
        private val repository = PhoneRepository(context)
        private val watches = PairedWatches(context)
        private val settings = PhoneSettingsStore(context)
        private val adapterName = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull()

        override val phoneId get() = watches.phoneId
        override val phoneName get() = adapterName ?: Build.MODEL

        override fun pairingOpen() = PairingWindow.isOpen

        override fun offerPairing(watchId: ByteArray, keys: PairingKeys) = PairingWindow.offer(PairingWindow.Pending(watchId, watchName, keys))

        override fun keyFor(watchId: ByteArray) = watches.keyFor(watchId)

        override fun snapshot(horizonMinutes: Int) = repository.snapshot(horizonMinutes)

        override fun account() = settings.load().toLink()

        override fun handOverCareLink() = repository.handOverCareLink()
    }

    companion object {
        private const val TAG = "GlucoLink"
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1
        private const val RETRY_MS = 15_000L
        private const val TIMEOUT_MS = 60_000L

        fun hasPermission(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** Starts the service if the watch could need it; stops it when no watch is paired and pairing is closed. */
        fun update(context: Context) {
            val intent = Intent(context, LinkService::class.java)
            val needed = PairedWatches(context).all().isNotEmpty() || PairingWindow.isOpen
            if (!needed || !hasPermission(context)) {
                context.stopService(intent)
                return
            }
            // Android can refuse a foreground service started from the background; the next app start retries.
            runCatching { context.startForegroundService(intent) }.onFailure { Log.w(TAG, "Cannot start the link service", it) }
        }
    }
}
