package app.nya.smsforward.node.data

import app.nya.smsforward.node.send.ReceiptStatus
import app.nya.smsforward.node.send.SendReceipt

enum class LedgerState(val wire: String) {
    /** Handed to SmsManager, no result yet. If the process dies here nobody knows whether it left: see [SendLedger.staleSending]. */
    SENDING("sending"),
    SENT("sent"),
    DELIVERED("delivered"),
    FAILED("failed"),
}

data class LedgerEntry(
    val taskId: String,
    val recipient: String,
    val mode: String,
    val state: LedgerState,
    val error: String?,
    /** null for tasks that were refused before sending (those do not count against the hourly limit). */
    val startedAt: Long?,
    val updatedAt: Long,
)

/**
 * The phone's own record of send tasks. It is what makes the channel safe:
 *  - one row per taskId, so a task the server offers again is never sent twice;
 *  - the durable source of receipts, replayed after every reconnect (the server ignores repeats);
 *  - the count behind the hourly limit, which lives on the phone where the server cannot reset it.
 * Message bodies are deliberately not stored.
 */
interface SendLedger {
    fun find(taskId: String): LedgerEntry?

    /** Records that sending starts now. Returns false if the task is already known (then nothing must be sent). */
    fun begin(taskId: String, recipient: String, mode: String, now: Long): Boolean

    /** Records a task refused before sending. Returns false if the task is already known. */
    fun reject(taskId: String, recipient: String, mode: String, error: String, now: Long): Boolean

    /**
     * Moves a task forward: SENDING -> SENT | FAILED, SENT -> DELIVERED | FAILED. Anything else is ignored, so late or
     * repeated radio callbacks cannot move a task backwards. Returns whether the entry changed.
     */
    fun update(taskId: String, status: ReceiptStatus, error: String?, now: Long): Boolean

    /** Tasks that started sending at or after [since]: the hourly limit counts these. */
    fun startedSince(since: Long): Int

    /** Receipts for tasks settled at or after [since], oldest first, to be replayed on (re)connect. */
    fun receiptsSince(since: Long, limit: Int): List<SendReceipt>

    /** Tasks stuck in SENDING since before [before]: the process died mid-send, the outcome is unknown. */
    fun staleSending(before: Long): List<String>

    fun recent(limit: Int): List<LedgerEntry>

    fun prune(now: Long)
}

class SqliteSendLedger(private val db: SqlDb) : SendLedger {
    init {
        Schema.migrate(db)
    }

    override fun find(taskId: String): LedgerEntry? =
        db.query("SELECT $COLS FROM send_tasks WHERE task_id = ?", listOf(taskId), ::entry).firstOrNull()

    override fun begin(taskId: String, recipient: String, mode: String, now: Long): Boolean =
        db.execute(
            "INSERT OR IGNORE INTO send_tasks (task_id, recipient, mode, state, started_at, created_at, updated_at) VALUES (?, ?, ?, 'sending', ?, ?, ?)",
            listOf(taskId, recipient, mode, now, now, now),
        ) > 0

    override fun reject(taskId: String, recipient: String, mode: String, error: String, now: Long): Boolean =
        db.execute(
            "INSERT OR IGNORE INTO send_tasks (task_id, recipient, mode, state, error, created_at, updated_at) VALUES (?, ?, ?, 'failed', ?, ?, ?)",
            listOf(taskId, recipient, mode, error, now, now),
        ) > 0

    override fun update(taskId: String, status: ReceiptStatus, error: String?, now: Long): Boolean {
        val from = when (status) {
            ReceiptStatus.SENT -> "'sending'"
            ReceiptStatus.DELIVERED -> "'sent'"
            ReceiptStatus.FAILED -> "'sending', 'sent'"
        }
        return db.execute(
            "UPDATE send_tasks SET state = ?, error = ?, updated_at = ? WHERE task_id = ? AND state IN ($from)",
            listOf(status.wire, error, now, taskId),
        ) > 0
    }

    override fun startedSince(since: Long): Int =
        db.query("SELECT COUNT(*) FROM send_tasks WHERE started_at IS NOT NULL AND started_at >= ?", listOf(since)) { it.long(0).toInt() }.first()

    override fun receiptsSince(since: Long, limit: Int): List<SendReceipt> =
        db.query(
            "SELECT task_id, state, error FROM send_tasks WHERE state != 'sending' AND updated_at >= ? ORDER BY updated_at, task_id LIMIT ?",
            listOf(since, limit),
        ) { r ->
            SendReceipt(r.string(0), ReceiptStatus.entries.first { it.wire == r.string(1) }, r.stringOrNull(2))
        }

    override fun staleSending(before: Long): List<String> =
        db.query("SELECT task_id FROM send_tasks WHERE state = 'sending' AND updated_at < ?", listOf(before)) { it.string(0) }

    override fun recent(limit: Int): List<LedgerEntry> =
        db.query("SELECT $COLS FROM send_tasks ORDER BY updated_at DESC, task_id LIMIT ?", listOf(limit), ::entry)

    override fun prune(now: Long) {
        db.execute("DELETE FROM send_tasks WHERE state != 'sending' AND updated_at < ?", listOf(now - KEEP_MS))
    }

    private fun entry(r: Row) = LedgerEntry(
        taskId = r.string(0),
        recipient = r.string(1),
        mode = r.string(2),
        state = LedgerState.entries.first { it.wire == r.string(3) },
        error = r.stringOrNull(4),
        startedAt = r.longOrNull(5),
        updatedAt = r.long(6),
    )

    private companion object {
        const val COLS = "task_id, recipient, mode, state, error, started_at, updated_at"

        /** Long enough that a task can never be offered again after its row is gone (the server expires tasks in minutes). */
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
