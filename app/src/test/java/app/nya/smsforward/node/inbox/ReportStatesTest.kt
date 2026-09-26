package app.nya.smsforward.node.inbox

import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.NewOutboxItem
import app.nya.smsforward.node.data.OutboxState
import app.nya.smsforward.node.data.ReportRecord
import app.nya.smsforward.node.data.SqliteOutbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportStatesTest {
    private val minute = 60_000L

    private fun rec(peer: String, body: String, time: Long, state: OutboxState, direction: String = "in", error: String? = null) =
        ReportRecord(peer, body, time, direction, state, error)

    private fun inbox(id: Long, address: String, body: String, date: Long, dateSent: Long = 0) =
        SmsRow(id = id, threadId = 1, address = address, body = body, date = date, type = 1, read = true, dateSent = dateSent)

    private fun sent(id: Long, address: String, body: String, date: Long) =
        SmsRow(id = id, threadId = 1, address = address, body = body, date = date, type = 2, read = true)

    @Test
    fun `matches by number, text and time`() {
        val states = ReportStates(
            listOf(
                rec("+86 138 0013 8000", "你好", 1_000 * minute, OutboxState.DONE),
                rec("13800138000", "你好", 2_000 * minute, OutboxState.PENDING),
                rec("95555", "账单", 500 * minute, OutboxState.DEAD, error = "bad_request"),
                rec("10086", "查话费", 700 * minute, OutboxState.DONE, direction = "out"),
            ),
        )
        // The queue keeps the network time; the inbox row was stored a few seconds later.
        assertEquals(OutboxState.DONE, states.of(inbox(1, "13800138000", "你好", 1_000 * minute + 3_000, dateSent = 1_000 * minute))?.state)
        // The same text again later is the other queued message, not the first one.
        assertEquals(OutboxState.PENDING, states.of(inbox(2, "13800138000", "你好", 2_000 * minute + 2_000))?.state)
        assertEquals("bad_request", states.of(inbox(3, "95555", "账单", 500 * minute))?.error)
        assertEquals(OutboxState.DONE, states.of(sent(4, "10086", "查话费", 700 * minute))?.state)
        // Too far apart in time, a different text, or the wrong direction: no state.
        assertNull(states.of(inbox(5, "13800138000", "你好", 1_500 * minute)))
        assertNull(states.of(inbox(6, "13800138000", "再见", 1_000 * minute)))
        assertNull(states.of(inbox(7, "10086", "查话费", 700 * minute)))

        assertEquals(1, states.pending("+8613800138000"))
        assertEquals(1, states.refused("95555"))
        assertEquals(0, states.pending("95555"))
    }

    @Test
    fun `the outbox hands out every kept row`() {
        val outbox = SqliteOutbox(JdbcSqlDb())
        outbox.enqueue(NewOutboxItem("k1", "13800138000", "你好", 1, 10), now = 10)
        outbox.enqueue(NewOutboxItem("k2", "10086", "查话费", null, 20, direction = "out"), now = 20)
        val ids = outbox.pending(10).associate { it.dedupeKey to it.id }
        outbox.markDone(listOf(ids.getValue("k1")), now = 30)
        val records = outbox.reportRecords().associateBy { it.body }
        assertEquals(OutboxState.DONE, records.getValue("你好").state)
        assertEquals("out", records.getValue("查话费").direction)
        assertEquals(OutboxState.PENDING, records.getValue("查话费").state)
    }
}
