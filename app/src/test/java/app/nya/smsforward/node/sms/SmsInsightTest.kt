package app.nya.smsforward.node.sms

import app.nya.smsforward.node.data.BlockedSms
import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.SmsBlockList
import app.nya.smsforward.node.data.SmsTrash
import app.nya.smsforward.node.inbox.ConversationSummary
import app.nya.smsforward.node.inbox.InboxFilter
import app.nya.smsforward.node.inbox.SmsRow
import app.nya.smsforward.node.inbox.searchConversations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsInsightTest {
    @Test
    fun `reads bank movements`() {
        assertEquals(
            SmsInsight.Bank(income = false, amount = "58.00", balance = "2,310.45", cardTail = "1234"),
            SmsInsight.find("【示例银行】您尾号 1234 的账户于 09:12 支出 58.00 元，余额 2,310.45 元。"),
        )
        assertEquals(
            SmsInsight.Bank(income = true, amount = "5,000.00", balance = "12,345.67", cardTail = "8888"),
            SmsInsight.find("您尾号8888的储蓄卡9月24日12:30收入人民币5,000.00元，活期余额12,345.67元。【示例银行】"),
        )
        assertEquals(
            SmsInsight.Bank(income = false, amount = "128.00", balance = null, cardTail = null),
            SmsInsight.find("【示例银行】您账户于09月24日12:30消费人民币128.00元，商户：示例超市。"),
        )
    }

    @Test
    fun `reads parcel pickup codes`() {
        assertEquals(SmsInsight.Parcel("4-7-2011"), SmsInsight.find("【示例快递】您的包裹已放入 3 号柜，取件码 4-7-2011，请于 24 小时内取件。"))
        assertEquals(SmsInsight.Parcel("A12-3-4567"), SmsInsight.find("【菜鸟驿站】凭取件码：A12-3-4567 到示例小区驿站取件"))
        assertEquals(SmsInsight.Parcel("88231645"), SmsInsight.find("【丰巢】取件码88231645，已到示例路快递柜"))
    }

    @Test
    fun `leaves ordinary messages alone`() {
        assertNull(SmsInsight.find("我支付了 50 块的车费，回头给你"))
        assertNull(SmsInsight.find("【示例银行】您的信用卡账单已出，请按时还款。"))
        assertNull(SmsInsight.find("晚上吃饭吗"))
        assertNull(SmsInsight.find("【示例商城】验证码 583921，5 分钟内有效"))
    }

    @Test
    fun `spots marketing but never a verification code`() {
        assertTrue(Marketing.looksLike("【示例商城】秋季大促全场5折，回T退订"))
        assertTrue(Marketing.looksLike("【示例】会员日福利已到账，拒收请回复R"))
        assertFalse(Marketing.looksLike("【示例商城】验证码 583921，回T退订"))
        assertFalse(Marketing.looksLike("明天见"))
    }
}

class SpamFilterTest {
    private val rules = listOf(
        BlockRule(kind = BlockRule.NUMBER, value = "+86 138 0013 8000"),
        BlockRule(kind = BlockRule.NUMBER, value = "1069*"),
        BlockRule(kind = BlockRule.KEYWORD, value = "贷款"),
    )

    @Test
    fun `matches numbers, prefixes and keywords`() {
        assertEquals(BlockRule.NUMBER, SpamFilter.check("13800138000", "你好", rules)?.reason)
        assertEquals(BlockVerdict(BlockRule.NUMBER, "1069*"), SpamFilter.check("10690001", "随便", rules))
        assertEquals(BlockVerdict(BlockRule.KEYWORD, "贷款"), SpamFilter.check("95555", "低息贷款秒批", rules))
        assertNull(SpamFilter.check("95555", "您的账单已出", rules))
        assertNull(SpamFilter.check("1060", "hi", rules))
    }

    @Test
    fun `keywords and marketing never hold back a verification code`() {
        val all = rules + BlockRule(kind = BlockRule.MARKETING, value = "on")
        assertNull(SpamFilter.check("95555", "【示例】贷款申请验证码 583921，回T退订", all))
        assertEquals(BlockRule.MARKETING, SpamFilter.check("95555", "【示例】大促5折，回T退订", all)?.reason)
        // Marketing is off without its rule.
        assertNull(SpamFilter.check("95555", "【示例】大促5折，回T退订", rules))
        // A blocked number is blocked even for codes: the user asked for it.
        assertEquals(BlockRule.NUMBER, SpamFilter.check("10690001", "验证码 583921", all)?.reason)
    }
}

class SmsBlockListTest {
    @Test
    fun `stores rules once and keeps intercepted messages for 30 days`() {
        val list = SmsBlockList(JdbcSqlDb())
        assertTrue(list.addRule(BlockRule.NUMBER, " 10690001 ", 1))
        assertFalse(list.addRule(BlockRule.NUMBER, "10690001", 2))
        assertFalse(list.addRule(BlockRule.KEYWORD, "  ", 3))
        assertFalse(list.marketingBlocked)
        list.marketingBlocked = true
        assertTrue(list.marketingBlocked)
        assertEquals(setOf("10690001", "on"), list.rules().map { it.value }.toSet())
        list.marketingBlocked = false
        assertEquals(listOf("10690001"), list.rules().map { it.value })
        assertTrue(list.removeRule(list.rules().first().id))

        val day = 86_400_000L
        val old = list.add(BlockedSms(address = "1", body = "旧", date = 1, dateSent = 0, subId = null, reason = "keyword", detail = "x", blockedAt = 0))
        list.add(BlockedSms(address = "2", body = "新", date = 2, dateSent = 1, subId = 2, reason = "number", detail = "2", blockedAt = 20 * day))
        assertEquals(listOf("新", "旧"), list.list().map { it.body })
        assertEquals("keyword", list.find(old)?.reason)
        assertEquals(1, list.purge(SmsTrash.RETENTION_MS + day))
        assertEquals(listOf("新"), list.list().map { it.body })
    }
}

class InboxFilterTest {
    private fun row(id: Long, address: String, body: String, read: Boolean = true) =
        SmsRow(id = id, threadId = id, address = address, body = body, date = id, type = 1, read = read)

    private val rows = listOf(
        row(1, "95555", "【示例银行】您尾号 1234 的账户支出 58.00 元，余额 2,310.45 元。"),
        row(2, "10690", "【示例快递】取件码 4-7-2011"),
        row(3, "10691", "验证码 583921", read = false),
        row(4, "13800138000", "晚上吃饭吗"),
    )

    private fun ids(list: List<ConversationSummary>) = list.map { it.threadId }

    @Test
    fun `chips pick conversations by their newest message`() {
        val all = searchConversations(rows, "")
        assertEquals(listOf(4L, 3, 2, 1), ids(all))
        assertEquals(listOf(3L), ids(all.filter { InboxFilter.UNREAD.matches(it) }))
        assertEquals(listOf(3L), ids(all.filter { InboxFilter.CODE.matches(it) }))
        assertEquals(listOf(1L), ids(all.filter { InboxFilter.BANK.matches(it) }))
        assertEquals(listOf(2L), ids(all.filter { InboxFilter.PARCEL.matches(it) }))
    }

    @Test
    fun `search looks at text, numbers and contact names`() {
        assertEquals(listOf(4L), ids(searchConversations(rows, "吃饭")))
        assertEquals(listOf(4L), ids(searchConversations(rows, "0013")))
        assertEquals(listOf(1L), ids(searchConversations(rows, "妈妈") { if (it == "95555") "妈妈" else null }))
        assertEquals(emptyList(), ids(searchConversations(rows, "不存在")))
    }
}
