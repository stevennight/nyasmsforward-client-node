package app.nya.smsforward.node.view

import kotlinx.serialization.Serializable

/*
 * What the server sends to a client (docs/协议.md §4.0.2, §6.1), for the 「查看」 feature. Parsing is forgiving: unknown
 * fields are ignored and a missing optional field is null, so a newer server never breaks this app.
 */

/** One SMS as the platform keeps it. [direction] is "in" or "out"; [origin] "device" (reported) or "platform" (sent by a task). */
@Serializable
data class RemoteMessage(
    val id: Long,
    val deviceId: String = "",
    val direction: String = "in",
    val origin: String = "device",
    val peer: String = "",
    val peerKey: String = "",
    val cardNumber: String? = null,
    val body: String = "",
    val simSlot: Int? = null,
    val replyTo: Long? = null,
    val deviceTime: Long = 0,
    /** The verification code the server found in the text, if any. */
    val code: String? = null,
    val readAt: Long? = null,
    val backfill: Boolean = false,
    val deletedAt: Long? = null,
) {
    val incoming: Boolean get() = direction != "out"
    val unread: Boolean get() = incoming && readAt == null
}

/** A thread with one number through one SIM card (or one phone, for messages without a known card). */
@Serializable
data class RemoteConversation(
    val deviceId: String = "",
    val cardNumber: String? = null,
    val peer: String = "",
    val peerKey: String = "",
    val last: RemoteMessage,
    val unread: Int = 0,
    /** False for alphanumeric sender ids ("示例银行"), which cannot be replied to. */
    val replyable: Boolean = true,
) {
    /** The same identity the server uses to merge a thread: the card when known, else the phone. */
    val key: String get() = (if (cardNumber.isNullOrEmpty()) "device:$deviceId" else "card:$cardNumber") + "|" + peerKey
}

@Serializable
data class RemoteSim(val slot: Int = 0, val subscriptionId: Int? = null, val label: String? = null, val number: String? = null)

/** A receiver phone, as clients see it (GET /api/v1/phones). */
@Serializable
data class RemotePhone(
    val id: String = "",
    val name: String = "",
    val sims: List<RemoteSim> = emptyList(),
    val online: Boolean = false,
    val revoked: Boolean = false,
    val battery: Int? = null,
)

/** A phone-number line (GET /api/v1/cards); [label] is the name the admin gave it. */
@Serializable
data class RemoteCard(val number: String = "", val label: String = "", val deviceId: String? = null, val simSlot: Int? = null)

/** GET /api/v1/me for a client token. */
@Serializable
data class ClientMe(
    val deviceId: String = "",
    val kind: String = "",
    val name: String = "",
    val scopes: List<String> = emptyList(),
    val serverVersion: String? = null,
)

/** What pairing or the account login hands out. */
@Serializable
data class ClientClaim(val deviceId: String, val token: String, val scopes: List<String> = emptyList())

@Serializable
data class ConversationPage(val items: List<RemoteConversation> = emptyList(), val unread: Int = 0)

@Serializable
data class MessagePage(val items: List<RemoteMessage> = emptyList(), val unread: Int = 0)

@Serializable
internal data class PhoneList(val items: List<RemotePhone> = emptyList())

@Serializable
internal data class CardList(val items: List<RemoteCard> = emptyList())

@Serializable
internal data class ChangedIds(val changed: List<Long> = emptyList())

/** The client permissions (docs/协议.md §3): read, reply, send, delete. */
object Scopes {
    const val READ = "read"
    const val REPLY = "reply"
    const val SEND = "send"
    const val DELETE = "delete"

    fun label(scope: String): String = when (scope) {
        READ -> "查看"
        REPLY -> "回复"
        SEND -> "新发"
        DELETE -> "删除"
        else -> scope
    }
}
