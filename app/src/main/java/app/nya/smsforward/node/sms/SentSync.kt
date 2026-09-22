package app.nya.smsforward.node.sms

import app.nya.smsforward.node.data.NewOutboxItem
import app.nya.smsforward.node.data.Outbox
import app.nya.smsforward.node.data.SendLedger
import app.nya.smsforward.node.node.NodeSettings
import app.nya.smsforward.node.send.BodyHash

/** One row of the phone's own SMS database. */
data class SystemSms(
    /** The provider's `_id`: grows with every message, so it works as a cursor. */
    val id: Long,
    /** The other party: the sender of an inbox message, the recipient of a sent one. */
    val address: String,
    val body: String,
    /** Epoch millis. For received messages the time the SMSC stamped on it, which is what the live receiver used. */
    val time: Long,
    val simSlot: Int?,
    /** The line number, when the platform SMS provider can associate one. */
    val cardNumber: String? = null,
)

/** A server deletion request. Matching uses provider fields rather than the server id (the phone has no server id). */
data class DeleteSms(
    val messageId: Long,
    val direction: String,
    val peer: String,
    val body: String,
    val deviceTime: Long,
    val simSlot: Int? = null,
    val cardNumber: String? = null,
)

enum class DeleteOutcome(val wire: String) { DELETED("deleted"), NOT_FOUND("not_found"), DENIED("denied"), FAILED("failed") }

/** Read access to the SMS database (needs READ_SMS). The Android implementation is [app.nya.smsforward.node.sms.AndroidSmsBox]. */
interface SmsBox {
    /** The highest `_id` in the sent box, or 0 when it is empty. */
    fun maxSentId(): Long

    /** Sent messages with `_id` greater than [afterId], oldest first. */
    fun sentAfter(afterId: Long, limit: Int): List<SystemSms>

    /** Received messages from [sinceMillis] on, oldest first. */
    fun inboxSince(sinceMillis: Long, limit: Int): List<SystemSms>

    /** Sent messages from [sinceMillis] on, oldest first. */
    fun sentSince(sinceMillis: Long, limit: Int): List<SystemSms>

    /** Best-effort removal from the system provider. Implementations may report DENIED when not the default SMS app. */
    fun delete(request: DeleteSms): DeleteOutcome = DeleteOutcome.FAILED
}

/**
 * The optional M4 features (docs/协议.md §5.1): report what the user sends from the phone's own SMS app, and read old
 * history once. Both only read when the user switched them on, and both only queue: uploading is the uploader's job.
 *
 * What the platform sent through a task is skipped here (the ledger remembers it), and the server double-checks, so a
 * reply sent from the web never shows up twice in the conversation.
 */
class SentSync(
    private val settings: NodeSettings,
    private val box: SmsBox,
    private val outbox: Outbox,
    private val ledger: SendLedger,
    private val clock: () -> Long,
) {
    /** Queues the messages sent since the last pass. Returns how many were queued. */
    fun syncSent(): Int {
        val deviceId = settings.deviceId ?: return 0
        if (!settings.syncSent) return 0
        val cursor = settings.sentCursor
        if (cursor < 0) {
            // First pass after switching on: only what is sent from now on. Old messages are the job of the history option.
            settings.sentCursor = box.maxSentId()
            return 0
        }
        val now = clock()
        var queued = 0
        var newest = cursor
        for (sms in box.sentAfter(cursor, PAGE)) {
            newest = maxOf(newest, sms.id)
            if (skip(sms)) continue
            if (enqueue(deviceId, sms, "out", backfill = false, now)) queued++
        }
        settings.sentCursor = newest
        return queued
    }

    /**
     * Reports the last [NodeSettings.backfillDays] days of history, once per setting: received and sent messages, marked
     * as backfill so nobody gets alerted. Returns how many were queued.
     */
    fun backfill(): Int {
        val deviceId = settings.deviceId ?: return 0
        val days = settings.backfillDays
        if (!settings.syncSent || days <= 0 || settings.backfilledDays >= days) return 0
        val now = clock()
        val since = now - days * DAY_MS
        var queued = 0
        for (sms in box.inboxSince(since, HISTORY_LIMIT)) {
            if (enqueue(deviceId, sms, "in", backfill = true, now)) queued++
            // A number that wrote to us within the reply window may be answered again: remember it by the message's own
            // time, never by "now", so old history does not widen what "reply only" allows.
            if (PeerKey.isReplyable(sms.address)) outbox.touchPeer(PeerKey.normalize(sms.address), sms.time)
        }
        for (sms in box.sentSince(since, HISTORY_LIMIT)) {
            if (skip(sms)) continue
            if (enqueue(deviceId, sms, "out", backfill = true, now)) queued++
        }
        settings.backfilledDays = days
        return queued
    }

    private fun skip(sms: SystemSms): Boolean =
        ledger.sentByTask(PeerKey.normalize(sms.address), BodyHash.of(sms.body), sms.time, MATCH_WINDOW_MS)

    private fun enqueue(deviceId: String, sms: SystemSms, direction: String, backfill: Boolean, now: Long): Boolean {
        val time = if (sms.time <= 0 || sms.time > now + MAX_FUTURE_MS) now else sms.time
        val key = DedupeKey.compute(deviceId, sms.cardNumber, sms.simSlot ?: 0, sms.address, time, sms.body)
        return outbox.enqueue(NewOutboxItem(key, sms.address, sms.body, sms.simSlot, time, direction, backfill, sms.cardNumber), now)
    }

    private companion object {
        const val PAGE = 200
        const val HISTORY_LIMIT = 5000
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MATCH_WINDOW_MS = 5L * 60 * 1000 // the same window the server uses
        const val MAX_FUTURE_MS = 5 * 60 * 1000L
    }
}
