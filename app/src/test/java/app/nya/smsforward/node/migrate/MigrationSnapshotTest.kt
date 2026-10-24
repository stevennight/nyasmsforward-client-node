package app.nya.smsforward.node.migrate

import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.Schema
import app.nya.smsforward.node.data.SqlDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MigrationSnapshotTest {
    private fun db(): SqlDb = JdbcSqlDb().also { Schema.migrate(it) }

    /** A legacy full edition with a bit of everything in node.db. */
    private fun legacy(): SqlDb = db().apply {
        execute(
            "INSERT INTO outbox (dedupe_key, peer, body, sim_slot, device_time, created_at, state, attempts, direction, backfill) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf("k1", "10086", "余额 12 元", 1L, 100L, 101L, "pending", 0L, "in", 0L),
        )
        execute(
            "INSERT INTO outbox (dedupe_key, peer, body, sim_slot, device_time, created_at, state, attempts, last_error, finished_at, direction, backfill, card_number) " +
                "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf("k2", "+8613800000000", "好的", 200L, 201L, "rejected", 3L, "bad_request", 300L, "out", 1L, "13900000000"),
        )
        execute("INSERT INTO recent_peers (peer_key, last_seen_at) VALUES (?, ?)", listOf("8613800000000", 500L))
        execute(
            "INSERT INTO send_tasks (task_id, recipient, mode, state, created_at, updated_at, body_hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
            listOf("t1", "10086", "reply", "delivered", 1L, 2L, "abc"),
        )
        execute(
            "INSERT INTO sms_trash (address, body, date, date_sent, type, read, sub_id, origin, deleted_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)",
            listOf("95588", "收入 100 元", 10L, 9L, 1L, 0L, "platform", 20L),
        )
        // The user switched the scam category off and added a keyword.
        execute("DELETE FROM block_rules WHERE kind = 'fraud'")
        execute("INSERT INTO block_rules (kind, value, created_at) VALUES (?, ?, ?)", listOf("keyword", "贷款", 5L))
        execute(
            "INSERT INTO sms_blocked (address, body, date, date_sent, sub_id, reason, detail, blocked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            listOf("1069000", "点击领取", 30L, 29L, 2L, "keyword", "领取", 31L),
        )
    }

    private val prefs = mapOf(
        "server_url" to "https://sms.example.com",
        "device_id" to "dev_1",
        "needs_pairing" to false,
        "send_limit_per_hour" to 20,
        "sent_cursor" to 1234L,
        "manual_sim_numbers" to setOf("1=13800000000", "2=13900000000"),
        "nothing" to null,
    )

    private fun snapshot() = MigrationSnapshot.parse(MigrationSnapshot.export(legacy(), prefs, "tok_secret", "0.6.0"))

    private fun rows(db: SqlDb, sql: String) = db.query(sql) { r -> (0..1).map { r.stringOrNull(it) } }

    @Test
    fun `settings and token survive the round trip with their types`() {
        val s = snapshot()
        assertEquals(Schema.LATEST, s.schema)
        assertEquals("0.6.0", s.version)
        assertEquals("tok_secret", s.token)
        assertEquals(prefs.filterValues { it != null }, s.prefs)
    }

    @Test
    fun `moving the pairing brings everything`() {
        val target = db()
        val counts = MigrationSnapshot.importTables(target, snapshot(), movingPairing = true)

        assertEquals(mapOf("outbox" to 2, "recent_peers" to 1, "send_tasks" to 1, "sms_trash" to 1, "block_rules" to 2, "sms_blocked" to 1), counts)
        val outbox = target.query(
            "SELECT dedupe_key, sim_slot, state, attempts, last_error, finished_at, direction, backfill, card_number FROM outbox ORDER BY dedupe_key",
        ) { r -> listOf(r.string(0), r.longOrNull(1), r.string(2), r.long(3), r.stringOrNull(4), r.longOrNull(5), r.string(6), r.long(7), r.stringOrNull(8)) }
        assertEquals(
            listOf(
                listOf("k1", 1L, "pending", 0L, null, null, "in", 0L, null),
                listOf("k2", null, "rejected", 3L, "bad_request", 300L, "out", 1L, "13900000000"),
            ),
            outbox,
        )
        assertEquals(listOf(listOf("8613800000000", "500")), rows(target, "SELECT peer_key, last_seen_at FROM recent_peers"))
        assertEquals(listOf(listOf("t1", "abc")), rows(target, "SELECT task_id, body_hash FROM send_tasks"))
        val trash = target.query("SELECT body, sub_id, read FROM sms_trash") { r -> Triple(r.string(0), r.longOrNull(1), r.long(2)) }
        assertEquals(listOf(Triple("收入 100 元", null, 0L)), trash)
    }

    @Test
    fun `keeping the own pairing brings only the recycle bin and interception`() {
        val target = db()
        target.execute(
            "INSERT INTO outbox (dedupe_key, peer, body, device_time, created_at) VALUES (?, ?, ?, ?, ?)",
            listOf("mine", "10010", "自己的", 1L, 1L),
        )
        val counts = MigrationSnapshot.importTables(target, snapshot(), movingPairing = false)

        assertEquals(setOf("sms_trash", "block_rules", "sms_blocked"), counts.keys)
        assertEquals(listOf(listOf("mine", null)), rows(target, "SELECT dedupe_key, NULL FROM outbox"))
        assertEquals(0L, target.query("SELECT COUNT(*) FROM recent_peers") { it.long(0) }.single())
        assertEquals(listOf(listOf("点击领取", "keyword")), rows(target, "SELECT body, reason FROM sms_blocked"))
    }

    @Test
    fun `interception rules are replaced, so a category switched off stays off`() {
        val target = db() // fresh: the built-in defaults switch gambling and fraud on
        MigrationSnapshot.importTables(target, snapshot(), movingPairing = false)
        val rules = target.query("SELECT kind, value FROM block_rules ORDER BY kind") { r -> r.string(0) to r.string(1) }
        assertEquals(listOf("gambling" to "on", "keyword" to "贷款"), rules)
    }

    @Test
    fun `importing twice does not duplicate the queue`() {
        val target = db()
        MigrationSnapshot.importTables(target, snapshot(), movingPairing = true)
        val again = MigrationSnapshot.importTables(target, snapshot(), movingPairing = true)
        assertEquals(0, again["outbox"])
        assertEquals(2L, target.query("SELECT COUNT(*) FROM outbox") { it.long(0) }.single())
    }

    @Test
    fun `no token exports as null`() {
        assertNull(MigrationSnapshot.parse(MigrationSnapshot.export(legacy(), emptyMap<String, Any>(), null, "0.6.0")).token)
    }

    @Test
    fun `an unknown format is refused`() {
        assertFailsWith<IllegalArgumentException> { MigrationSnapshot.parse("""{"format":99}""") }
    }
}
