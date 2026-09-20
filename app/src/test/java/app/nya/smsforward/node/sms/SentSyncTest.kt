package app.nya.smsforward.node.sms

import app.nya.smsforward.node.MemorySettings
import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.Schema
import app.nya.smsforward.node.data.SqliteOutbox
import app.nya.smsforward.node.data.SqliteSendLedger
import app.nya.smsforward.node.send.BodyHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 1_800_000_000_000L
private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN

private class FakeBox : SmsBox {
    val sent = mutableListOf<SystemSms>()
    val inbox = mutableListOf<SystemSms>()
    override fun maxSentId() = sent.maxOfOrNull { it.id } ?: 0L
    override fun sentAfter(afterId: Long, limit: Int) = sent.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
    override fun inboxSince(sinceMillis: Long, limit: Int) = inbox.filter { it.time >= sinceMillis }.sortedBy { it.time }
    override fun sentSince(sinceMillis: Long, limit: Int) = sent.filter { it.time >= sinceMillis }.sortedBy { it.time }
}

private class Rig(syncSent: Boolean = true, backfillDays: Int = 0) {
    val settings = MemorySettings().apply {
        deviceId = "d_test"
        this.syncSent = syncSent
        this.backfillDays = backfillDays
    }
    val db = JdbcSqlDb()
    val outbox = SqliteOutbox(db)
    val ledger = SqliteSendLedger(db)
    val box = FakeBox()
    var now = NOW
    val sync = SentSync(settings, box, outbox, ledger) { now }
    fun sms(id: Long, address: String, body: String, time: Long = NOW, slot: Int? = 1) = SystemSms(id, address, body, time, slot)
    fun pending() = outbox.pending(100)
}

class SentSyncTest {
    @Test
    fun `nothing is read while the option is off`() {
        val r = Rig(syncSent = false)
        r.box.sent += r.sms(1, "13800000000", "hi")
        assertEquals(0, r.sync.syncSent())
        assertEquals(0, r.sync.backfill())
        assertEquals(-1L, r.settings.sentCursor, "the cursor is untouched")
    }

    @Test
    fun `the first pass only notes where the sent box ends, old messages are not reported`() {
        val r = Rig()
        r.box.sent += listOf(r.sms(1, "13800000000", "old"), r.sms(2, "13800000000", "older"))
        assertEquals(0, r.sync.syncSent())
        assertEquals(2L, r.settings.sentCursor)
        assertTrue(r.pending().isEmpty())

        r.box.sent += r.sms(3, "+86 138 0000 0000", "我到了")
        assertEquals(1, r.sync.syncSent())
        val item = r.pending().single()
        assertEquals("out", item.direction)
        assertFalse(item.backfill)
        assertEquals("+86 138 0000 0000", item.peer, "the recipient as the SMS app wrote it")
        assertEquals(3L, r.settings.sentCursor)
        assertEquals(0, r.sync.syncSent(), "nothing new: nothing queued")
    }

    @Test
    fun `sent messages get the same dedupe key however often they are read`() {
        val r = Rig()
        r.settings.sentCursor = 0
        r.box.sent += r.sms(1, "13800000000", "hello", time = NOW - MIN)
        assertEquals(1, r.sync.syncSent())
        r.settings.sentCursor = 0 // e.g. the cursor was lost
        assertEquals(0, r.sync.syncSent(), "the outbox refuses the same message twice")
        assertEquals(1, r.pending().size)
    }

    @Test
    fun `what the platform sent through a task is not reported again`() {
        val r = Rig()
        r.settings.sentCursor = 0
        r.ledger.begin("t_1", "13800000000", "reply", NOW - MIN, BodyHash.of("TD"))
        r.box.sent += listOf(
            r.sms(1, "+8613800000000", "TD", time = NOW - MIN + 20_000), // the task's SMS, seen in the sent box
            r.sms(2, "13800000000", "TD", time = NOW + 20 * MIN), // the same words 20 minutes later: typed by the user
            r.sms(3, "13800000000", "something else", time = NOW),
        )
        assertEquals(2, r.sync.syncSent())
        assertEquals(listOf("TD", "something else"), r.pending().map { it.body })
        assertEquals(3L, r.settings.sentCursor, "the skipped message still moves the cursor")
    }

