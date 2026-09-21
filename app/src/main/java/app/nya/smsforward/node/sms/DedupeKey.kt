package app.nya.smsforward.node.sms

import java.security.MessageDigest

/**
 * The idempotency key the phone attaches to every reported message (docs/协议.md §5):
 * `sha256:` + hex(SHA-256(UTF-8 of "deviceId|simSlot|peer|deviceTime|body")).
 *
 * The server enforces UNIQUE(device_id, dedupe_key), so re-uploading after a crash or a lost response is harmless.
 * `body` comes last on purpose: it is free text and may itself contain the separator.
 */
object DedupeKey {
    fun compute(deviceId: String, cardNumber: String?, simSlot: Int, peer: String, deviceTimeMillis: Long, body: String): String {
        if (cardNumber.isNullOrBlank()) return compute(deviceId, simSlot, peer, deviceTimeMillis, body)
        return hash("$deviceId|card:${PeerKey.normalize(cardNumber)}|$peer|$deviceTimeMillis|$body")
    }

    fun compute(deviceId: String, simSlot: Int, peer: String, deviceTimeMillis: Long, body: String): String {
        return hash("$deviceId|$simSlot|$peer|$deviceTimeMillis|$body")
    }

    private fun hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
