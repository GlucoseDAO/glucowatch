package io.github.antonkulaga.glucowatch.ui

import glucowatch.alerts.AlertsActivity
import io.github.antonkulaga.glucowatch.data.RefreshReceiver

class GlucoseAlertsActivity : AlertsActivity() {
    override fun warningAccount() = io.github.antonkulaga.glucowatch.data.GlucoseRepository(this).state().let { it.settings.accountKey + ":" + it.relayedAccount.orEmpty() }
    override fun changed() { RefreshReceiver.kick(this) }
}
