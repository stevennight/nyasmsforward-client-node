package app.nya.smsforward.node.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmsDeleteMatcherTest {
    @Test
    fun `normalizes peer and line endings and chooses the closest candidate`() {
        val request = DeleteSms(
            messageId = 71,
            direction = "in",
            peer = "+8613800000000",
            body = "广告\r\n内容",
            deviceTime = 1_000_000L,
            simSlot = 1,
        )

        val match = SmsDeleteMatcher.find(
            request,
            listOf(
                SmsDeleteCandidate(10, "13800000000", "广告\n内容", 999_000L, 0L, 1),
                SmsDeleteCandidate(11, "13800000000", "广告\n内容", 1_004_000L, 0L, 1),
            ),
            windowMs = 300_000L,
        )

        assertEquals(SmsDeleteMatcher.Match(id = 10, distance = 1_000L, bodyRank = 0), match)
    }

    @Test
    fun `exact body match wins and a different sim is ignored`() {
        val request = DeleteSms(71, "out", "13800000000", "第一行 第二行", 2_000_000L, simSlot = 2)

        val match = SmsDeleteMatcher.find(
            request,
            listOf(
                SmsDeleteCandidate(20, "13800000000", "第一行  第二行", 2_000_010L, 0L, 2),
                SmsDeleteCandidate(21, "13800000000", "第一行 第二行", 2_000_020L, 0L, 2),
                SmsDeleteCandidate(22, "13800000000", "第一行 第二行", 2_000_001L, 0L, 1),
            ),
            windowMs = 300_000L,
        )

        assertEquals(SmsDeleteMatcher.Match(id = 21, distance = 20L, bodyRank = 0), match)
    }

    @Test
    fun `returns no match when the provider row is outside the time window`() {
        val request = DeleteSms(71, "in", "106901234", "验证码 583921", 3_000_000L)

        val match = SmsDeleteMatcher.find(
            request,
            listOf(SmsDeleteCandidate(30, "106901234", "验证码 583921", 3_300_001L, 0L, null)),
            windowMs = 300_000L,
        )

        assertNull(match)
    }
}
