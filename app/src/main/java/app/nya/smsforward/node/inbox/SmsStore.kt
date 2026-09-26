package app.nya.smsforward.node.inbox

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log

/**
 * The full edition's access to the platform SMS database.
 *
 * Reading needs READ_SMS. Every write (inserting what arrived or was sent, marking read, deleting) only takes effect
 * while this app is the default SMS app: for anyone else the provider silently ignores it. Being the default app also
 * means nobody else stores our messages, so [insertIncoming] and [insertOutgoing] are not optional then.
 */
class SmsStore(context: Context) {
    private val app = context.applicationContext
    private val resolver get() = app.contentResolver

    fun canRead(): Boolean = app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun isDefaultApp(): Boolean = isDefaultSmsApp(app)

    /** The newest [limit] messages, enough to build the conversation list. */
    fun recent(limit: Int = 3000): List<SmsRow> = query(null, null, "date DESC LIMIT $limit")

    /** One conversation, oldest first. */
    fun thread(threadId: Long, limit: Int = 1000): List<SmsRow> =
        query("thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT $limit").reversed()

    fun row(id: Long): SmsRow? = query("_id = ?", arrayOf(id.toString()), "_id").firstOrNull()

    /** The date_sent column, which the inbox list does not need (0 when unknown). */
    fun dateSent(id: Long): Long = runCatching {
        resolver.query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), arrayOf(Telephony.Sms.DATE_SENT), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }.getOrNull() ?: 0L

    /**
     * Puts a message from the recycle bin (or the block list) back with its original time and state. Returns the new row
     * only once it can be read back: when this app is not the default SMS app the provider ignores the insert but still
     * hands out a Uri, and trusting that Uri made the caller drop its only copy of the message.
     */
    fun insertRestored(address: String, body: String, date: Long, dateSent: Long, type: Int, read: Boolean, subId: Int?): SmsRow? {
        if (!isDefaultApp()) return null
        val uri = insert(
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.DATE_SENT, dateSent)
                put(Telephony.Sms.TYPE, type)
                put(Telephony.Sms.READ, if (read) 1 else 0)
                put(Telephony.Sms.SEEN, 1)
                if (subId != null) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            },
        ) ?: return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
        if (id <= 0) return null
        return row(id)?.takeIf { it.body == body }
    }

    /** The provider's thread for a number; creates it when needed (only the default app can create one). */
    fun threadIdFor(address: String): Long =
        runCatching { Telephony.Threads.getOrCreateThreadId(app, address) }.getOrDefault(0L)

    fun insertIncoming(address: String, body: String, date: Long, dateSent: Long, subId: Int?): Uri? = insert(
        ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, dateSent)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            if (subId != null) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        },
    )

    fun insertOutgoing(address: String, body: String, subId: Int?, type: Int = Telephony.Sms.MESSAGE_TYPE_OUTBOX): Uri? = insert(
        ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, type)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            if (subId != null) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        },
    )

    fun setType(uri: Uri, type: Int) {
        write { resolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null) }
    }

    /** Outbox → sent, but never over a "failed" an earlier part already reported. */
    fun markSent(uri: Uri) {
        write {
            resolver.update(
                uri,
                ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT) },
                "type = ?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX.toString()),
            )
        }
    }

    fun markThreadRead(threadId: Long) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        write { resolver.update(Telephony.Sms.CONTENT_URI, values, "thread_id = ? AND read = 0", arrayOf(threadId.toString())) }
    }

    /** "标为未读": the newest incoming message of the conversation becomes unread again, like other SMS apps do it. */
    fun markThreadUnread(threadId: Long): Boolean {
        val newest = query("thread_id = ? AND type = ?", arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString()), "date DESC LIMIT 1")
            .firstOrNull() ?: return false
        val values = ContentValues().apply { put(Telephony.Sms.READ, 0) }
        return (write { resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, newest.id), values, null, null) } ?: 0) > 0
    }

    /** "全部标为已读". Returns how many rows changed (0 when not the default app). */
    fun markAllRead(): Int {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        return write { resolver.update(Telephony.Sms.CONTENT_URI, values, "read = 0", null) } ?: 0
    }

    /** Returns how many rows went; 0 when the platform refused (not the default app). */
    fun deleteMessage(id: Long): Int = write { resolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null) } ?: 0

    private fun insert(values: ContentValues): Uri? = write { resolver.insert(Telephony.Sms.CONTENT_URI, values) }

    private fun <T> write(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "SMS provider write refused", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "SMS provider write failed", e)
        null
    }

    private fun query(selection: String?, args: Array<String>?, order: String): List<SmsRow> {
        if (!canRead()) return emptyList()
        return try {
            resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, selection, args, order)?.use(::readRows).orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            // A ROM whose provider lacks sub_id: retry without it rather than show nothing.
            resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION.copyOf(PROJECTION.size - 1).requireNoNulls(), selection, args, order)
                ?.use(::readRows).orEmpty()
        }
    }

    private fun readRows(c: Cursor): List<SmsRow> {
        val out = ArrayList<SmsRow>(c.count)
        val hasSub = c.columnCount > 7
        while (c.moveToNext()) {
            out += SmsRow(
                id = c.getLong(0),
                threadId = c.getLong(1),
                address = c.getString(2).orEmpty(),
                body = c.getString(3).orEmpty(),
                date = c.getLong(4),
                type = c.getInt(5),
                read = c.getInt(6) != 0,
                subId = if (hasSub && !c.isNull(7)) c.getInt(7) else null,
            )
        }
        return out
    }

    companion object {
        private const val TAG = "SmsStore"
        private val PROJECTION = arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "sub_id")

        fun isDefaultSmsApp(context: Context): Boolean = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

        /** The system dialog that makes this app the default SMS app (Android 10+ uses the role API). */
        fun requestDefaultIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                @Suppress("DEPRECATION")
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
    }
}
