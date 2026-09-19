package app.nya.smsforward.node.data

import app.nya.smsforward.node.data.AndroidSqlDb.Companion.inlineNumbers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteOutboxTest {
    private fun item(n: Int, peer: String = "106901234", slot: Int? = 1) =
        NewOutboxItem("sha256:$n", peer, "body $n", slot, 1_000L * n)

    private fun outbox() = SqliteOutbox(JdbcSqlDb())

    @Test
    fun `an SMS is queued once even if the broadcast is delivered twice`() {
        val box = outbox()
        assertTrue(box.enqueue(item(1), now = 10))
        assertFalse(box.enqueue(item(1), now = 11), "same dedupe key must not create a second row")
        assertEquals(1, box.pendingCount())
    }

    @Test
    fun `pending rows come back oldest first, with all fields, and respect the limit`() {
        val box = outbox()
        box.enqueue(item(1, slot = null), 1)
        box.enqueue(item(2, slot = 2), 2)
        box.enqueue(item(3), 3)

        val two = box.pending(2)
        assertEquals(listOf("sha256:1", "sha256:2"), two.map { it.dedupeKey })
        assertNull(two[0].simSlot)
        assertEquals(2, two[1].simSlot)
        assertEquals("body 2", two[1].body)
        assertEquals(2_000L, two[1].deviceTime)
        assertEquals(0, two[1].attempts)
    }

    @Test
    fun `finished rows leave the pending list, and dead ones keep their reason`() {
        val box = outbox()
        listOf(1, 2, 3).forEach { box.enqueue(item(it), it.toLong()) }
        val ids = box.pending(10).map { it.id }

        box.markDone(listOf(ids[0]), now = 100)
        box.markDead(ids[1], "bad_body", now = 101)
        assertEquals(listOf("sha256:3"), box.pending(10).map { it.dedupeKey })
        assertEquals(1, box.pendingCount())

        val recent = box.recent(10)
        assertEquals(listOf("body 3", "body 2", "body 1"), recent.map { it.body }, "newest first")
        assertEquals(listOf(OutboxState.PENDING, OutboxState.DEAD, OutboxState.DONE), recent.map { it.state })
        assertEquals("bad_body", recent[1].error)
        assertNull(recent[2].error)
    }

    @Test
    fun `recording attempts counts per row and marking done is a no-op for an empty list`() {
        val box = outbox()
        box.enqueue(item(1), 1)
        box.enqueue(item(2), 2)
        val ids = box.pending(10).map { it.id }
        box.recordAttempt(listOf(ids[0]))
        box.recordAttempt(ids)
        box.markDone(emptyList(), 5)
        box.recordAttempt(emptyList())
        assertEquals(listOf(2, 1), box.pending(10).map { it.attempts })
    }

    @Test
    fun `an SMS that was already reported can be re-queued safely as a duplicate`() {
        val box = outbox()
        box.enqueue(item(1), 1)
        box.markDone(box.pending(1).map { it.id }, 2)
        assertFalse(box.enqueue(item(1), 3), "a finished row still blocks the same key: it must not be uploaded again")
        assertEquals(0, box.pendingCount())
    }

    @Test
    fun `recent peers are remembered, refreshed and filtered by age`() {
        val box = outbox()
        box.touchPeer("13800000000", now = 100)
        box.touchPeer("106901234", now = 200)
        box.touchPeer("13800000000", now = 300) // refresh, not a second row

        assertEquals(setOf("13800000000", "106901234"), box.recentPeers(since = 0))
        assertEquals(setOf("13800000000"), box.recentPeers(since = 250))
        assertEquals(emptySet(), box.recentPeers(since = 400))
    }

    @Test
    fun `pruning removes old finished rows and old peers but never pending ones`() {
        val box = outbox()
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        box.enqueue(item(1), now - 60 * day) // will finish long ago
        box.enqueue(item(2), now - 60 * day) // stays pending, however old
        box.enqueue(item(3), now - day)      // finished yesterday
        val ids = box.pending(10).associateBy { it.dedupeKey }
        box.markDone(listOf(ids.getValue("sha256:1").id), now - 60 * day)
        box.markDone(listOf(ids.getValue("sha256:3").id), now - day)
        box.touchPeer("old", now - 120 * day)
        box.touchPeer("fresh", now - day)

        box.prune(now)

        assertEquals(listOf("body 3", "body 2"), box.recent(10).map { it.body })
        assertEquals(1, box.pendingCount())
        assertEquals(setOf("fresh"), box.recentPeers(since = 0))
    }

    @Test
    fun `pruning keeps at most the newest 500 finished rows`() {
        val box = outbox()
        val total = SqliteOutbox.KEEP_FINISHED + 20
        repeat(total) { box.enqueue(item(it), 1L) }
        box.markDone(box.pending(total).map { it.id }, now = 10)
        box.prune(now = 20)
        val kept = box.recent(total)
        assertEquals(SqliteOutbox.KEEP_FINISHED, kept.size)
        assertEquals("body ${total - 1}", kept.first().body)
    }

    @Test
    fun `the schema survives reopening the database and is not applied twice`() {
        val file = File.createTempFile("outbox", ".db").apply { deleteOnExit() }
        val url = "jdbc:sqlite:${file.absolutePath}"
        JdbcSqlDb(url).use { db ->
            val box = SqliteOutbox(db)
            box.enqueue(item(1), 1)
            box.touchPeer("p", 1)
        }
        JdbcSqlDb(url).use { db ->
            assertEquals(1, db.version)
            val box = SqliteOutbox(db) // opening again must not fail with "table already exists"
            assertEquals(1, box.pendingCount())
            assertEquals(setOf("p"), box.recentPeers(0))
        }
    }

    @Test
    fun `a failed transaction changes nothing`() {
        val db = JdbcSqlDb()
        val box = SqliteOutbox(db)
        box.enqueue(item(1), 1)
        assertFailsWith<IllegalStateException> {
            db.transaction {
                db.execute("DELETE FROM outbox")
                error("boom")
            }
        }
        assertEquals(1, box.pendingCount())
    }

    @Test
    fun `numbers are inlined as literals and only strings stay bound on Android`() {
        val (sql, bound) = inlineNumbers("SELECT * FROM t WHERE a = ? AND b >= ? AND c = ? LIMIT ?", listOf("x", 5L, null, 20))
        assertEquals("SELECT * FROM t WHERE a = ? AND b >= 5 AND c = NULL LIMIT 20", sql)
        assertContentEquals(arrayOf("x"), bound)
        assertFailsWith<IllegalArgumentException> { inlineNumbers("SELECT ?", emptyList()) }
    }
}
