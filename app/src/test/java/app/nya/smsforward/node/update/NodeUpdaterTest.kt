package app.nya.smsforward.node.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeUpdaterTest {
    @Test
    fun `stable versions parse and compare numerically`() {
        val old = parseStableVersion("0.9.12")!!
        val current = parseStableVersion("1.0.0")!!
        assertEquals("1.0.0", current.text)
        assert(old < current)
        assert(parseStableVersion("1.0.1")!! > current)
    }

    @Test
    fun `pre releases and malformed versions are rejected`() {
        assertNull(parseStableVersion("v1.2.3"))
        assertNull(parseStableVersion("1.2.3-beta"))
        assertNull(parseStableVersion("1.2"))
        assertNull(parseStableVersion("abc"))
    }
}
