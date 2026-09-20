package app.nya.smsforward.node.node

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairLinkTest {
    @Test
    fun `the link the console shows is understood, encoded or not`() {
        val expected = PairLink("https://sms.example.com", "483920")
        assertEquals(expected, PairLink.parse("nyasmsforward://pair?server=https%3A%2F%2Fsms.example.com&code=483920"))
        assertEquals(expected, PairLink.parse("nyasmsforward://pair?server=https://sms.example.com&code=483920"))
        assertEquals(expected, PairLink.parse("  nyasmsforward://pair?code=483%20920&server=https%3A%2F%2Fsms.example.com\n"))
    }

    @Test
    fun `ports and paths in the address survive`() {
        assertEquals(
            PairLink("http://192.168.1.5:8080", "111222"),
            PairLink.parse("nyasmsforward://pair?server=http%3A%2F%2F192.168.1.5%3A8080&code=111222"),
        )
    }

    @Test
    fun `anything else is not a pairing link`() {
        for (text in listOf(
            "", "hello", "https://evil.example/pair?server=x&code=483920",
            "nyasmsforward://other?server=https://x.example&code=483920",
            "nyasmsforward://pair?code=483920", // no address
            "nyasmsforward://pair?server=https://x.example", // no code
            "nyasmsforward://pair?server=https://x.example&code=12345", // too short
            "nyasmsforward://pair?server=https://x.example&code=1234567", // too long
            "nyasmsforward://pair?server=%ZZ&code=483920", // broken escape
        )) {
            assertNull(PairLink.parse(text), text)
        }
    }
}
