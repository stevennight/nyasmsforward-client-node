package app.nya.smsforward.node.send

import app.nya.smsforward.node.MemorySettings
import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.LedgerState
import app.nya.smsforward.node.data.Schema
import app.nya.smsforward.node.data.SqliteOutbox
import app.nya.smsforward.node.data.SqliteSendLedger
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.policy.SendPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR = 60L * 60 * 1000
private const val DAY = 24 * HOUR
private const val NOW = 1_800_000_000_000L

private fun task(
    id: String = "t_1",
    mode: TaskMode = TaskMode.REPLY,
    to: String = "+8613800000000",
    slot: Int? = 1,
    expiresAt: Long = NOW + 10 * 60_000,
) = SendTask(id, mode, slot, to, "TD", expiresAt)

/** Gate + ledger + outbox on a real SQLite, with the clock under the test's control. */
private class Rig(policy: SendPolicy, limit: Int = 10) {
    var now = NOW
    val settings = MemorySettings().apply { sendPolicy = policy; sendLimitPerHour = limit }
    val db = JdbcSqlDb()
    val outbox = SqliteOutbox(db)
    val ledger = SqliteSendLedger(db)
    val gate = SendGate(settings, outbox, ledger) { now }
    fun heardFrom(number: String, at: Long = NOW) = outbox.touchPeer(app.nya.smsforward.node.sms.PeerKey.normalize(number), at)
}

class SendGateTest {
    @Test
    fun `off rejects everything`() {
        val r = Rig(SendPolicy.OFF)
        r.heardFrom("13800000000")
        assertEquals(GateDecision.Reject(SendError.POLICY_DENIED), r.gate.check(task()))
        assertEquals(GateDecision.Reject(SendError.POLICY_DENIED), r.gate.check(task(mode = TaskMode.NEW)))
    }

    @Test
    fun `reply only answers numbers that messaged us recently, however the server formats them`() {
        val r = Rig(SendPolicy.REPLY)
        r.heardFrom("13800000000")
        assertEquals(GateDecision.Allow, r.gate.check(task(to = "+8613800000000")))
        assertEquals(GateDecision.Allow, r.gate.check(task(to = "138 0000 0000")))
        assertEquals(GateDecision.Reject(SendError.RECIPIENT_NOT_RECENT), r.gate.check(task(to = "13900000000")))
    }

    @Test
    fun `reply only forgets numbers after 7 days`() {
        val r = Rig(SendPolicy.REPLY)
        r.heardFrom("13800000000", at = NOW - 6 * DAY)
        assertEquals(GateDecision.Allow, r.gate.check(task()))
        r.now = NOW + 2 * DAY // the last message is now 8 days old
        assertEquals(GateDecision.Reject(SendError.RECIPIENT_NOT_RECENT), r.gate.check(task(expiresAt = r.now + 60_000)))
    }

    @Test
    fun `reply only never sends a task the server calls new, even to a recent number`() {
        val r = Rig(SendPolicy.REPLY)
        r.heardFrom("13800000000")
        assertEquals(GateDecision.Reject(SendError.POLICY_DENIED), r.gate.check(task(mode = TaskMode.NEW)))
    }

    @Test
    fun `named senders can never be sent to`() {
        val r = Rig(SendPolicy.REPLY)
        r.outbox.touchPeer("examplebank", NOW)
        assertEquals(GateDecision.Reject(SendError.RECIPIENT_NOT_RECENT), r.gate.check(task(to = "ExampleBank")))
        val any = Rig(SendPolicy.ANY)
        assertEquals(GateDecision.Reject(SendError.POLICY_DENIED), any.gate.check(task(mode = TaskMode.NEW, to = "ExampleBank")))
    }

    @Test
    fun `any allows new messages to any number`() {
        val r = Rig(SendPolicy.ANY)
        assertEquals(GateDecision.Allow, r.gate.check(task(mode = TaskMode.NEW, to = "13900000000")))
    }

    @Test
    fun `expired tasks are dropped whatever the policy`() {
        val r = Rig(SendPolicy.ANY)
        assertEquals(GateDecision.Reject(SendError.EXPIRED), r.gate.check(task(expiresAt = NOW)))
        assertEquals(GateDecision.Reject(SendError.EXPIRED), r.gate.check(task(expiresAt = NOW - 1)))
    }

