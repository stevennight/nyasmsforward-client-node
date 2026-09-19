package app.nya.smsforward.node.data

/** [Outbox] on SQLite. The schema is versioned with PRAGMA user_version; add a step to [migrate] to change it. */
class SqliteOutbox(private val db: SqlDb) : Outbox {
    init {
        migrate()
    }

    private fun migrate() = db.transaction {
        if (db.version < 1) {
            db.execute(
                """CREATE TABLE outbox (
                     id          INTEGER PRIMARY KEY AUTOINCREMENT,
                     dedupe_key  TEXT NOT NULL UNIQUE,
                     peer        TEXT NOT NULL,
                     body        TEXT NOT NULL,
                     sim_slot    INTEGER,
                     device_time INTEGER NOT NULL,
                     created_at  INTEGER NOT NULL,
                     state       TEXT NOT NULL DEFAULT 'pending',
                     attempts    INTEGER NOT NULL DEFAULT 0,
                     last_error  TEXT,
                     finished_at INTEGER
                   )""",
            )
            db.execute("CREATE INDEX idx_outbox_state ON outbox (state, id)")
            db.execute("CREATE TABLE recent_peers (peer_key TEXT PRIMARY KEY, last_seen_at INTEGER NOT NULL)")
            db.version = 1
        }
    }

    override fun enqueue(item: NewOutboxItem, now: Long): Boolean =
        db.execute(
            "INSERT OR IGNORE INTO outbox (dedupe_key, peer, body, sim_slot, device_time, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(item.dedupeKey, item.peer, item.body, item.simSlot, item.deviceTime, now),
        ) > 0

    override fun pending(limit: Int): List<OutboxItem> =
        db.query(
            "SELECT id, dedupe_key, peer, body, sim_slot, device_time, attempts FROM outbox WHERE state = 'pending' ORDER BY id LIMIT ?",
            listOf(limit),
        ) { r ->
            OutboxItem(r.long(0), r.string(1), r.string(2), r.string(3), r.longOrNull(4)?.toInt(), r.long(5), r.long(6).toInt())
        }

    override fun markDone(ids: List<Long>, now: Long) {
        if (ids.isEmpty()) return
        db.transaction {
            for (id in ids) {
                db.execute("UPDATE outbox SET state = 'done', finished_at = ?, last_error = NULL WHERE id = ?", listOf(now, id))
            }
        }
    }

    override fun markDead(id: Long, error: String, now: Long) {
        db.execute("UPDATE outbox SET state = 'dead', finished_at = ?, last_error = ? WHERE id = ?", listOf(now, error, id))
    }

    override fun recordAttempt(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.transaction {
            for (id in ids) db.execute("UPDATE outbox SET attempts = attempts + 1 WHERE id = ?", listOf(id))
        }
    }

    override fun pendingCount(): Int =
        db.query("SELECT COUNT(*) FROM outbox WHERE state = 'pending'") { it.long(0).toInt() }.first()

    override fun recent(limit: Int): List<RecentItem> =
        db.query(
            "SELECT peer, body, device_time, state, last_error FROM outbox ORDER BY id DESC LIMIT ?",
            listOf(limit),
        ) { r ->
            RecentItem(
                r.string(0), r.string(1), r.long(2),
                OutboxState.entries.firstOrNull { it.wire == r.string(3) } ?: OutboxState.PENDING,
                r.stringOrNull(4),
            )
        }

    override fun touchPeer(peerKey: String, now: Long) {
        db.execute(
            "INSERT INTO recent_peers (peer_key, last_seen_at) VALUES (?, ?) ON CONFLICT (peer_key) DO UPDATE SET last_seen_at = excluded.last_seen_at",
            listOf(peerKey, now),
        )
    }

    override fun recentPeers(since: Long): Set<String> =
        db.query("SELECT peer_key FROM recent_peers WHERE last_seen_at >= ?", listOf(since)) { it.string(0) }.toSet()

    override fun prune(now: Long) {
        db.transaction {
            // Keep finished rows for a month for the status list, and never more than KEEP_FINISHED of them.
            db.execute("DELETE FROM outbox WHERE state != 'pending' AND finished_at < ?", listOf(now - FINISHED_TTL_MS))
            db.execute(
                "DELETE FROM outbox WHERE state != 'pending' AND id NOT IN (SELECT id FROM outbox WHERE state != 'pending' ORDER BY id DESC LIMIT ?)",
                listOf(KEEP_FINISHED),
            )
            db.execute("DELETE FROM recent_peers WHERE last_seen_at < ?", listOf(now - PEER_TTL_MS))
        }
    }

    companion object {
        const val FINISHED_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val PEER_TTL_MS = 90L * 24 * 60 * 60 * 1000
        const val KEEP_FINISHED = 500
    }
}
