package app.nya.smsforward.node.send

/**
 * Joins the per-part results of a (possibly multipart) SMS into one verdict per task.
 *
 * SmsManager reports every part separately, through PendingIntents that name the task, the part index and the part
 * count. The counts travel in the intents, so this also works when the process was restarted between sending and the
 * result arriving (the in-memory progress is simply rebuilt from the first callback).
 */
class SendStatusTracker {
    /** What a callback means for the task as a whole. */
    sealed interface Outcome {
        val taskId: String

        /** Every part was handed to the network. */
        data class Sent(override val taskId: String) : Outcome

        /** A part could not be sent, or the network reported that a part was not delivered. */
        data class Failed(override val taskId: String, val error: String) : Outcome

        /** Every part reached the recipient's phone. */
        data class Delivered(override val taskId: String) : Outcome
    }

    private class Progress(val count: Int) {
        val sent = HashSet<Int>()
        val delivered = HashSet<Int>()
        var settled = false // failed: ignore whatever else the other parts report
    }

    // Insertion-ordered so the oldest entries can be dropped: tasks whose carrier never sends delivery reports stay here.
    private val tasks = object : LinkedHashMap<String, Progress>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Progress>) = size > MAX_TRACKED
    }

    @Synchronized
    fun onPartSent(taskId: String, index: Int, count: Int, error: String?): Outcome? {
        val p = tasks.getOrPut(taskId) { Progress(count.coerceAtLeast(1)) }
        if (p.settled) return null
        if (error != null) {
            p.settled = true
            return Outcome.Failed(taskId, error)
        }
        // Only the first time all parts are in produces "sent"; a repeated callback returns null.
        val firstTime = p.sent.add(index)
        return if (firstTime && p.sent.size == p.count) Outcome.Sent(taskId) else null
    }

    @Synchronized
    fun onPartDelivered(taskId: String, index: Int, count: Int, ok: Boolean): Outcome? {
        val p = tasks.getOrPut(taskId) { Progress(count.coerceAtLeast(1)) }
        if (p.settled) return null
        if (!ok) {
            p.settled = true
            return Outcome.Failed(taskId, SendError.DELIVERY_FAILED)
        }
        val firstTime = p.delivered.add(index)
        return if (firstTime && p.delivered.size == p.count) {
            tasks.remove(taskId) // everything reported: nothing more can arrive for this task
            Outcome.Delivered(taskId)
        } else {
            null
        }
    }

    @Synchronized
    fun forget(taskId: String) {
        tasks.remove(taskId)
    }

    companion object {
        private const val MAX_TRACKED = 256

        // android.telephony.SmsManager result codes (kept as numbers so this class stays testable on the JVM).
        private const val RESULT_ERROR_LIMIT_EXCEEDED = 5

        /**
         * Reads the TP-Status byte of a delivery report (3GPP TS 23.040 §9.2.3.15): 0x00-0x1F means the message was
         * delivered, 0x20-0x3F means the network is still trying (no verdict yet: null), 0x40 and up is a failure.
         */
        fun deliveryOk(tpStatus: Int): Boolean? = when {
            tpStatus < 0x20 -> true
            tpStatus < 0x40 -> null
            else -> false
        }

        /** Maps the result code of a "sent" callback to the error code reported to the server; null when it succeeded. */
        fun errorFor(resultCode: Int, ok: Int): String? = when (resultCode) {
            ok -> null
            RESULT_ERROR_LIMIT_EXCEEDED -> SendError.RATE_LIMITED // Android's own anti-spam limit
            else -> SendError.RADIO_ERROR // generic failure, radio off, no service, null PDU, short code blocked…
        }
    }
}
