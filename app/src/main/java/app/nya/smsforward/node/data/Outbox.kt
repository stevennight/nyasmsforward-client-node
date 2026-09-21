package app.nya.smsforward.node.data

/** A received SMS waiting to be reported. [dedupeKey] is computed once, at receipt (docs/协议.md §5.2). */
data class NewOutboxItem(
    val dedupeKey: String,
    val peer: String,
    val body: String,
    val simSlot: Int?,
    val deviceTime: Long,
    /** "in" for a received SMS, "out" for one the user sent from the phone's own SMS app (docs/协议.md §5.1). */
    val direction: String = "in",
    /** History read from the SMS database: reported quietly (no alert, already read). */
    val backfill: Boolean = false,
    val cardNumber: String? = null,
)

data class OutboxItem(
    val id: Long,
    val dedupeKey: String,
    val peer: String,
    val body: String,
    val simSlot: Int?,
    val deviceTime: Long,
    val attempts: Int,
    val direction: String = "in",
    val backfill: Boolean = false,
    val cardNumber: String? = null,
)

enum class OutboxState(val wire: String) {
    /** Not reported yet (or reported but not confirmed): will be retried. */
    PENDING("pending"),

    /** The server accepted it or already had it. */
    DONE("done"),

    /** The server refused it for good (e.g. malformed); retrying would change nothing. */
    DEAD("dead"),
}

data class RecentItem(
    val peer: String,
    val body: String,
    val deviceTime: Long,
    val state: OutboxState,
    val error: String?,
)

/**
 * The local queue between "SMS received" and "reported to the server".
 *
 * Receiving never depends on the network or on being paired: an SMS is written here first and reported later, so a
 * dead server, a revoked token or a plane ride loses nothing (docs/协议.md §2.2).
 */
interface Outbox {
    /** Returns false when the same dedupe key is already queued (e.g. the broadcast was delivered twice). */
    fun enqueue(item: NewOutboxItem, now: Long): Boolean

    /** Oldest first. */
    fun pending(limit: Int): List<OutboxItem>

    fun markDone(ids: List<Long>, now: Long)
    fun markDead(id: Long, error: String, now: Long)

    /** Counts an upload attempt for the given rows (for the status screen and for diagnosing stuck rows). */
    fun recordAttempt(ids: List<Long>)

    fun pendingCount(): Int
    fun recent(limit: Int): List<RecentItem>

    /** Remembers that [peerKey] messaged this phone. "Reply only" mode (M3) may only answer such numbers. */
    fun touchPeer(peerKey: String, now: Long)
    fun recentPeers(since: Long): Set<String>

    /** Drops old finished rows and old peers; pending rows are never pruned. */
    fun prune(now: Long)
}
