package app.nya.smsforward.node.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import app.nya.smsforward.node.net.SimInfo

/** [SmsBox] on the system SMS provider. Every read needs READ_SMS; without it the box simply looks empty. */
class AndroidSmsBox(context: Context, private val sims: () -> List<SimInfo>) : SmsBox {
    private val app = context.applicationContext

    fun canRead(): Boolean = app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override fun maxSentId(): Long =
        query(Telephony.Sms.Sent.CONTENT_URI, arrayOf("_id"), null, null, "_id DESC LIMIT 1") { it.getLong(0) }.firstOrNull() ?: 0L

    override fun sentAfter(afterId: Long, limit: Int): List<SystemSms> =
        read(Telephony.Sms.Sent.CONTENT_URI, "_id > ?", arrayOf(afterId.toString()), "_id ASC LIMIT $limit", useSentTime = false)

    override fun inboxSince(sinceMillis: Long, limit: Int): List<SystemSms> =
        read(Telephony.Sms.Inbox.CONTENT_URI, "date >= ?", arrayOf(sinceMillis.toString()), "date ASC LIMIT $limit", useSentTime = true)

    override fun sentSince(sinceMillis: Long, limit: Int): List<SystemSms> =
        read(Telephony.Sms.Sent.CONTENT_URI, "date >= ?", arrayOf(sinceMillis.toString()), "date ASC LIMIT $limit", useSentTime = false)

    private fun read(uri: Uri, selection: String, args: Array<String>, order: String, useSentTime: Boolean): List<SystemSms> {
        val slots = sims().associate { it.subscriptionId to it.slot }
        return query(uri, arrayOf("_id", "address", "body", "date", "date_sent", "sub_id"), selection, args, order) { c ->
            val date = c.getLong(3)
            val dateSent = c.getLong(4)
            val sub = if (c.isNull(5)) null else c.getInt(5)
            SystemSms(
                id = c.getLong(0),
                address = c.getString(1).orEmpty(),
                body = c.getString(2).orEmpty(),
                // A received message is stamped by the network (date_sent); that is the time the live receiver reported, so
                // using it keeps history from duplicating what was already reported.
                time = if (useSentTime && dateSent > 0) dateSent else date,
                simSlot = sub?.let { slots[it] },
            )
        }.filter { it.address.isNotBlank() && it.body.isNotEmpty() }
    }

    private fun <T> query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String, map: (Cursor) -> T): List<T> {
        if (!canRead()) return emptyList()
        return try {
            app.contentResolver.query(uri, projection, selection, args, order)?.use { c ->
                val out = ArrayList<T>(c.count)
                while (c.moveToNext()) out += map(c)
                out
            }.orEmpty()
        } catch (e: SecurityException) {
            emptyList() // permission revoked between the check and the query
        } catch (e: IllegalArgumentException) {
            emptyList() // a ROM whose provider lacks a column (date_sent / sub_id): better nothing than a crash
        }
    }
}
