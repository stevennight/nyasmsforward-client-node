package app.nya.smsforward.node.view

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import app.nya.smsforward.node.MainActivity
import app.nya.smsforward.node.R
import app.nya.smsforward.node.inbox.ContactNames
import app.nya.smsforward.node.inbox.senderBrand
import app.nya.smsforward.node.node.NodeRuntime
import kotlinx.coroutines.launch

/**
 * New-message notifications of the 「平台」 tab: one per conversation, newest message shown, with "复制验证码" and
 * "标为已读". Tapping opens that conversation.
 */
class PlatformNotifier(context: Context) : PlatformAlerts {
    private val app = context.applicationContext

    /** Message ids each shown notification stands for, so "标为已读" marks exactly those. */
    private val shownIds = HashMap<String, MutableSet<Long>>()

    override fun show(message: RemoteMessage, state: ViewState) {
        if (!canPost()) return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "平台短信", NotificationManager.IMPORTANCE_HIGH))
        val key = message.conversationKey
        val id = notificationId(key)
        val ids = synchronized(shownIds) { shownIds.getOrPut(key) { mutableSetOf() }.apply { add(message.id) }.toLongArray() }
        val sender = ContactNames.lookup(app, message.peer) ?: senderBrand(message.body) ?: message.peer
        val title = message.code?.let { "验证码 $it · $sender" } ?: sender
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message.body)
            .setSubText(sourceLabel(message.deviceId, message.cardNumber, state))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openThread(message, id))
            .setAutoCancel(true)
        message.code?.let { code ->
            builder.addAction(0, "复制验证码", broadcast(PlatformActionReceiver.ACTION_COPY, id) { putExtra(EXTRA_CODE, code) })
        }
        builder.addAction(0, "标为已读", broadcast(PlatformActionReceiver.ACTION_READ, id) { putExtra(EXTRA_IDS, ids) })
        manager.notify(id, builder.build())
    }

    override fun cancel(conversationKeys: Collection<String>) {
        val manager = app.getSystemService(NotificationManager::class.java)
        for (key in conversationKeys) {
            synchronized(shownIds) { shownIds.remove(key) }
            manager.cancel(notificationId(key))
        }
    }

    private fun openThread(m: RemoteMessage, id: Int): PendingIntent = PendingIntent.getActivity(
        app,
        id,
        Intent(app, MainActivity::class.java)
            .setData(Uri.parse("nyasms://platform/$id"))
            .putExtra(MainActivity.EXTRA_PLATFORM_DEVICE, m.deviceId)
            .putExtra(MainActivity.EXTRA_PLATFORM_CARD, m.cardNumber)
            .putExtra(MainActivity.EXTRA_PLATFORM_PEER, m.peer)
            .putExtra(MainActivity.EXTRA_PLATFORM_PEER_KEY, m.peerKey)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(action: String, id: Int, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(app, PlatformActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("nyasms://platform-notification/$id/$action"))
            .putExtra(EXTRA_NOTIFICATION, id)
            .apply(extras)
        return PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL = "platform_messages"
        const val EXTRA_CODE = "code"
        const val EXTRA_IDS = "ids"
        const val EXTRA_NOTIFICATION = "notification"

        // Their own range, clear of the app's fixed ids and of the 「短信」 inbox's (1000 + …).
        fun notificationId(conversationKey: String): Int = 0x40000000 or (conversationKey.hashCode() and 0x3fffffff)
    }
}

/** The 「平台」 notification buttons. Not exported: only our own PendingIntents reach it. */
class PlatformActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PlatformNotifier.EXTRA_NOTIFICATION, 0)
        when (intent.action) {
            ACTION_COPY -> {
                val code = intent.getStringExtra(PlatformNotifier.EXTRA_CODE) ?: return
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("验证码", code))
                // Android 13+ shows its own "copied" confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "验证码已复制", Toast.LENGTH_SHORT).show()
            }
            ACTION_READ -> {
                val ids = intent.getLongArrayExtra(PlatformNotifier.EXTRA_IDS)?.toList().orEmpty()
                if (ids.isNotEmpty()) {
                    val pending = goAsync()
                    val runtime = NodeRuntime.get(context)
                    runtime.scope.launch {
                        try {
                            runtime.view.markRead(ids)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    companion object {
        const val ACTION_COPY = "app.nya.smsforward.node.PLATFORM_COPY_CODE"
        const val ACTION_READ = "app.nya.smsforward.node.PLATFORM_MARK_READ"
    }
}
