package app.nya.smsforward.node.data

/** A copy of an SMS that was taken out of the system database, kept so it can be put back. */
data class TrashedSms(
    val id: Long = 0,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: Int,
    val read: Boolean,
    val subId: Int?,
    /** "platform" when the server asked for the deletion, "app" when the user deleted it on the phone. */
    val origin: String,
    val deletedAt: Long,
)

/** The full edition's recycle bin (docs: README「两个版本」). Entries older than [RETENTION_MS] are purged. */
class SmsTrash(private val db: SqlDb) {
    init {
        Schema.migrate(db)
    }

    fun add(sms: TrashedSms): Long = db.transaction {
        db.execute(
            "INSERT INTO sms_trash (address, body, date, date_sent, type, read, sub_id, origin, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(sms.address, sms.body, sms.date, sms.dateSent, sms.type, if (sms.read) 1 else 0, sms.subId, sms.origin, sms.deletedAt),
        )
        db.query("SELECT last_insert_rowid()") { it.long(0) }.first()
    }

    /** Newest deletion first. */
    fun list(limit: Int = 500): List<TrashedSms> =
        db.query("SELECT $COLS FROM sms_trash ORDER BY deleted_at DESC, id DESC LIMIT ?", listOf(limit), ::entry)

    fun find(id: Long): TrashedSms? = db.query("SELECT $COLS FROM sms_trash WHERE id = ?", listOf(id), ::entry).firstOrNull()

    fun remove(id: Long): Boolean = db.execute("DELETE FROM sms_trash WHERE id = ?", listOf(id)) > 0

    fun clear(): Int = db.execute("DELETE FROM sms_trash")

    /** Permanently drops what was deleted more than [RETENTION_MS] before [now]. */
    fun purge(now: Long): Int = db.execute("DELETE FROM sms_trash WHERE deleted_at < ?", listOf(now - RETENTION_MS))

    private fun entry(r: Row) = TrashedSms(
        id = r.long(0),
        address = r.string(1),
        body = r.string(2),
        date = r.long(3),
        dateSent = r.long(4),
        type = r.long(5).toInt(),
        read = r.long(6) != 0L,
        subId = r.longOrNull(7)?.toInt(),
        origin = r.string(8),
        deletedAt = r.long(9),
    )

    companion object {
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val COLS = "id, address, body, date, date_sent, type, read, sub_id, origin, deleted_at"

        /** Whole days left before [sms] is purged, for "剩 N 天可恢复". */
        fun daysLeft(sms: TrashedSms, now: Long): Int =
            ((sms.deletedAt + RETENTION_MS - now + 86_399_999) / 86_400_000).toInt().coerceAtLeast(0)
    }
}
