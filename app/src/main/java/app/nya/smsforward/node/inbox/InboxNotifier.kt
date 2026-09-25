package app.nya.smsforward.node.inbox

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
import app.nya.smsforward.node.sms.SmsInsight
import app.nya.smsforward.node.sms.VerificationCode

/** New-message notifications of the full edition, with "复制验证码" when the message carries a code. */
object InboxNotifier {
    private const val CHANNEL = "messages"

    fun newMessage(context: Context, threadId: Long, address: String, body: String) {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "短信", NotificationManager.IMPORTANCE_HIGH))
        val id = notificationId(threadId, address)
        val code = VerificationCode.find(body)
        val insight = if (code == null) SmsInsight.find(body) else null
        val sender = ContactNames.lookup(context, address) ?: senderBrand(body) ?: address
        val title = when {
            code != null -> "验证码 $code · $sender"
            insight is SmsInsight.Parcel -> "取件码 ${insight.code} · $sender"
            insight is SmsInsight.Bank -> "${if (insight.income) "收入" else "支出"} ${insight.amount} 元 · $sender"
            else -> sender
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openThread(context, threadId, address))
            .setAutoCancel(true)
        if (code != null) {
            builder.addAction(0, "复制验证码", broadcast(context, InboxActionReceiver.ACTION_COPY, id, threadId) { putExtra(EXTRA_CODE, code) })
        } else if (insight is SmsInsight.Parcel) {
            builder.addAction(0, "复制取件码", broadcast(context, InboxActionReceiver.ACTION_COPY, id, threadId) {
                putExtra(EXTRA_CODE, insight.code)
                putExtra(EXTRA_LABEL, "取件码")
            })
        }
        builder.addAction(0, "标为已读", broadcast(context, InboxActionReceiver.ACTION_READ, id, threadId) {})
        manager.notify(id, builder.build())
    }

    fun cancel(context: Context, threadId: Long, address: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(threadId, address))
    }

    fun openThread(context: Context, threadId: Long, address: String): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId(threadId, address),
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("nyasms://thread/$threadId"))
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_ADDRESS, address)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(context: Context, action: String, id: Int, threadId: Long, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, InboxActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("nyasms://notification/$id/$action"))
            .putExtra(EXTRA_NOTIFICATION, id)
            .putExtra(EXTRA_THREAD, threadId)
            .apply(extras)
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // Offset keeps these clear of the app's own fixed notification ids (1, 2).
    private fun notificationId(threadId: Long, address: String): Int =
        1000 + ((if (threadId > 0) threadId.hashCode() else address.hashCode()) and 0x3fffffff)

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    const val EXTRA_CODE = "code"
    const val EXTRA_LABEL = "label"
    const val EXTRA_NOTIFICATION = "notification"
    const val EXTRA_THREAD = "thread"
}

/** The notification buttons. Not exported: only our own PendingIntents reach it. */
class InboxActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(InboxNotifier.EXTRA_NOTIFICATION, 0)
        val threadId = intent.getLongExtra(InboxNotifier.EXTRA_THREAD, 0)
        when (intent.action) {
            ACTION_COPY -> {
                val code = intent.getStringExtra(InboxNotifier.EXTRA_CODE) ?: return
                val label = intent.getStringExtra(InboxNotifier.EXTRA_LABEL) ?: "验证码"
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, code))
                // Android 13+ shows its own "copied" confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "${label}已复制", Toast.LENGTH_SHORT).show()
            }
            ACTION_READ -> if (threadId > 0) {
                val pending = goAsync()
                Thread {
                    try {
                        SmsStore(context).markThreadRead(threadId)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    companion object {
        const val ACTION_COPY = "app.nya.smsforward.node.COPY_CODE"
        const val ACTION_READ = "app.nya.smsforward.node.MARK_READ"
    }
}
