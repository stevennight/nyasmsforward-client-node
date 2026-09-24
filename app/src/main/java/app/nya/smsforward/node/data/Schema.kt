package app.nya.smsforward.node.data

/**
 * The database schema, versioned with PRAGMA user_version.
 *
 * Every step uses CREATE ... IF NOT EXISTS and the whole thing runs whenever the stored version is behind, so it is safe to
 * run on a fresh database, on an old one, and on a database whose version number was bumped without its tables being
 * created (the first release let Android's SQLiteOpenHelper own user_version, which did exactly that). To change the
 * schema add statements and raise [LATEST]; never edit what has shipped.
 */
object Schema {
    const val LATEST = 5

    fun migrate(db: SqlDb) = db.transaction {
        if (db.version < LATEST) {
            db.execute(
                """CREATE TABLE IF NOT EXISTS outbox (
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
            db.execute("CREATE INDEX IF NOT EXISTS idx_outbox_state ON outbox (state, id)")
            db.execute("CREATE TABLE IF NOT EXISTS recent_peers (peer_key TEXT PRIMARY KEY, last_seen_at INTEGER NOT NULL)")

            // Send tasks handed to this phone by the server (M3). One row per taskId makes handling idempotent: the
            // server may offer the same task again after a reconnect, and a task must never be sent twice.
            db.execute(
                """CREATE TABLE IF NOT EXISTS send_tasks (
                     task_id       TEXT PRIMARY KEY,
                     recipient     TEXT NOT NULL,
                     mode          TEXT NOT NULL,
                     state         TEXT NOT NULL,
                     error         TEXT,
                     started_at    INTEGER,
                     created_at    INTEGER NOT NULL,
                     updated_at    INTEGER NOT NULL
                   )""",
            )
            db.execute("CREATE INDEX IF NOT EXISTS idx_send_started ON send_tasks (started_at)")

            // Version 3 (M4): sent messages and history backfill share the outbox, and the ledger remembers a hash of what a
            // task sent so the sent box does not report it a second time. ALTER has no IF NOT EXISTS, so look first.
            addColumnIfMissing(db, "outbox", "direction", "TEXT NOT NULL DEFAULT 'in'")
            addColumnIfMissing(db, "outbox", "backfill", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "outbox", "card_number", "TEXT")
            addColumnIfMissing(db, "send_tasks", "body_hash", "TEXT")

            // Version 5 (full edition): the phone's recycle bin. A deleted SMS is copied here before it leaves the
            // system database, so it can be put back for 30 days.
            db.execute(
                """CREATE TABLE IF NOT EXISTS sms_trash (
                     id          INTEGER PRIMARY KEY AUTOINCREMENT,
                     address     TEXT NOT NULL,
                     body        TEXT NOT NULL,
                     date        INTEGER NOT NULL,
                     date_sent   INTEGER NOT NULL DEFAULT 0,
                     type        INTEGER NOT NULL,
                     read        INTEGER NOT NULL DEFAULT 1,
                     sub_id      INTEGER,
                     origin      TEXT NOT NULL,
                     deleted_at  INTEGER NOT NULL
                   )""",
            )
            db.execute("CREATE INDEX IF NOT EXISTS idx_sms_trash_deleted ON sms_trash (deleted_at)")
            db.version = LATEST
        }
    }

    private fun addColumnIfMissing(db: SqlDb, table: String, column: String, definition: String) {
        val columns = db.query("PRAGMA table_info($table)") { it.string(1) }
        if (column !in columns) db.execute("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}
