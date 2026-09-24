package app.nya.smsforward.node.net

import app.nya.smsforward.node.send.SendReceipt
import app.nya.smsforward.node.send.SendTask
import app.nya.smsforward.node.send.TaskMode
import app.nya.smsforward.node.sms.DeleteSms
import app.nya.smsforward.node.sms.DeleteReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** phone -> server, first frame of every connection and then every minute (docs/协议.md §6.2). */
@Serializable
data class HelloFrame(
    val appVersion: String,
    val battery: Int? = null,
    /** off | reply | any: this phone's own setting, the one that is actually enforced. */
    val sendPolicy: String,
    val syncSent: Boolean = false,
    val backfillDays: Int = 0,
    val sims: List<SimInfo> = emptyList(),
    val type: String = "hello",
)

@Serializable
private data class SendResultFrame(
    val taskId: String,
    val status: String,
    val error: String? = null,
    val type: String = "send_result",
)

@Serializable
private data class SendSmsFrame(
    val taskId: String? = null,
    val mode: String? = null,
    val cardNumber: String? = null,
    val simSlot: Int? = null,
    val to: String? = null,
    val body: String? = null,
    val expiresAt: Long? = null,
    val type: String? = null,
)

@Serializable
private data class DeleteSmsFrame(
    val messageId: Long? = null,
    val direction: String? = null,
    val peer: String? = null,
    val body: String? = null,
    val deviceTime: Long? = null,
    val simSlot: Int? = null,
    val cardNumber: String? = null,
    val type: String? = null,
)

@Serializable
private data class DeleteResultFrame(
    val messageId: Long,
    val status: String,
    val error: String? = null,
    val type: String = "delete_result",
)

/** What the server may send us. Frames of an unknown type or with missing fields are ignored, never fatal. */
sealed interface ServerFrame {
    data class SendSms(val task: SendTask) : ServerFrame
    data class DeleteSms(val request: app.nya.smsforward.node.sms.DeleteSms) : ServerFrame
    data object Unknown : ServerFrame
}

object Frames {
    // Unknown fields must be ignored: a newer server may send more (docs/协议.md §1).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    fun hello(frame: HelloFrame): String = json.encodeToString(HelloFrame.serializer(), frame)

    fun result(receipt: SendReceipt): String =
        json.encodeToString(SendResultFrame.serializer(), SendResultFrame(receipt.taskId, receipt.status.wire, receipt.error))

    fun deleteResult(request: DeleteSms, report: DeleteReport): String =
        json.encodeToString(DeleteResultFrame.serializer(), DeleteResultFrame(request.messageId, report.outcome.wire, report.reason))

    fun parse(text: String): ServerFrame {
		val kind = runCatching { json.decodeFromString(DeleteSmsFrame.serializer(), text) }.getOrNull()
		if (kind?.type == "delete_sms") {
			val id = kind.messageId ?: return ServerFrame.Unknown
			val direction = kind.direction ?: return ServerFrame.Unknown
			val peer = kind.peer?.takeIf { it.isNotBlank() } ?: return ServerFrame.Unknown
			val body = kind.body?.takeIf { it.isNotEmpty() } ?: return ServerFrame.Unknown
			val time = kind.deviceTime ?: return ServerFrame.Unknown
			if (id <= 0 || direction !in listOf("in", "out")) return ServerFrame.Unknown
			return ServerFrame.DeleteSms(app.nya.smsforward.node.sms.DeleteSms(id, direction, peer, body, time, kind.simSlot, kind.cardNumber))
		}
        val f = try {
            json.decodeFromString(SendSmsFrame.serializer(), text)
        } catch (e: Exception) {
            return ServerFrame.Unknown
        }
        if (f.type != "send_sms") return ServerFrame.Unknown
        val mode = TaskMode.fromWire(f.mode)
        val id = f.taskId
        val to = f.to
        val body = f.body
        val expires = f.expiresAt
        if (id.isNullOrEmpty() || mode == null || to.isNullOrBlank() || body.isNullOrEmpty() || expires == null) return ServerFrame.Unknown
        return ServerFrame.SendSms(SendTask(id, mode, f.simSlot, to, body, expires, f.cardNumber))
    }
}