    @Test
    fun `a task that failed sent nothing, so its text in the sent box is a real message`() {
        val r = Rig()
        r.settings.sentCursor = 0
        r.ledger.reject("t_1", "13800000000", "reply", "policy_denied", NOW)
        r.box.sent += r.sms(1, "13800000000", "TD", time = NOW)
        assertEquals(1, r.sync.syncSent())
    }

    @Test
    fun `history is reported once, quietly, both directions, within the chosen days`() {
        val r = Rig(backfillDays = 7)
        r.box.inbox += listOf(r.sms(1, "106900", "旧验证码 111111", time = NOW - 3 * DAY), r.sms(2, "106900", "太旧了", time = NOW - 20 * DAY))
        r.box.sent += r.sms(10, "13800000000", "旧的发出", time = NOW - 2 * DAY)
        assertEquals(2, r.sync.backfill())
        val items = r.pending()
        assertEquals(listOf("in" to true, "out" to true), items.map { it.direction to it.backfill })
        assertEquals(7, r.settings.backfilledDays)
        assertEquals(0, r.sync.backfill(), "not again for the same setting")

        r.settings.backfillDays = 30 // asking for more later reports the extra days (duplicates are refused by key)
        assertEquals(1, r.sync.backfill())
        assertEquals(30, r.settings.backfilledDays)
    }

    @Test
    fun `history does not widen who may be replied to`() {
        val r = Rig(backfillDays = 30)
        r.box.inbox += r.sms(1, "+86 138 0000 0000", "两周前", time = NOW - 14 * DAY)
        r.sync.backfill()
        // The number is remembered by the message's own time, so "reply only" (7 days) still refuses it.
        val recentWeek = r.outbox.recentPeers(NOW - 7 * DAY)
        assertFalse("13800000000" in recentWeek)
        assertTrue("13800000000" in r.outbox.recentPeers(NOW - 30 * DAY))
    }

    @Test
    fun `history needs the option on and a device identity`() {
        val r = Rig(syncSent = false, backfillDays = 30)
        r.box.inbox += r.sms(1, "106900", "x", time = NOW - DAY)
        assertEquals(0, r.sync.backfill())
        val unpaired = Rig(backfillDays = 7).also { it.settings.deviceId = null }
        assertEquals(0, unpaired.sync.syncSent())
    }

    @Test
    fun `a bogus future timestamp falls back to now, like received messages do`() {
        val r = Rig()
        r.settings.sentCursor = 0
        r.box.sent += r.sms(1, "13800000000", "x", time = NOW + 10 * DAY)
        r.sync.syncSent()
        assertEquals(NOW, r.pending().single().deviceTime)
    }
}

class SchemaV3Test {
    @Test
    fun `a version 2 database gains the new columns and keeps its rows`() {
        val db = JdbcSqlDb()
        // What the first M3 build created.
        db.execute("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, dedupe_key TEXT NOT NULL UNIQUE, peer TEXT NOT NULL, body TEXT NOT NULL, sim_slot INTEGER, device_time INTEGER NOT NULL, created_at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, finished_at INTEGER)")
        db.execute("CREATE TABLE recent_peers (peer_key TEXT PRIMARY KEY, last_seen_at INTEGER NOT NULL)")
        db.execute("CREATE TABLE send_tasks (task_id TEXT PRIMARY KEY, recipient TEXT NOT NULL, mode TEXT NOT NULL, state TEXT NOT NULL, error TEXT, started_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execute("INSERT INTO outbox (dedupe_key, peer, body, device_time, created_at) VALUES ('k', 'p', 'b', 1, 1)")
        db.version = 2

        val box = SqliteOutbox(db)
        assertEquals(Schema.LATEST, db.version)
        val item = box.pending(10).single()
        assertEquals("in", item.direction, "existing rows are received messages")
        assertFalse(item.backfill)
        assertTrue(SqliteSendLedger(db).begin("t", "r", "reply", 5, "hash"))
    }

    @Test
    fun `migrating twice does not fail on the columns that already exist`() {
        val db = JdbcSqlDb()
        Schema.migrate(db)
        db.version = 1 // pretend an older stamp on a database that is in fact current
        Schema.migrate(db)
        assertEquals(Schema.LATEST, db.version)
    }
}