    @Test
    fun `the hourly limit counts what this phone started, in a rolling hour`() {
        val r = Rig(SendPolicy.ANY, limit = 2)
        r.ledger.begin("a", "1", "reply", NOW - 30 * 60_000)
        r.ledger.begin("b", "1", "reply", NOW - 10 * 60_000)
        assertEquals(GateDecision.Reject(SendError.RATE_LIMITED), r.gate.check(task(mode = TaskMode.NEW)))
        r.now = NOW + 35 * 60_000 // "a" is now more than an hour old
        assertEquals(GateDecision.Allow, r.gate.check(task(mode = TaskMode.NEW, expiresAt = r.now + 60_000)))
    }

    @Test
    fun `refused tasks do not use up the limit`() {
        val r = Rig(SendPolicy.ANY, limit = 1)
        r.ledger.reject("x", "1", "reply", SendError.POLICY_DENIED, NOW)
        assertEquals(GateDecision.Allow, r.gate.check(task(mode = TaskMode.NEW)))
    }
}

class SendLedgerTest {
    private val ledger = SqliteSendLedger(JdbcSqlDb())

    @Test
    fun `a task id is only ever accepted once`() {
        assertTrue(ledger.begin("t", "13800000000", "reply", NOW))
        assertFalse(ledger.begin("t", "13800000000", "reply", NOW + 1))
        assertFalse(ledger.reject("t", "13800000000", "reply", SendError.POLICY_DENIED, NOW + 1))
        assertEquals(LedgerState.SENDING, ledger.find("t")?.state)
    }

    @Test
    fun `state only moves forward`() {
        ledger.begin("t", "1", "reply", NOW)
        assertFalse(ledger.update("t", ReceiptStatus.DELIVERED, null, NOW), "cannot deliver what was never sent")
        assertTrue(ledger.update("t", ReceiptStatus.SENT, null, NOW + 1))
        assertFalse(ledger.update("t", ReceiptStatus.SENT, null, NOW + 2), "a repeated callback changes nothing")
        assertTrue(ledger.update("t", ReceiptStatus.DELIVERED, null, NOW + 3))
        assertFalse(ledger.update("t", ReceiptStatus.FAILED, "x", NOW + 4), "delivered is final")
        assertFalse(ledger.update("t", ReceiptStatus.SENT, null, NOW + 5), "and never goes back")
        assertEquals(LedgerState.DELIVERED, ledger.find("t")?.state)
    }

    @Test
    fun `a failed delivery report after sent is recorded`() {
        ledger.begin("t", "1", "reply", NOW)
        ledger.update("t", ReceiptStatus.SENT, null, NOW + 1)
        assertTrue(ledger.update("t", ReceiptStatus.FAILED, SendError.DELIVERY_FAILED, NOW + 2))
        assertEquals(SendError.DELIVERY_FAILED, ledger.find("t")?.error)
    }

    @Test
    fun `receipts to replay are the settled tasks of the window, oldest first`() {
        ledger.begin("sending", "1", "reply", NOW)
        ledger.begin("old", "1", "reply", NOW - 2 * DAY)
        ledger.update("old", ReceiptStatus.SENT, null, NOW - 2 * DAY)
        ledger.begin("a", "1", "reply", NOW)
        ledger.update("a", ReceiptStatus.SENT, null, NOW + 1)
        ledger.reject("b", "1", "reply", SendError.EXPIRED, NOW + 2)
        val receipts = ledger.receiptsSince(NOW - DAY, 50)
        assertEquals(
            listOf(SendReceipt("a", ReceiptStatus.SENT), SendReceipt("b", ReceiptStatus.FAILED, SendError.EXPIRED)),
            receipts,
        )
    }

    @Test
    fun `stale sending tasks are found by age`() {
        ledger.begin("t", "1", "reply", NOW)
        assertEquals(emptyList(), ledger.staleSending(NOW))
        assertEquals(listOf("t"), ledger.staleSending(NOW + 1))
    }

