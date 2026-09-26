package app.nya.smsforward.node.inbox

import android.provider.Telephony
import app.nya.smsforward.node.data.OutboxState
import app.nya.smsforward.node.data.ReportRecord
import app.nya.smsforward.node.sms.PeerKey
import kotlin.math.abs

/**
 * Whether the platform has each message of the phone's inbox: the report queue ([ReportRecord]s) matched to the SMS
 * database by number, text and time. The queue knows nothing of provider row ids, and the two times differ a little
 * (the queue keeps the network timestamp, the inbox row the moment it was stored), hence [WINDOW_MS].
 *
 * A message with no match simply has no state: it arrived before pairing, is older than the queue keeps finished rows,
 * or was sent while "同步发出的短信" was off.
 */
class ReportStates(records: List<ReportRecord>) {
    private val byMessage = records.groupBy { Key(PeerKey.normalize(it.peer), it.direction, it.body) }
    private val pendingByPeer = records.filter { it.state == OutboxState.PENDING }.groupingBy { PeerKey.normalize(it.peer) }.eachCount()
    private val refusedByPeer = records.filter { it.state == OutboxState.DEAD }.groupingBy { PeerKey.normalize(it.peer) }.eachCount()

    private data class Key(val peer: String, val direction: String, val body: String)

    fun of(row: SmsRow): ReportRecord? {
        val direction = when (row.type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> "in"
            Telephony.Sms.MESSAGE_TYPE_SENT -> "out"
            else -> return null
        }
        val candidates = byMessage[Key(PeerKey.normalize(row.address), direction, row.body)] ?: return null
        val best = candidates.minBy { distance(it.deviceTime, row) }
        return best.takeIf { distance(it.deviceTime, row) <= WINDOW_MS }
    }

    /** Messages with [address] that still wait to be reported. */
    fun pending(address: String): Int = pendingByPeer[PeerKey.normalize(address)] ?: 0

    /** Messages with [address] the server refused for good. */
    fun refused(address: String): Int = refusedByPeer[PeerKey.normalize(address)] ?: 0

    private fun distance(time: Long, row: SmsRow): Long =
        minOf(abs(time - row.date), if (row.dateSent > 0) abs(time - row.dateSent) else Long.MAX_VALUE)

    companion object {
        const val WINDOW_MS = 10 * 60 * 1000L
        val EMPTY = ReportStates(emptyList())
    }
}
