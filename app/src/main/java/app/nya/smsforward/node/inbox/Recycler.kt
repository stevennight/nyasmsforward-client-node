package app.nya.smsforward.node.inbox

import android.content.Context
import app.nya.smsforward.node.data.SmsTrash
import app.nya.smsforward.node.data.TrashedSms

/**
 * The full edition's recycle bin on top of the system SMS database: deleting copies a message into [SmsTrash] and then
 * removes it from the provider; restoring writes it back. Only works while this app is the default SMS app.
 */
class Recycler(context: Context, private val trash: SmsTrash, private val now: () -> Long = System::currentTimeMillis) {
    private val store = SmsStore(context)

    /** Moves one message to the recycle bin. False when it is gone already or the platform refused the delete. */
    fun recycle(id: Long, origin: String): Boolean {
        val row = store.row(id) ?: return false
        val entry = trash.add(
            TrashedSms(
                address = row.address, body = row.body, date = row.date, dateSent = store.dateSent(id), type = row.type,
                read = row.read, subId = row.subId, origin = origin, deletedAt = now(),
            ),
        )
        if (store.deleteMessage(id) > 0) return true
        trash.remove(entry) // the platform kept it, so the bin must not show a copy of a message that is still there
        return false
    }

    fun recycleThread(threadId: Long, origin: String): Int = store.thread(threadId, limit = 100_000).count { recycle(it.id, origin) }

    fun restore(id: Long): Boolean {
        val sms = trash.find(id) ?: return false
        store.insertRestored(sms.address, sms.body, sms.date, sms.dateSent, sms.type, sms.read, sms.subId) ?: return false
        return trash.remove(id)
    }

    fun list(): List<TrashedSms> {
        trash.purge(now())
        return trash.list()
    }

    fun deleteForever(id: Long) = trash.remove(id)

    fun empty() = trash.clear()

    fun purge() = trash.purge(now())

    companion object {
        const val ORIGIN_APP = "app"
        const val ORIGIN_PLATFORM = "platform"
    }
}
