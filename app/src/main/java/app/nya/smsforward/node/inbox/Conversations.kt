package app.nya.smsforward.node.inbox

import android.provider.Telephony
import app.nya.smsforward.node.sms.PeerKey
import app.nya.smsforward.node.sms.VerificationCode

/** One row of the platform SMS table, as the full edition's inbox shows it. */
data class SmsRow(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    /** Epoch millis the provider stored the row (received / sent time). */
    val date: Long,
    /** Telephony.Sms.MESSAGE_TYPE_*: 1 inbox, 2 sent, 4 outbox, 5 failed, 6 queued. */
    val type: Int,
    val read: Boolean,
    val subId: Int? = null,
) {
    val incoming: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_INBOX
    val failed: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_FAILED
    val sending: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED
    val code: String? by lazy { if (incoming) VerificationCode.find(body) else null }
}

/** A conversation in the list: the newest message, and how many incoming ones are unread. */
data class ConversationSummary(val threadId: Long, val address: String, val last: SmsRow, val unread: Int)

object Conversations {
    /**
     * Groups rows into conversations, newest first. The provider's thread id is the key; rows without one (some ROMs
     * leave it 0) are grouped by the normalized number instead, so "+86 138…" and "138…" stay together.
     */
    fun group(rows: List<SmsRow>): List<ConversationSummary> {
        val groups = LinkedHashMap<String, MutableList<SmsRow>>()
        for (row in rows.sortedByDescending { it.date }) {
            if (row.type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
            val key = if (row.threadId > 0) "t${row.threadId}" else "p${PeerKey.normalize(row.address)}"
            groups.getOrPut(key) { mutableListOf() } += row
        }
        return groups.values.map { list ->
            val last = list.first()
            ConversationSummary(
                threadId = last.threadId,
                address = list.firstOrNull { it.address.isNotBlank() }?.address ?: last.address,
                last = last,
                unread = list.count { it.incoming && !it.read },
            )
        }
    }
}

/** The sender name Chinese service SMS start with ("【中国移动】…" → "中国移动"), or null. Same rule as the viewer app. */
fun senderBrand(body: String): String? =
    Regex("^\\s*[【\\[]([^】\\]]{1,16})[】\\]]").find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
