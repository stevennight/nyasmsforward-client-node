package app.nya.smsforward.node.sms

import android.Manifest
import android.content.Context
import android.content.ContentUris
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

    override fun delete(request: DeleteSms): DeleteOutcome {
        if (!canRead()) return DeleteOutcome.DENIED
        val uri = if (request.direction == "out") Telephony.Sms.Sent.CONTENT_URI else Telephony.Sms.Inbox.CONTENT_URI
        val start = request.deviceTime - MATCH_WINDOW_MS
        val end = request.deviceTime + MATCH_WINDOW_MS
        return try {
            // Delete one row only. A provider may contain two identical verification messages, and a broad resolver
            // delete would remove both. Compare normalized addresses because providers vary between +86 and local form.
            val selection = "body = ? AND (date BETWEEN ? AND ? OR date_sent BETWEEN ? AND ?)"
            val args = arrayOf(request.body, start.toString(), end.toString(), start.toString(), end.toString())
            val candidate = app.contentResolver.query(uri, arrayOf("_id", "address", "date", "date_sent"), selection, args, null)?.use { c ->
                var best: Pair<Long, Long>? = null
                val requestedPeer = PeerKey.normalize(request.peer)
                while (c.moveToNext()) {
                    if (PeerKey.normalize(c.getString(1).orEmpty()) != requestedPeer) continue
                    val sent = c.getLong(3)
                    val time = if (request.direction == "in" && sent > 0) sent else c.getLong(2)
                    val distance = kotlin.math.abs(time - request.deviceTime)
                    if (best == null || distance < best.second) best = c.getLong(0) to distance
                }
                best?.first
            }
            if (candidate == null) DeleteOutcome.NOT_FOUND
            else if (app.contentResolver.delete(ContentUris.withAppendedId(uri, candidate), null, null) > 0) DeleteOutcome.DELETED
            else DeleteOutcome.NOT_FOUND
        } catch (_: SecurityException) {
            DeleteOutcome.DENIED
        } catch (_: IllegalArgumentException) {
            DeleteOutcome.FAILED
        }
    }

    private fun read(uri: Uri, selection: String, args: Array<String>, order: String, useSentTime: Boolean): List<SystemSms> {
        val slots = sims().associateBy { it.subscriptionId }
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
                simSlot = sub?.let { slots[it]?.slot },
                cardNumber = sub?.let { slots[it]?.number },
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

    private companion object { const val MATCH_WINDOW_MS = 2 * 60 * 1000L }
}
