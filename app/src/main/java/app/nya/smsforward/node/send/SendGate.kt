package app.nya.smsforward.node.send

import app.nya.smsforward.node.data.Outbox
import app.nya.smsforward.node.data.SendLedger
import app.nya.smsforward.node.node.NodeSettings
import app.nya.smsforward.node.policy.SendPolicy
import app.nya.smsforward.node.sms.PeerKey

sealed interface GateDecision {
    data object Allow : GateDecision

    /** @property error the code reported back in `send_result` (see [SendError]) */
    data class Reject(val error: String) : GateDecision
}

/**
 * The phone's last line of defence (docs/协议.md §7): decides whether a send task from the server may go out.
 *
 * Everything here is judged from data that lives on the phone. The server can ask for anything, but "reply only" means
 * the recipient must be a number that messaged this phone in the last [RECENT_DAYS] days according to the phone's own
 * records, and the hourly limit counts tasks in the phone's own ledger, so a compromised server cannot use this phone to
 * message arbitrary people or to spam.
 */
class SendGate(
    private val settings: NodeSettings,
    private val outbox: Outbox,
    private val ledger: SendLedger,
    private val clock: () -> Long,
) {
    fun check(task: SendTask): GateDecision {
        val now = clock()
        if (now >= task.expiresAt) return GateDecision.Reject(SendError.EXPIRED)

        when (settings.sendPolicy) {
            SendPolicy.OFF -> return GateDecision.Reject(SendError.POLICY_DENIED)
            SendPolicy.REPLY -> {
                // "Reply only" is about who the recipient is, not about what the server calls the task: the number
                // must have messaged us recently, and it must be a real number (never a named sender).
                if (task.mode != TaskMode.REPLY) return GateDecision.Reject(SendError.POLICY_DENIED)
                if (!PeerKey.isReplyable(task.to)) return GateDecision.Reject(SendError.RECIPIENT_NOT_RECENT)
                val recent = outbox.recentPeers(now - RECENT_DAYS * DAY_MS)
                if (PeerKey.normalize(task.to) !in recent) return GateDecision.Reject(SendError.RECIPIENT_NOT_RECENT)
            }
            SendPolicy.ANY -> {
                if (!PeerKey.isReplyable(task.to)) return GateDecision.Reject(SendError.POLICY_DENIED) // only numbers, ever
            }
        }

        val recipientKey = PeerKey.normalize(task.to)
        if (settings.allowedRecipients.isNotEmpty() && recipientKey !in settings.allowedRecipients) {
            return GateDecision.Reject(SendError.RECIPIENT_NOT_ALLOWED)
        }

        if (ledger.startedSince(now - HOUR_MS) >= settings.sendLimitPerHour) return GateDecision.Reject(SendError.RATE_LIMITED)
        return GateDecision.Allow
    }

    companion object {
        const val RECENT_DAYS = 7L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
    }
}
