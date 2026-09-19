package app.nya.smsforward.node.node

import app.nya.smsforward.node.data.NewOutboxItem
import app.nya.smsforward.node.data.Outbox
import app.nya.smsforward.node.sms.DedupeKey
import app.nya.smsforward.node.sms.PeerKey
import app.nya.smsforward.node.sms.SmsAssembler
import app.nya.smsforward.node.sms.SmsPart

/**
 * What happens the moment an SMS arrives: join the parts, fingerprint it, and put it in the local queue.
 *
 * Deliberately no network here. The broadcast receiver has only a few seconds, and an SMS that cannot be reported right
 * now must still be kept, so the queue is the single hand-off point to the uploader.
 */
class IncomingHandler(
    private val outbox: Outbox,
    private val settings: NodeSettings,
    private val clock: () -> Long,
) {
    /**
     * @param simSlot 1-based SIM slot, or null when it cannot be determined
     * @return how many new messages were queued (0 when the phone is not set up, or the broadcast was a repeat)
     */
    fun onReceived(parts: List<SmsPart>, simSlot: Int?): Int {
        // Before the first pairing there is no device identity to build dedupe keys from, and the user has not asked us
        // to collect anything yet. After a token is revoked the id is kept, so receiving continues (docs/协议.md §2.2).
        val deviceId = settings.deviceId ?: return 0
        val now = clock()

        var queued = 0
        for (sms in SmsAssembler.assemble(parts)) {
            val deviceTime = plausibleTime(sms.timestampMillis, now)
            val key = DedupeKey.compute(deviceId, simSlot ?: 0, sms.peer, deviceTime, sms.body)
            if (outbox.enqueue(NewOutboxItem(key, sms.peer, sms.body, simSlot, deviceTime), now)) {
                queued++
                // Only real numbers can ever be replied to; alphanumeric senders ("示例银行") never qualify.
                if (PeerKey.isReplyable(sms.peer)) outbox.touchPeer(PeerKey.normalize(sms.peer), now)
            }
        }
        return queued
    }

    /** Some carriers send bogus timestamps; the server refuses times more than a day ahead, so fall back to "now". */
    private fun plausibleTime(timestamp: Long, now: Long): Long =
        if (timestamp <= 0 || timestamp > now + MAX_FUTURE_MS) now else timestamp

    private companion object {
        const val MAX_FUTURE_MS = 5 * 60 * 1000L
    }
}
