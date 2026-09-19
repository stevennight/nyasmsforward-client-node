package app.nya.smsforward.node.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmsAssemblerTest {
    @Test
    fun `the parts of one long SMS are joined into a single message`() {
        val out = SmsAssembler.assemble(
            listOf(
                SmsPart("106901234", "【示例商城】验证码 583921，", 1000),
                SmsPart("106901234", "5 分钟内有效，请勿泄露", 1000),
                SmsPart("106901234", "给他人。", 1000),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("【示例商城】验证码 583921，5 分钟内有效，请勿泄露给他人。", out[0].body)
        assertEquals(1000, out[0].timestampMillis)
    }

    @Test
    fun `messages from different senders stay separate and keep their order`() {
        val out = SmsAssembler.assemble(
            listOf(
                SmsPart("13800000000", "a", 1), SmsPart("106901234", "b", 2), SmsPart("13800000000", "c", 3),
            ),
        )
        assertEquals(listOf("13800000000" to "ac", "106901234" to "b"), out.map { it.peer to it.body })
        assertEquals(1, out[0].timestampMillis, "the first part's time is the message time")
    }

    @Test
    fun `sender ids are trimmed and a missing sender gets a placeholder`() {
        val out = SmsAssembler.assemble(listOf(SmsPart("  示例银行 ", "x", 1), SmsPart(null, "y", 2), SmsPart("  ", "z", 3)))
        assertEquals(listOf("示例银行" to "x", SmsAssembler.UNKNOWN_SENDER to "yz"), out.map { it.peer to it.body })
    }

    @Test
    fun `empty messages are dropped and no parts give no messages`() {
        assertTrue(SmsAssembler.assemble(emptyList()).isEmpty())
        assertTrue(SmsAssembler.assemble(listOf(SmsPart("1", null, 1), SmsPart("1", "", 2))).isEmpty())
    }

    @Test
    fun `whitespace inside the body is preserved exactly`() {
        val out = SmsAssembler.assemble(listOf(SmsPart("1", " 第一行\n", 1), SmsPart("1", "  第二行 ", 1)))
        assertEquals(" 第一行\n  第二行 ", out.single().body)
    }
}
