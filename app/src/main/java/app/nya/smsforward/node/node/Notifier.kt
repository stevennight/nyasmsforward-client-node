package app.nya.smsforward.node.node

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.nya.smsforward.node.MainActivity
import app.nya.smsforward.node.R
import app.nya.smsforward.node.policy.SendPolicy

/** The notifications this app posts: "the token stopped working, pair again", and the send channel's ongoing one. */
object Notifier {
    private const val CHANNEL = "pairing"
    private const val CHANNEL_SERVICE = "send_channel"
    private const val ID_NEEDS_PAIRING = 1
    const val ID_CHANNEL = 2

    fun needsPairing(context: Context, pending: Int) {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "需要重新配对", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (pending > 0) "有 $pending 条短信还没上报，短信仍在继续接收。点这里重新配对。" else "点这里重新配对，短信不会丢。"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("短信接收端需要重新配对")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true) // the same notification is refreshed on every failed run, never re-alerted
            .setAutoCancel(true)
            .build()
        manager.notify(ID_NEEDS_PAIRING, notification)
    }

    /** The ongoing notification of the foreground service that keeps the send channel connected. */
    fun channelNotification(context: Context, policy: SendPolicy): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "下发通道", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = if (policy == SendPolicy.ANY) "回复和新发" else "回复"
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("接收端在线")
            .setContentText("平台可以让这台手机代发短信（$what）。在设置里可以随时关闭。")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun clearNeedsPairing(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_NEEDS_PAIRING)
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
