package app.nya.smsforward.node.inbox

import android.provider.Telephony
import app.nya.smsforward.node.sms.PeerKey
import app.nya.smsforward.node.sms.SmsInsight
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

    /** A bank movement or parcel pickup code, for messages without a verification code. */
    val insight: SmsInsight? by lazy { if (incoming && code == null) SmsInsight.find(body) else null }
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

/** The chips above the conversation list; each looks at a conversation's newest message. */
enum class InboxFilter(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    CODE("验证码"),
    BANK("银行"),
    PARCEL("快递");

    fun matches(c: ConversationSummary): Boolean = when (this) {
        ALL -> true
        UNREAD -> c.unread > 0
        CODE -> c.last.code != null
        BANK -> c.last.insight is SmsInsight.Bank
        PARCEL -> c.last.insight is SmsInsight.Parcel
    }
}

/**
 * Search: the messages whose text or number contains [query] (or whose contact name does, via [nameOf]), grouped like the
 * list, so each hit conversation shows its newest matching message.
 */
fun searchConversations(rows: List<SmsRow>, query: String, nameOf: (String) -> String? = { null }): List<ConversationSummary> {
    val q = query.trim()
    if (q.isEmpty()) return Conversations.group(rows)
    val digits = q.filter { it.isDigit() }
    val names = HashMap<String, Boolean>()
    return Conversations.group(
        rows.filter { row ->
            row.body.contains(q, ignoreCase = true) ||
                row.address.contains(q, ignoreCase = true) ||
                (digits.length >= 3 && digits == q && PeerKey.normalize(row.address).contains(digits)) ||
                names.getOrPut(row.address) { nameOf(row.address)?.contains(q, ignoreCase = true) == true }
        },
    )
}
