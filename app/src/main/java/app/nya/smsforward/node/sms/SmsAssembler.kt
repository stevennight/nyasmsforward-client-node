package app.nya.smsforward.node.sms

/** One PDU as delivered by the SMS_RECEIVED broadcast. */
data class SmsPart(val address: String?, val body: String?, val timestampMillis: Long)

/** A complete message: all parts of a long SMS joined. */
data class IncomingSms(val peer: String, val body: String, val timestampMillis: Long)

/**
 * A long SMS arrives as several PDUs in one broadcast. This joins the parts from the same sender back into one message,
 * in the order they were delivered (the platform already orders them by sequence number), so the server gets one
 * message instead of fragments.
 */
object SmsAssembler {
    const val UNKNOWN_SENDER = "未知号码"

    fun assemble(parts: List<SmsPart>): List<IncomingSms> {
        // LinkedHashMap keeps senders in the order they first appeared.
        val bySender = LinkedHashMap<String, Pair<StringBuilder, Long>>()
        for (part in parts) {
            val sender = part.address?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN_SENDER
            val existing = bySender[sender]
            if (existing == null) {
                bySender[sender] = StringBuilder(part.body.orEmpty()) to part.timestampMillis
            } else {
                existing.first.append(part.body.orEmpty())
            }
        }
        return bySender.map { (sender, v) -> IncomingSms(sender, v.first.toString(), v.second) }
            .filter { it.body.isNotEmpty() } // the server rejects empty bodies; nothing to report anyway
    }
}
