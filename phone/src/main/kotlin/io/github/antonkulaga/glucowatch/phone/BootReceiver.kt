package io.github.antonkulaga.glucowatch.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or an update, listens for the watch again if one is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LinkService.update(context)
    }
}
