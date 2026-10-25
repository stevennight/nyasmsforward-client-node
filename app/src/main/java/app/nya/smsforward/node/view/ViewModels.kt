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

    /** The [RemoteConversation.key] of the thread this message is part of. */
    val conversationKey: String get() = (if (cardNumber.isNullOrEmpty()) "device:$deviceId" else "card:$cardNumber") + "|" + peerKey
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
    /** The platform's limit, what the phone itself reported (null: never), and the stricter of the two. off | reply | any. */
    val sendPolicy: String = "off",
    val phoneSendPolicy: String? = null,
    val effectivePolicy: String = "off",
) {
    /** Why sending ([reply] or a new message) through this phone is not possible, or null when it is. */
    fun blockedReason(reply: Boolean): String? {
        if (revoked) return "这台手机的令牌已吊销，需要先重新配对"
        val need = if (reply) 1 else 2
        if (policyRank(effectivePolicy) >= need) return null
        return "下发策略不允许${if (reply) "回复" else "新发"}（平台：${policyLabel(sendPolicy)}；手机：${policyLabel(phoneSendPolicy)}）"
    }

    private companion object {
        fun policyRank(p: String?) = when (p) { "any" -> 2; "reply" -> 1; else -> 0 }
        fun policyLabel(p: String?) = when (p) { "off" -> "关闭"; "reply" -> "仅回复"; "any" -> "允许新发"; null -> "手机尚未上报"; else -> p }
    }
}

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

/** A send task (docs/协议.md §4.1): a reply to a received message, or a new message, carried out by a receiver phone. */
@Serializable
data class OutboundTask(
    val taskId: String = "",
    val deviceId: String = "",
    val mode: String = "reply",
    val recipient: String = "",
    val recipientKey: String = "",
    val body: String = "",
    /** queued | dispatched | sent | delivered | failed | expired */
    val status: String = "queued",
    val error: String? = null,
    val messageId: Long? = null,
    val simSlot: Int? = null,
    val createdAt: Long = 0,
) {
    val final: Boolean get() = status == "delivered" || status == "failed" || status == "expired"

    /** One line for the task strip, in the words the web console uses. */
    val summary: String
        get() {
            val base = when (status) {
                "queued" -> "排队中（等待手机上线）"
                "dispatched" -> "已下发"
                "sent" -> "已发送"
                "delivered" -> "已送达"
                "failed" -> "失败"
                "expired" -> "已过期"
                else -> status
            }
            val reason = error?.takeIf { it.isNotEmpty() }?.let { TASK_ERRORS[it] ?: it }
            return if (reason != null && (status == "failed" || status == "expired")) "$base：$reason" else base
        }

    companion object {
        /** Why a send task failed (docs/协议.md §6.2). */
        val TASK_ERRORS = mapOf(
            "policy_denied" to "手机的下发策略不允许",
            "recipient_not_recent" to "收件人不在手机的“最近来信号码”里",
            "recipient_not_allowed" to "收件人不在手机白名单里",
            "rate_limited" to "手机端限速",
            "expired" to "任务过期，手机没有及时处理",
            "no_permission" to "手机没有授予发送短信权限",
            "sim_unavailable" to "该 SIM 卡槽当前没有卡",
            "radio_error" to "发送失败（基站 / 网络）",
            "delivery_failed" to "已发出，但运营商回报对方没有收到",
            "cancelled" to "已取消",
        )
    }
}

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

@Serializable
internal data class TaskList(val items: List<OutboundTask> = emptyList())

@Serializable
internal data class DeletedIds(val deleted: List<Long> = emptyList())

@Serializable
internal data class DeletedCount(val deleted: Int = 0)

@Serializable
internal data class QueuedIds(val queued: List<Long> = emptyList())

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