    @Test
    fun `prune keeps recent rows and anything still sending`() {
        ledger.begin("old", "1", "reply", NOW - 40 * DAY)
        ledger.update("old", ReceiptStatus.SENT, null, NOW - 40 * DAY)
        ledger.begin("stuck", "1", "reply", NOW - 40 * DAY)
        ledger.begin("new", "1", "reply", NOW)
        ledger.prune(NOW)
        assertNull(ledger.find("old"))
        assertNotNull(ledger.find("stuck"))
        assertNotNull(ledger.find("new"))
    }

    @Test
    fun `a database from the first release, whose version was bumped without its tables, is repaired`() {
        // SQLiteOpenHelper used to set user_version = 1 by itself, so Schema thought the tables existed.
        val db = JdbcSqlDb()
        db.version = 1
        Schema.migrate(db)
        assertEquals(Schema.LATEST, db.version)
        SqliteOutbox(db).touchPeer("p", NOW)
        assertTrue(SqliteSendLedger(db).begin("t", "1", "reply", NOW))
    }

    @Test
    fun `a version 1 database with data gains the new table and keeps its data`() {
        val db = JdbcSqlDb()
        db.execute("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, dedupe_key TEXT NOT NULL UNIQUE, peer TEXT NOT NULL, body TEXT NOT NULL, sim_slot INTEGER, device_time INTEGER NOT NULL, created_at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, finished_at INTEGER)")
        db.execute("CREATE TABLE recent_peers (peer_key TEXT PRIMARY KEY, last_seen_at INTEGER NOT NULL)")
        db.execute("INSERT INTO outbox (dedupe_key, peer, body, device_time, created_at) VALUES ('k', 'p', 'b', 1, 1)")
        db.version = 1
        val box = SqliteOutbox(db)
        assertEquals(1, box.pendingCount())
        assertTrue(SqliteSendLedger(db).begin("t", "1", "reply", NOW))
    }
}

class SimResolverTest {
    private val sims = listOf(SimInfo(1, 11, "A"), SimInfo(2, 22, "B"), SimInfo(3, null, "C"))

    @Test
    fun `no slot means the default SIM`() {
        assertEquals(SimChoice.Default, SimResolver.resolve(null, sims, canReadSims = true))
        assertEquals(SimChoice.Default, SimResolver.resolve(null, emptyList(), canReadSims = false))
    }

    @Test
    fun `the slot of the task picks the subscription, and there is never a fallback to the other card`() {
        assertEquals(SimChoice.Subscription(22), SimResolver.resolve(2, sims, true))
        assertEquals(SimChoice.Unavailable, SimResolver.resolve(2, sims.filter { it.slot == 1 }, true))
        assertEquals(SimChoice.Unavailable, SimResolver.resolve(3, sims, true), "a slot without a subscription id cannot send")
        assertEquals(SimChoice.Unavailable, SimResolver.resolve(4, sims, true))
    }

    @Test
    fun `a named slot needs the permission to read SIMs`() {
        assertEquals(SimChoice.NoPermission, SimResolver.resolve(1, emptyList(), canReadSims = false))
    }
}

class SendStatusTrackerTest {
    private val t = SendStatusTracker()

    @Test
    fun `a single part is sent then delivered`() {
        assertEquals(SendStatusTracker.Outcome.Sent("t"), t.onPartSent("t", 0, 1, null))
        assertEquals(SendStatusTracker.Outcome.Delivered("t"), t.onPartDelivered("t", 0, 1, true))
    }

    @Test
    fun `a multipart message is sent only when every part is`() {
        assertNull(t.onPartSent("t", 0, 3, null))
        assertNull(t.onPartSent("t", 2, 3, null))
        assertNull(t.onPartSent("t", 2, 3, null), "a repeated callback does not count twice")
        assertEquals(SendStatusTracker.Outcome.Sent("t"), t.onPartSent("t", 1, 3, null))
        assertNull(t.onPartSent("t", 1, 3, null), "and produces no second verdict")
    }

    @Test
    fun `one failed part fails the task once and the rest is ignored`() {
        assertNull(t.onPartSent("t", 0, 2, null))
        assertEquals(SendStatusTracker.Outcome.Failed("t", SendError.RADIO_ERROR), t.onPartSent("t", 1, 2, SendError.RADIO_ERROR))
        assertNull(t.onPartSent("t", 0, 2, null))
        assertNull(t.onPartDelivered("t", 0, 2, true))
    }

