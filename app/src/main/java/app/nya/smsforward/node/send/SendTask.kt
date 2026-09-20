package app.nya.smsforward.node.send

/** How a send task was created on the platform (docs/协议.md §4.1). */
enum class TaskMode(val wire: String) {
    REPLY("reply"),
    NEW("new");

    companion object {
        fun fromWire(value: String?): TaskMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One `send_sms` frame: an SMS the server wants this phone to send.
 *
 * @property simSlot 1-based SIM slot the message should leave from (for replies: the SIM the original arrived on), or
 *   null for "the phone's default SMS SIM"
 * @property expiresAt epoch millis after which the task must be dropped instead of sent
 */
data class SendTask(
    val taskId: String,
    val mode: TaskMode,
    val simSlot: Int?,
    val to: String,
    val body: String,
    val expiresAt: Long,
)

enum class ReceiptStatus(val wire: String) {
    /** Handed to the network. */
    SENT("sent"),

    /** The recipient's phone confirmed receipt (only if the carrier sends delivery reports). */
    DELIVERED("delivered"),

    /** Not sent (rejected locally) or the radio reported a failure. */
    FAILED("failed"),
}

/** A `send_result` frame. */
data class SendReceipt(val taskId: String, val status: ReceiptStatus, val error: String? = null)

/** The error codes the server understands (docs/协议.md §6.2). Anything else is stored by the server as `radio_error`. */
object SendError {
    const val POLICY_DENIED = "policy_denied"
    const val RECIPIENT_NOT_RECENT = "recipient_not_recent"
    const val RATE_LIMITED = "rate_limited"
    const val EXPIRED = "expired"
    const val NO_PERMISSION = "no_permission"
    const val SIM_UNAVAILABLE = "sim_unavailable"
    const val RADIO_ERROR = "radio_error"

    /** The SMS left this phone, but the network said the recipient did not get it. */
    const val DELIVERY_FAILED = "delivery_failed"
}
