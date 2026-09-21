package app.nya.smsforward.node.send

import app.nya.smsforward.node.data.LedgerState
import app.nya.smsforward.node.data.SendLedger
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.sms.PeerKey

/** What the platform-specific side has to provide: the SMS radio. */
interface SmsSender {
    /** Whether SEND_SMS is granted. */
    fun canSend(): Boolean

    /** Whether SIM information may be read (READ_PHONE_STATE), which mapping a slot to a subscription needs. */
    fun canReadSims(): Boolean

    fun activeSims(): List<SimInfo>

    /**
     * Hands the message to the radio (long texts are split into parts there). Results arrive later through [SendCoordinator.onPartSent] / [SendCoordinator.onPartDelivered].
     * @throws Exception when the radio refuses right away
     */
    fun send(task: SendTask, sim: SimChoice)
}

/**
 * Turns `send_sms` frames into SMS and receipts.
 *
 * Every task goes through the same steps: is it new (the ledger says so), does the gate allow it, which SIM sends it, hand
 * it to the radio, and report `sent` / `delivered` / `failed` as the results come in. The ledger is written BEFORE the
 * radio is used, so a crash can leave a task "unknown", but never lets it be sent twice.
 */
class SendCoordinator(
    private val gate: SendGate,
    private val ledger: SendLedger,
    private val sender: SmsSender,
    private val tracker: SendStatusTracker,
    private val clock: () -> Long,
    /** Delivers a receipt to the server over the open channel. Best effort: the ledger replays what gets lost. */
    private val emit: (SendReceipt) -> Unit,
) {
    private val lock = Any()

    fun onTask(task: SendTask) {
        val receipt = synchronized(lock) { handle(task) } ?: return
        emit(receipt)
    }

    private fun handle(task: SendTask): SendReceipt? {
        val now = clock()

        ledger.find(task.taskId)?.let { known ->
            // The server offered a task we already dealt with (it does that after a reconnect until it sees our receipt).
            // Never send again; answer with what we know. A task still SENDING gets its receipt when the radio reports.
            return when (known.state) {
                LedgerState.SENDING -> null
                LedgerState.SENT -> SendReceipt(task.taskId, ReceiptStatus.SENT)
                LedgerState.DELIVERED -> SendReceipt(task.taskId, ReceiptStatus.DELIVERED)
                LedgerState.FAILED -> SendReceipt(task.taskId, ReceiptStatus.FAILED, known.error)
            }
        }

        val recipient = if (PeerKey.isReplyable(task.to)) PeerKey.normalize(task.to) else task.to
        fun reject(error: String): SendReceipt {
            ledger.reject(task.taskId, recipient, task.mode.wire, error, now)
            return SendReceipt(task.taskId, ReceiptStatus.FAILED, error)
        }

        when (val decision = gate.check(task)) {
            GateDecision.Allow -> Unit
            is GateDecision.Reject -> return reject(decision.error)
        }
        if (!sender.canSend()) return reject(SendError.NO_PERMISSION)
		val sim = when (val choice = SimResolver.resolve(task.cardNumber, task.simSlot, sender.activeSims(), sender.canReadSims())) {
            SimChoice.Unavailable -> return reject(SendError.SIM_UNAVAILABLE)
            SimChoice.NoPermission -> return reject(SendError.NO_PERMISSION)
            else -> choice
        }

        // Written first: from here on this task counts against the limit and can never be started again.
        if (!ledger.begin(task.taskId, recipient, task.mode.wire, now, BodyHash.of(task.body))) return null
        return try {
            sender.send(task, sim)
            null // the result comes back through onPartSent
        } catch (e: Exception) {
            ledger.update(task.taskId, ReceiptStatus.FAILED, SendError.RADIO_ERROR, clock())
            tracker.forget(task.taskId)
            SendReceipt(task.taskId, ReceiptStatus.FAILED, SendError.RADIO_ERROR)
        }
    }

    /** A "sent" callback from the radio for one part of a task. [error] is null on success. */
    fun onPartSent(taskId: String, index: Int, count: Int, error: String?) =
        apply(tracker.onPartSent(taskId, index, count, error))

    /** A delivery report for one part of a task. */
    fun onPartDelivered(taskId: String, index: Int, count: Int, ok: Boolean) =
        apply(tracker.onPartDelivered(taskId, index, count, ok))

    private fun apply(outcome: SendStatusTracker.Outcome?) {
        outcome ?: return
        val receipt = synchronized(lock) {
            val now = clock()
            when (outcome) {
                is SendStatusTracker.Outcome.Sent ->
                    if (ledger.update(outcome.taskId, ReceiptStatus.SENT, null, now)) SendReceipt(outcome.taskId, ReceiptStatus.SENT) else null
                is SendStatusTracker.Outcome.Delivered ->
                    if (ledger.update(outcome.taskId, ReceiptStatus.DELIVERED, null, now)) SendReceipt(outcome.taskId, ReceiptStatus.DELIVERED) else null
                is SendStatusTracker.Outcome.Failed ->
                    if (ledger.update(outcome.taskId, ReceiptStatus.FAILED, outcome.error, now)) SendReceipt(outcome.taskId, ReceiptStatus.FAILED, outcome.error) else null
            }
        }
        if (receipt != null) emit(receipt)
    }

    /**
     * Receipts for the tasks settled in the last [WINDOW_MS], for replaying after a (re)connect. The server treats repeats
     * as no-ops, so this is how a receipt lost with a dropped connection reaches it eventually.
     */
    fun receiptsToReplay(): List<SendReceipt> = synchronized(lock) { ledger.receiptsSince(clock() - WINDOW_MS, MAX_REPLAY) }

    /**
     * Tasks that were mid-send when the process died: the outcome is unknown. Reporting them as failed is the safe
     * choice (a duplicate send would be worse than a wrongly reported failure); the server corrects itself if a later
     * "sent" ever arrives.
     */
    fun settleStale(): List<SendReceipt> = synchronized(lock) {
        val now = clock()
        ledger.staleSending(now - STALE_MS).mapNotNull { id ->
            if (ledger.update(id, ReceiptStatus.FAILED, SendError.RADIO_ERROR, now)) SendReceipt(id, ReceiptStatus.FAILED, SendError.RADIO_ERROR) else null
        }
    }

    companion object {
        const val WINDOW_MS = 24L * 60 * 60 * 1000
        const val MAX_REPLAY = 50
        const val STALE_MS = 2L * 60 * 1000
    }
}