    @Test
    fun `delivered needs every part, and a failed report is a failure`() {
        t.onPartSent("t", 0, 2, null)
        t.onPartSent("t", 1, 2, null)
        assertNull(t.onPartDelivered("t", 0, 2, true))
        assertEquals(SendStatusTracker.Outcome.Delivered("t"), t.onPartDelivered("t", 1, 2, true))

        t.onPartSent("u", 0, 1, null)
        assertEquals(SendStatusTracker.Outcome.Failed("u", SendError.DELIVERY_FAILED), t.onPartDelivered("u", 0, 1, false))
    }

    @Test
    fun `progress is rebuilt from the intents after a restart`() {
        // A fresh tracker (new process) that first hears about part 1 of 2 must still be able to finish the task.
        assertNull(t.onPartSent("t", 1, 2, null))
        assertEquals(SendStatusTracker.Outcome.Sent("t"), t.onPartSent("t", 0, 2, null))
    }

    @Test
    fun `radio result codes map to the documented errors`() {
        val ok = -1 // Activity.RESULT_OK
        assertNull(SendStatusTracker.errorFor(ok, ok))
        assertEquals(SendError.RADIO_ERROR, SendStatusTracker.errorFor(1, ok)) // generic failure
        assertEquals(SendError.RADIO_ERROR, SendStatusTracker.errorFor(2, ok)) // radio off
        assertEquals(SendError.RADIO_ERROR, SendStatusTracker.errorFor(4, ok)) // no service
        assertEquals(SendError.RATE_LIMITED, SendStatusTracker.errorFor(5, ok)) // Android's own limit
    }

    @Test
    fun `delivery report status bytes are read per the GSM spec`() {
        assertEquals(true, SendStatusTracker.deliveryOk(0x00))
        assertEquals(true, SendStatusTracker.deliveryOk(0x1F))
        assertNull(SendStatusTracker.deliveryOk(0x20)) // still trying
        assertNull(SendStatusTracker.deliveryOk(0x3F))
        assertEquals(false, SendStatusTracker.deliveryOk(0x40))
        assertEquals(false, SendStatusTracker.deliveryOk(0x60))
    }
}

private class FakeSender(
    var allowed: Boolean = true,
    var readSims: Boolean = true,
    var sims: List<SimInfo> = listOf(SimInfo(1, 11, "A"), SimInfo(2, 22, "B")),
) : SmsSender {
    val sent = mutableListOf<Pair<SendTask, SimChoice>>()
    var failWith: Exception? = null
    override fun canSend() = allowed
    override fun canReadSims() = readSims
    override fun activeSims() = sims
    override fun send(task: SendTask, sim: SimChoice) {
        failWith?.let { throw it }
        sent += task to sim
    }
}

class SendCoordinatorTest {
    private var now = NOW
    private val settings = MemorySettings().apply { sendPolicy = SendPolicy.REPLY }
    private val db = JdbcSqlDb()
    private val outbox = SqliteOutbox(db)
    private val ledger = SqliteSendLedger(db)
    private val sender = FakeSender()
    private val receipts = mutableListOf<SendReceipt>()
    private val coordinator = SendCoordinator(SendGate(settings, outbox, ledger) { now }, ledger, sender, SendStatusTracker(), { now }) { receipts += it }

    init {
        outbox.touchPeer("13800000000", NOW)
    }

    @Test
    fun `an allowed reply goes out on the original SIM and is reported as sent then delivered`() {
        coordinator.onTask(task(slot = 2))
        assertEquals(listOf(SimChoice.Subscription(22)), sender.sent.map { it.second })
        assertEquals(emptyList(), receipts, "nothing is reported until the radio answers")

        coordinator.onPartSent("t_1", 0, 1, null)
        coordinator.onPartDelivered("t_1", 0, 1, true)
        assertEquals(listOf(SendReceipt("t_1", ReceiptStatus.SENT), SendReceipt("t_1", ReceiptStatus.DELIVERED)), receipts)
    }

    @Test
    fun `the same task offered again is never sent twice, it gets the known answer`() {
        coordinator.onTask(task())
        coordinator.onPartSent("t_1", 0, 1, null)
        receipts.clear()

        coordinator.onTask(task()) // the server re-offers after a reconnect
        assertEquals(1, sender.sent.size)
        assertEquals(listOf(SendReceipt("t_1", ReceiptStatus.SENT)), receipts)
    }

