package app.nya.smsforward.node.node

import app.nya.smsforward.node.MemorySettings
import app.nya.smsforward.node.newOutbox
import app.nya.smsforward.node.pairedSettings
import app.nya.smsforward.node.sms.DedupeKey
import app.nya.smsforward.node.sms.SmsPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncomingHandlerTest {
    private val now = 1_789_830_000_000L
    private val box = newOutbox()

    private fun handler(settings: NodeSettings = pairedSettings()) = IncomingHandler(box, settings, { now })

    @Test
    fun `a received SMS is queued with the documented dedupe key`() {
        val queued = handler().onReceived(listOf(SmsPart("106901234", "验证码 583921", now - 5_000)), simSlot = 2)

        assertEquals(1, queued)
        val item = box.pending(10).single()
        assertEquals("106901234", item.peer)
        assertEquals("验证码 583921", item.body)
        assertEquals(2, item.simSlot)
        assertEquals(now - 5_000, item.deviceTime)
        assertEquals(DedupeKey.compute("d_test", 2, "106901234", now - 5_000, "验证码 583921"), item.dedupeKey)
    }

    @Test
    fun `nothing is collected before the phone has been paired`() {
        val queued = handler(MemorySettings()).onReceived(listOf(SmsPart("106", "x", now)), 1)
        assertEquals(0, queued)
        assertEquals(0, box.pendingCount())
    }

    @Test
    fun `receiving continues after the token is revoked, so nothing is lost`() {
        val revoked = pairedSettings().apply { needsPairing = true }
        assertEquals(1, handler(revoked).onReceived(listOf(SmsPart("106", "x", now)), 1))
        assertEquals(1, box.pendingCount())
    }

    @Test
    fun `the same broadcast delivered twice is queued once`() {
        val parts = listOf(SmsPart("106901234", "验证码 583921", now - 1000))
        assertEquals(1, handler().onReceived(parts, 1))
        assertEquals(0, handler().onReceived(parts, 1))
        assertEquals(1, box.pendingCount())
    }

    @Test
    fun `the same text from the same sender at another moment is a different message`() {
        val h = handler()
        h.onReceived(listOf(SmsPart("106901234", "验证码 583921", now - 60_000)), 1)
        h.onReceived(listOf(SmsPart("106901234", "验证码 583921", now - 1_000)), 1)
        assertEquals(2, box.pendingCount())
    }

    @Test
    fun `an unknown SIM slot is reported as null and keyed as slot zero`() {
        handler().onReceived(listOf(SmsPart("106901234", "hi", now)), simSlot = null)
        val item = box.pending(1).single()
        assertEquals(null, item.simSlot)
        assertEquals(DedupeKey.compute("d_test", 0, "106901234", now, "hi"), item.dedupeKey)
    }

    @Test
    fun `nonsense timestamps fall back to now`() {
        val h = handler()
        h.onReceived(listOf(SmsPart("a1", "zero", 0), SmsPart("a2", "future", now + 10 * 60_000)), 1)
        h.onReceived(listOf(SmsPart("a3", "slightly ahead is fine", now + 60_000)), 1)
        val times = box.pending(10).associate { it.body to it.deviceTime }
        assertEquals(now, times["zero"])
        assertEquals(now, times["future"])
        assertEquals(now + 60_000, times["slightly ahead is fine"], "a clock a minute ahead is normal and kept")
    }

    @Test
    fun `numbers become recent peers for reply-only mode, sender names never do`() {
        val h = handler()
        h.onReceived(listOf(SmsPart("+86 138 0000 0000", "hi", now)), 1)
        h.onReceived(listOf(SmsPart("106901234", "code", now + 1)), 1)
        h.onReceived(listOf(SmsPart("示例银行", "balance", now + 2)), 1)

        assertEquals(setOf("13800000000", "106901234"), box.recentPeers(since = 0))
        assertFalse("示例银行" in box.recentPeers(0))
        assertTrue(box.pendingCount() == 3, "all three are still reported")
    }

    @Test
    fun `a multipart SMS is queued as one message`() {
        handler().onReceived(listOf(SmsPart("106", "part one, ", now), SmsPart("106", "part two", now)), 1)
        assertEquals("part one, part two", box.pending(10).single().body)
    }
}
