package app.nya.smsforward.node.sms

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import app.nya.smsforward.node.inbox.SmsStore
import app.nya.smsforward.node.net.SimInfo

/**
 * [SmsBox] on the system SMS provider. Every read needs READ_SMS; without it the box simply looks empty.
 *
 * [recycle] (full edition) moves a matched message to the phone's recycle bin instead of deleting it for good.
 */
class AndroidSmsBox(
    context: Context,
    private val sims: () -> List<SimInfo>,
    private val recycle: ((providerId: Long) -> Boolean)? = null,
) : SmsBox {
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

    /** Since Android 4.4 only the default SMS app may write the provider; for anyone else a delete silently removes nothing. */
    fun isDefaultSmsApp(): Boolean = SmsStore.isDefaultSmsApp(app)

    override fun delete(request: DeleteSms): DeleteReport {
        if (!canRead()) return DeleteReport(DeleteOutcome.DENIED, "no_read_permission")
        val uri = Telephony.Sms.CONTENT_URI
        val type = if (request.direction == "out") Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX
        val start = request.deviceTime - MATCH_WINDOW_MS
        val end = request.deviceTime + MATCH_WINDOW_MS
        return try {
            val slots = sims().associateBy { it.subscriptionId }
            val selection = "type = ? AND (date BETWEEN ? AND ? OR date_sent BETWEEN ? AND ?)"
            val args = arrayOf(type.toString(), start.toString(), end.toString(), start.toString(), end.toString())
            val candidates = app.contentResolver.query(
                uri,
                arrayOf("_id", "address", "body", "date", "date_sent", "sub_id"),
                selection,
                args,
                null,
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val sub = if (c.isNull(5)) null else c.getInt(5)
                        add(
                            SmsDeleteCandidate(
                                id = c.getLong(0),
                                address = c.getString(1),
                                body = c.getString(2).orEmpty(),
                                date = c.getLong(3),
                                dateSent = if (c.isNull(4)) 0 else c.getLong(4),
                                simSlot = slots[sub]?.slot,
                            ),
                        )
                    }
                }
            }.orEmpty()
            val match = SmsDeleteMatcher.find(request, candidates, MATCH_WINDOW_MS)
            if (match == null) {
                Log.w(TAG, "SMS delete no match: id=${request.messageId}, direction=${request.direction}, peer=${request.peer}, candidates=${candidates.size}")
                DeleteReport(DeleteOutcome.NOT_FOUND, if (candidates.isEmpty()) "no_candidates" else "no_match")
            } else if (recycle != null && isDefaultSmsApp() && recycle(match.id)) {
                Log.i(TAG, "SMS moved to the phone recycle bin: id=${request.messageId}, providerId=${match.id}")
                DeleteReport(DeleteOutcome.DELETED, "recycled")
            } else if (app.contentResolver.delete(ContentUris.withAppendedId(uri, match.id), null, null) > 0) {
                Log.i(TAG, "SMS delete succeeded: id=${request.messageId}, providerId=${match.id}, distance=${match.distance}, bodyRank=${match.bodyRank}")
                DeleteReport(DeleteOutcome.DELETED)
            } else if (!isDefaultSmsApp()) {
                // The row exists (we just read it) but the provider ignored the write: the platform's default-SMS-app rule.
                Log.w(TAG, "SMS delete ignored, not the default SMS app: id=${request.messageId}, providerId=${match.id}")
                DeleteReport(DeleteOutcome.DENIED, "not_default_sms_app")
            } else {
                Log.w(TAG, "SMS delete provider returned 0: id=${request.messageId}, providerId=${match.id}")
                DeleteReport(DeleteOutcome.FAILED, "provider_rejected")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SMS delete denied: id=${request.messageId}", e)
            DeleteReport(DeleteOutcome.DENIED, "security_exception")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "SMS delete provider failed: id=${request.messageId}", e)
            DeleteReport(DeleteOutcome.FAILED, "provider_error")
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

    private companion object {
        const val TAG = "NyaSmsDelete"
        const val MATCH_WINDOW_MS = 5 * 60 * 1000L
    }
}