    @Test
    fun `a task offered again while still sending is ignored`() {
        coordinator.onTask(task())
        coordinator.onTask(task())
        assertEquals(1, sender.sent.size)
        assertEquals(emptyList(), receipts)
    }

    @Test
    fun `refusals are reported with their reason and remembered`() {
        coordinator.onTask(task(id = "a", to = "13900000000")) // not a recent number
        coordinator.onTask(task(id = "b", expiresAt = NOW - 1))
        coordinator.onTask(task(id = "c", mode = TaskMode.NEW))
        assertEquals(
            listOf(
                SendReceipt("a", ReceiptStatus.FAILED, SendError.RECIPIENT_NOT_RECENT),
                SendReceipt("b", ReceiptStatus.FAILED, SendError.EXPIRED),
                SendReceipt("c", ReceiptStatus.FAILED, SendError.POLICY_DENIED),
            ),
            receipts,
        )
        assertTrue(sender.sent.isEmpty())
        receipts.clear()
        coordinator.onTask(task(id = "a", to = "13900000000")) // offered again: same answer, still nothing sent
        assertEquals(listOf(SendReceipt("a", ReceiptStatus.FAILED, SendError.RECIPIENT_NOT_RECENT)), receipts)
    }

    @Test
    fun `missing permissions and a missing SIM are reported, and never fall back to the other card`() {
        sender.allowed = false
        coordinator.onTask(task(id = "p"))
        sender.allowed = true
        sender.sims = listOf(SimInfo(1, 11, "A"))
        coordinator.onTask(task(id = "s", slot = 2))
        sender.readSims = false
        coordinator.onTask(task(id = "r", slot = 1))
        assertEquals(
            listOf(SendError.NO_PERMISSION, SendError.SIM_UNAVAILABLE, SendError.NO_PERMISSION),
            receipts.map { it.error },
        )
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a task without a slot uses the default SIM, which needs no SIM permission`() {
        sender.readSims = false
        coordinator.onTask(task(slot = null))
        assertEquals(listOf(SimChoice.Default), sender.sent.map { it.second })
    }

    @Test
    fun `a radio that refuses at once fails the task and it stays failed`() {
        sender.failWith = IllegalStateException("radio off")
        coordinator.onTask(task())
        assertEquals(listOf(SendReceipt("t_1", ReceiptStatus.FAILED, SendError.RADIO_ERROR)), receipts)
        assertEquals(LedgerState.FAILED, ledger.find("t_1")?.state)
    }

    @Test
    fun `a failed part is reported once`() {
        coordinator.onTask(task())
        coordinator.onPartSent("t_1", 0, 1, SendError.RADIO_ERROR)
        coordinator.onPartSent("t_1", 0, 1, SendError.RADIO_ERROR)
        assertEquals(listOf(SendReceipt("t_1", ReceiptStatus.FAILED, SendError.RADIO_ERROR)), receipts)
    }

    @Test
    fun `the hourly limit is enforced on the phone`() {
        settings.sendLimitPerHour = 2
        for (i in 1..3) coordinator.onTask(task(id = "t$i"))
        assertEquals(2, sender.sent.size)
        assertEquals(SendReceipt("t3", ReceiptStatus.FAILED, SendError.RATE_LIMITED), receipts.last())
    }

    @Test
    fun `settled receipts are replayed after a reconnect, sending ones are not`() {
        coordinator.onTask(task(id = "a"))
        coordinator.onPartSent("a", 0, 1, null)
        coordinator.onTask(task(id = "b")) // still sending
        assertEquals(listOf(SendReceipt("a", ReceiptStatus.SENT)), coordinator.receiptsToReplay())
    }

    @Test
    fun `a task left sending by a dead process is settled as failed, not sent again`() {
        coordinator.onTask(task())
        now += 3 * 60_000
        val settled = coordinator.settleStale()
        assertEquals(listOf(SendReceipt("t_1", ReceiptStatus.FAILED, SendError.RADIO_ERROR)), settled)
        coordinator.onTask(task(expiresAt = now + 60_000)) // offered again: known, so not sent
        assertEquals(1, sender.sent.size)
    }
}
