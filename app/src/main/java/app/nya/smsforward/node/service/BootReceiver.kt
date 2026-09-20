package app.nya.smsforward.node.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the send channel back after a reboot or an app update, when sending is switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NodeService.sync(context)
        }
    }
}
