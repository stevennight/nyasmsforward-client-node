package app.nya.smsforward.node.data

import app.nya.smsforward.node.sms.BlockRule
import app.nya.smsforward.node.sms.SpamCategory
import app.nya.smsforward.node.sms.SpamFilter

/** An SMS the full edition intercepted: kept here instead of the system inbox. */
data class BlockedSms(
    val id: Long = 0,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val subId: Int?,
    /** BlockRule.NUMBER / KEYWORD / BUILTIN (MARKETING in old records), and the rule value that matched. */
    val reason: String,
    val detail: String,
    val blockedAt: Long,
)

/** The interception rules and the intercepted messages (kept [SmsTrash.RETENTION_MS], like the recycle bin). */
class SmsBlockList(private val db: SqlDb) {
    init {
        Schema.migrate(db)
    }

    // --- rules ---

    fun rules(): List<BlockRule> =
        db.query("SELECT id, kind, value FROM block_rules ORDER BY id DESC") { BlockRule(it.long(0), it.string(1), it.string(2)) }

    /** False when the rule was there already or is empty. */
    fun addRule(kind: String, value: String, now: Long): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        return db.execute("INSERT OR IGNORE INTO block_rules (kind, value, created_at) VALUES (?, ?, ?)", listOf(kind, v, now)) > 0
    }

    fun removeRule(id: Long): Boolean = db.execute("DELETE FROM block_rules WHERE id = ?", listOf(id)) > 0

    fun enabledCategories(): Set<SpamCategory> = SpamFilter.enabledCategories(rules())

    /** Switches one built-in category on or off. */
    fun setCategory(category: SpamCategory, on: Boolean) {
        if (on) addRule(BlockRule.BUILTIN, category.id, System.currentTimeMillis())
        else db.execute("DELETE FROM block_rules WHERE kind = ? AND value = ?", listOf(BlockRule.BUILTIN, category.id))
    }

    // --- intercepted messages ---

    fun add(sms: BlockedSms): Long = db.transaction {
        db.execute(
            "INSERT INTO sms_blocked (address, body, date, date_sent, sub_id, reason, detail, blocked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(sms.address, sms.body, sms.date, sms.dateSent, sms.subId, sms.reason, sms.detail, sms.blockedAt),
        )
        db.query("SELECT last_insert_rowid()") { it.long(0) }.first()
    }

    /** Newest first. */
    fun list(limit: Int = 500): List<BlockedSms> =
        db.query("SELECT $COLS FROM sms_blocked ORDER BY blocked_at DESC, id DESC LIMIT ?", listOf(limit), ::entry)

    fun find(id: Long): BlockedSms? = db.query("SELECT $COLS FROM sms_blocked WHERE id = ?", listOf(id), ::entry).firstOrNull()

    fun remove(id: Long): Boolean = db.execute("DELETE FROM sms_blocked WHERE id = ?", listOf(id)) > 0

    fun removeAll(ids: Collection<Long>): Int = db.transaction { ids.sumOf { db.execute("DELETE FROM sms_blocked WHERE id = ?", listOf(it)) } }

    fun clear(): Int = db.execute("DELETE FROM sms_blocked")

    fun purge(now: Long): Int = db.execute("DELETE FROM sms_blocked WHERE blocked_at < ?", listOf(now - SmsTrash.RETENTION_MS))

    private fun entry(r: Row) = BlockedSms(
        id = r.long(0),
        address = r.string(1),
        body = r.string(2),
        date = r.long(3),
        dateSent = r.long(4),
        subId = r.longOrNull(5)?.toInt(),
        reason = r.string(6),
        detail = r.string(7),
        blockedAt = r.long(8),
    )

    private companion object {
        const val COLS = "id, address, body, date, date_sent, sub_id, reason, detail, blocked_at"
    }
}
