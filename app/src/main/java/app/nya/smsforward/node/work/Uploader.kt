package app.nya.smsforward.node.work

import app.nya.smsforward.node.data.Outbox
import app.nya.smsforward.node.data.OutboxItem
import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ConnectionVerdict
import app.nya.smsforward.node.net.NodeApi
import app.nya.smsforward.node.net.UploadMessage
import app.nya.smsforward.node.node.NodeSettings
import app.nya.smsforward.node.node.TokenStore
import app.nya.smsforward.node.node.describeFailure

sealed interface FlushResult {
    /** The queue is empty (everything reported, or nothing to do). */
    data class Done(val uploaded: Int) : FlushResult

    /** Something went wrong that time may fix: try again later. */
    data class Retry(val uploaded: Int, val reason: String) : FlushResult

    /** The server rejected our token. Uploads pause; the queue is kept. */
    data object NeedsPairing : FlushResult

    /** Not set up (yet): nothing to upload to. */
    data object NotPaired : FlushResult
}

/**
 * Reports queued SMS to the server, oldest first, in batches.
 *
 * The rules that keep messages safe:
 *  - a message leaves the queue only after the server says `accepted` or `duplicate`;
 *  - `rejected` is final (the same bytes would be refused again) and is kept as a dead row for diagnosis;
 *  - every other outcome, including a batch we could not deliver, leaves the message queued;
 *  - a revoked token pauses uploading but never discards anything.
 */
class Uploader(
    private val outbox: Outbox,
    private val api: NodeApi,
    private val settings: NodeSettings,
    private val tokens: TokenStore,
    private val clock: () -> Long,
) {
    suspend fun flush(): FlushResult {
        val baseUrl = settings.serverUrl
        val token = tokens.read()
        if (baseUrl == null || token == null || settings.deviceId == null) return FlushResult.NotPaired
        if (settings.needsPairing) return FlushResult.NeedsPairing

        var uploaded = 0
        repeat(MAX_BATCHES) {
            val batch = outbox.pending(BATCH_SIZE)
            if (batch.isEmpty()) {
                outbox.prune(clock())
                return FlushResult.Done(uploaded)
            }
            outbox.recordAttempt(batch.map { it.id })

            when (val result = api.upload(baseUrl, token, batch.map { it.toWire() })) {
                is ApiResult.Failure -> return when (result.verdict) {
                    ConnectionVerdict.NEEDS_PAIRING -> {
                        settings.needsPairing = true
                        settings.lastError = "令牌已失效，需要重新配对"
                        FlushResult.NeedsPairing
                    }
                    else -> {
                        val why = describeFailure(result)
                        settings.lastError = why
                        FlushResult.Retry(uploaded, why)
                    }
                }
                is ApiResult.Ok -> {
                    val progressed = apply(batch, result.value)
                    if (progressed == 0) {
                        // The server answered but settled nothing (unknown statuses, missing results). Do not spin.
                        settings.lastError = "服务器返回的结果无法识别"
                        return FlushResult.Retry(uploaded, "server settled no messages")
                    }
                    uploaded += progressed
                    settings.lastUploadAt = clock()
                    settings.lastError = null
                }
            }
        }
        return FlushResult.Retry(uploaded, "more messages are waiting") // a very long backlog: continue in the next run
    }

    /** Applies the server's per-message verdicts. Returns how many messages left the pending state. */
    private fun apply(batch: List<OutboxItem>, outcomes: List<app.nya.smsforward.node.net.UploadOutcome>): Int {
        val byKey = outcomes.associateBy { it.dedupeKey }
        val now = clock()
        val done = ArrayList<Long>()
        var dead = 0
        for (item in batch) {
            val outcome = byKey[item.dedupeKey] ?: continue
            when (outcome.status) {
                "accepted", "duplicate" -> done += item.id
                "rejected" -> {
                    outbox.markDead(item.id, outcome.error ?: "rejected", now)
                    dead++
                }
                // Anything else is a status from a newer server: leave the message queued rather than guess.
            }
        }
        outbox.markDone(done, now)
        return done.size + dead
    }

    private fun OutboxItem.toWire() = UploadMessage(
        dedupeKey = dedupeKey, direction = direction, peer = peer, body = body, cardNumber = cardNumber, simSlot = simSlot, deviceTime = deviceTime,
        backfill = backfill,
    )

    private companion object {
        const val BATCH_SIZE = 100 // the server accepts at most 100 per request
        const val MAX_BATCHES = 20 // 2000 messages per run; the rest goes in the next one
    }
}
