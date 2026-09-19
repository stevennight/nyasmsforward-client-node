package app.nya.smsforward.node.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SendPolicyTest {
    @Test
    fun `unknown or missing values fall back to the safest policy`() {
        assertEquals(SendPolicy.OFF, SendPolicy.fromWire(null))
        assertEquals(SendPolicy.OFF, SendPolicy.fromWire(""))
        assertEquals(SendPolicy.OFF, SendPolicy.fromWire("everything"))
        assertEquals(SendPolicy.REPLY, SendPolicy.fromWire("reply"))
        assertEquals(SendPolicy.ANY, SendPolicy.fromWire("any"))
    }

    @Test
    fun `wire names round trip`() {
        for (p in SendPolicy.entries) assertEquals(p, SendPolicy.fromWire(p.wire))
    }

    @Test
    fun `the stricter layer wins`() {
        assertEquals(SendPolicy.OFF, SendPolicy.stricter(SendPolicy.OFF, SendPolicy.ANY))
        assertEquals(SendPolicy.OFF, SendPolicy.stricter(SendPolicy.ANY, SendPolicy.OFF))
        assertEquals(SendPolicy.REPLY, SendPolicy.stricter(SendPolicy.ANY, SendPolicy.REPLY))
        assertEquals(SendPolicy.REPLY, SendPolicy.stricter(SendPolicy.REPLY, SendPolicy.ANY))
        assertEquals(SendPolicy.ANY, SendPolicy.stricter(SendPolicy.ANY, SendPolicy.ANY))
    }

    @Test
    fun `off is the strictest and any the loosest`() {
        assertFalse(SendPolicy.entries.any { it.ordinal < SendPolicy.OFF.ordinal })
        assertEquals(SendPolicy.ANY, SendPolicy.entries.last())
    }
}
