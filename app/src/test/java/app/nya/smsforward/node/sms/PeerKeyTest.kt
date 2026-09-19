package app.nya.smsforward.node.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The vectors below are also listed in docs/协议.md §5.2; the server's implementation must satisfy the same ones. */
class PeerKeyTest {
    @Test
    fun `mainland mobile numbers collapse to the bare 11 digits`() {
        assertEquals("13800000000", PeerKey.normalize("13800000000"))
        assertEquals("13800000000", PeerKey.normalize("+8613800000000"))
        assertEquals("13800000000", PeerKey.normalize("+86 138 0000 0000"))
        assertEquals("13800000000", PeerKey.normalize("0086 138-0000-0000"))
        assertEquals("13800000000", PeerKey.normalize("8613800000000"))
    }

    @Test
    fun `a prefix is only stripped when an 11 digit mobile number remains`() {
        assertEquals("861380000000", PeerKey.normalize("861380000000")) // only 10 digits after the 86
        assertEquals("+8610088886666", PeerKey.normalize("+8610088886666")) // remainder does not start with 1 + 10 digits pattern of a mobile
    }

    @Test
    fun `other numbers only lose formatting`() {
        assertEquals("01088886666", PeerKey.normalize("(010) 8888-6666"))
        assertEquals("106901234", PeerKey.normalize("106901234"))
        assertEquals("+14155550100", PeerKey.normalize("+1 (415) 555-0100"))
    }

    @Test
    fun `alphanumeric sender ids are trimmed and lower-cased`() {
        assertEquals("examplebank", PeerKey.normalize("  ExampleBank "))
        assertEquals("示例银行", PeerKey.normalize("示例银行"))
    }

    @Test
    fun `only real numbers can be replied to`() {
        assertTrue(PeerKey.isReplyable("13800000000"))
        assertTrue(PeerKey.isReplyable("+8613800000000"))
        assertTrue(PeerKey.isReplyable("106901234"))
        assertFalse(PeerKey.isReplyable("ExampleBank"))
        assertFalse(PeerKey.isReplyable("示例银行"))
        assertFalse(PeerKey.isReplyable(""))
    }
}
