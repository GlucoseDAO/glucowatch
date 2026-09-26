package io.github.antonkulaga.glucowatch.phone

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import glucowatch.core.DexcomNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Reads only ongoing G6 notifications after the user opts in. No notification text is logged or saved. */
class DexcomNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { PhoneRepository(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = PhoneSettingsStore(this).load()
        if (!settings.usesDexcomNotifications || !DexcomNotification.supports(sbn.packageName) || !sbn.isOngoing) return
        val notification = sbn.notification
        val fields = runCatching { textFields(notification) }.getOrDefault(emptyList())
        // postTime is the time Android posted this update, not a guaranteed sensor timestamp.
        // Never import activeNotifications on reconnect or stamp an existing notification with 'now'.
        val reading = DexcomNotification.parse(sbn.packageName, fields, sbn.postTime, System.currentTimeMillis())
        scope.launch { repository.acceptNotification(settings, sbn.packageName, reading) }
    }

    @Suppress("DEPRECATION") // G6 Quick Glance uses custom RemoteViews on some regional releases.
    private fun textFields(notification: Notification): List<String> = buildList {
        val extras = notification.extras
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT).forEach { key -> extras.getCharSequence(key)?.toString()?.let(::add) }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it.toString()) }
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView) add(view.text.toString())
            // Some releases expose their arrow as an accessible image description.
            view.contentDescription?.toString()?.let(::add)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        listOfNotNull(notification.contentView, notification.bigContentView).forEach { remote ->
            runCatching { visit(remote.apply(this@DexcomNotificationService, null)) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun hasAccess(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, DexcomNotificationService::class.java))
    }
}
