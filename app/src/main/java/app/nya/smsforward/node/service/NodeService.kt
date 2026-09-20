package app.nya.smsforward.node.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.Notifier
import app.nya.smsforward.node.node.channelWanted

/**
 * Keeps the send channel alive. It runs only while sending is switched on (policy other than "off") and the phone is
 * paired: a phone that only receives and reports SMS needs no permanent process at all (docs/开发计划.md §2.1).
 */
class NodeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = NodeRuntime.get(this)
        // A foreground service must post its notification within seconds of being started, even if it is about to stop.
        val notification = Notifier.channelNotification(this, runtime.settings.sendPolicy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.ID_CHANNEL, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_CHANNEL, notification)
        }
        if (!runtime.settings.channelWanted(runtime.tokens)) {
            stopSelf()
            return START_NOT_STICKY
        }
        runtime.channel.start(onStopped = { stopSelf() })
        return START_STICKY
    }

    override fun onDestroy() {
        NodeRuntime.get(this).channel.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NodeService"

        /** Starts or stops the service to match the settings. Safe to call whenever something relevant changed. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val runtime = NodeRuntime.get(app)
            val intent = Intent(app, NodeService::class.java)
            if (runtime.settings.channelWanted(runtime.tokens)) {
                try {
                    ContextCompat.startForegroundService(app, intent)
                } catch (e: Exception) {
                    // Android refuses to start a foreground service from the background on some versions (e.g. from a
                    // broadcast). It is started the next time the app is opened or the phone boots.
                    Log.w(TAG, "could not start the send channel now", e)
                }
            } else {
                app.stopService(intent)
            }
        }
    }
}
