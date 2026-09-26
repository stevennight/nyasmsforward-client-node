package app.nya.smsforward.node.sms

import app.nya.smsforward.node.data.BlockedSms
import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.SmsBlockList
import app.nya.smsforward.node.data.SmsTrash
import app.nya.smsforward.node.inbox.ConversationSummary
import app.nya.smsforward.node.inbox.InboxFilter
import app.nya.smsforward.node.inbox.SmsRow
import app.nya.smsforward.node.inbox.searchConversations
import app.nya.smsforward.node.inbox.searchAll
import app.nya.smsforward.node.inbox.Conversations
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

}

class SpamCategoryTest {
    private fun hit(c: SpamCategory, body: String, from: String = "10690001") = c.match(from, body)

    @Test
    fun `marketing needs an unsubscribe note or promo words with a link`() {
        assertEquals("回T退订", hit(SpamCategory.MARKETING, "【示例商城】秋季大促全场5折，回T退订"))
        assertEquals("拒收请回复R", hit(SpamCategory.MARKETING, "【示例】会员日福利已到账，拒收请回复R"))
        assertEquals("回复TD退订", hit(SpamCategory.MARKETING, "【示例】新品上市，回复TD退订"))
        assertEquals("限时、秒杀", hit(SpamCategory.MARKETING, "【示例】限时秒杀 低至9.9 戳 https://e.example.cn/a"))
        // Promo words without a link, or one promo word with a link, are not enough.
        assertNull(hit(SpamCategory.MARKETING, "限时秒杀，明天见"))
        assertNull(hit(SpamCategory.MARKETING, "你的福利到了 https://e.example.cn/a"))
        // A confirmation request is not an unsubscribe note.
        assertNull(hit(SpamCategory.MARKETING, "【示例银行】回复Y确认开通，回复N取消"))
    }

    @Test
    fun `loan ads but not the bank itself`() {
        assertEquals("秒到账、最高可借", hit(SpamCategory.LOAN, "【示例】您有一笔最高可借20万的额度，秒到账"))
        assertEquals("低息", hit(SpamCategory.LOAN, "低息贷款 点击 t.cn/abc"))
        assertEquals("不看征信", hit(SpamCategory.LOAN, "不 看 征 信，回T退订"))
        assertNull(hit(SpamCategory.LOAN, "【示例银行】您的信用卡账单已出，请按时还款。", "95555"))
        assertNull(hit(SpamCategory.LOAN, "【示例银行】您尾号1234的账户放款入账5,000.00元，余额8,000.00元，资金周转请合理使用", "95555"))
        assertNull(hit(SpamCategory.LOAN, "借我点钱急用", "13800138000"))
    }

    @Test
    fun `gambling survives split words`() {
        assertEquals("彩金", hit(SpamCategory.GAMBLING, "注册即领彩*金88元"))
        assertEquals("百家乐", hit(SpamCategory.GAMBLING, "真人 百 家 乐 在线"))
        assertNull(hit(SpamCategory.GAMBLING, "下周去澳门玩吗"))
    }

    @Test
    fun `fraud phrases, and phishing only from personal numbers with a link`() {
        assertEquals("安全账户", hit(SpamCategory.FRAUD, "请将资金转入安全账户配合调查"))
        assertEquals("刷单", hit(SpamCategory.FRAUD, "在家兼职刷单，一单一结"))
        assertEquals("积分即将清零", hit(SpamCategory.FRAUD, "【中国移动】您的积分即将清零，请登录 http://x.top 兑换", "13912345678"))
        assertEquals("etc已停用", hit(SpamCategory.FRAUD, "您的ETC已停用，请点击 abc.xyz/e 认证", "+639171234567"))
        // The same text from a service number is how the real notice looks.
        assertNull(hit(SpamCategory.FRAUD, "【中国移动】您的积分即将清零，请登录 http://x.top 兑换", "10086"))
        // No link, nothing to click.
        assertNull(hit(SpamCategory.FRAUD, "你的快递到了，理赔的事明天说", "13912345678"))
    }
}

class SpamFilterTest {
    private val rules = listOf(
        BlockRule(kind = BlockRule.NUMBER, value = "+86 138 0013 8000"),
        BlockRule(kind = BlockRule.NUMBER, value = "1069*"),
        BlockRule(kind = BlockRule.KEYWORD, value = "贷款"),
        BlockRule(kind = BlockRule.KEYWORD, value = "会员 续费"),
    )

    @Test
    fun `matches numbers, prefixes and keywords`() {
        assertEquals(BlockRule.NUMBER, SpamFilter.check("13800138000", "你好", rules)?.reason)
        assertEquals(BlockVerdict(BlockRule.NUMBER, "1069*"), SpamFilter.check("10690001", "随便", rules))
        assertEquals(BlockVerdict(BlockRule.KEYWORD, "贷款"), SpamFilter.check("95555", "低息贷 款秒批", rules))
        assertEquals(BlockVerdict(BlockRule.KEYWORD, "会员 续费"), SpamFilter.check("95555", "您的会员即将到期，续费享8折", rules))
        assertNull(SpamFilter.check("95555", "您的会员即将到期", rules))
        assertNull(SpamFilter.check("95555", "您的账单已出", rules))
        assertNull(SpamFilter.check("1060", "hi", rules))
    }

    @Test
    fun `keywords and categories never hold back a verification code or a contact`() {
        val all = rules + BlockRule(kind = BlockRule.MARKETING, value = "on")
        assertNull(SpamFilter.check("95555", "【示例】贷款申请验证码 583921，回T退订", all))
        assertEquals(BlockRule.MARKETING, SpamFilter.check("95555", "【示例】大促5折，回T退订", all)?.reason)
        assertNull(SpamFilter.check("95555", "【示例】大促5折，回T退订", all, isContact = true))
        // A category is off without its rule.
        assertNull(SpamFilter.check("95555", "【示例】大促5折，回T退订", rules))
        // A blocked number is blocked even for codes and contacts: the user asked for it.
        assertEquals(BlockRule.NUMBER, SpamFilter.check("10690001", "验证码 583921", all, isContact = true)?.reason)
    }

    @Test
    fun `a trusted number beats everything`() {
        val all = rules + BlockRule(kind = BlockRule.ALLOW, value = "10690088") + BlockRule(kind = SpamCategory.GAMBLING.kind, value = "on")
        assertNull(SpamFilter.check("10690088", "彩金 贷款", all))
        assertEquals(BlockRule.NUMBER, SpamFilter.check("10690089", "彩金", all)?.reason)
        assertEquals(SpamCategory.GAMBLING.kind, SpamFilter.check("95555", "彩金88", all)?.reason)
    }

    @Test
    fun `describes why`() {
        assertEquals("推广营销 · 回T退订", SpamFilter.describe("marketing", "回T退订"))
        assertEquals("推广营销", SpamFilter.describe("marketing", ""))
        assertEquals("关键词「贷款」", SpamFilter.describe(BlockRule.KEYWORD, "贷款"))
    }
}

class SmsBlockListTest {
    @Test
    fun `stores rules once and keeps intercepted messages for 30 days`() {
        val list = SmsBlockList(JdbcSqlDb())
        // A fresh database starts with the two safest categories on.
        assertEquals(setOf(SpamCategory.GAMBLING, SpamCategory.FRAUD), SpamCategory.entries.filter { list.isOn(it) }.toSet())
        list.setOn(SpamCategory.GAMBLING, false)
        list.setOn(SpamCategory.FRAUD, false)

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

    @Test
    fun `search over the whole history keeps real unread counts and finds old hits`() {
        // Conversation 7 has an old message about "发票" that is not its newest one; the provider found it.
        val newest = row(7, "10086", "本月话费 30 元", read = false)
        val all = Conversations.group(rows + newest).map { if (it.threadId == 7L) it.copy(unread = 5) else it }
        val oldHit = SmsRow(id = 70, threadId = 7, address = "10086", body = "电子发票已开具", date = 0, type = 1, read = true)

        val found = searchAll(all, listOf(oldHit), "发票")
        assertEquals(listOf(7L), ids(found))
        assertEquals("电子发票已开具", found.single().last.body)
        assertEquals(5, found.single().unread)
        // Names and numbers still match through each conversation's newest message.
        assertEquals(listOf(1L), ids(searchAll(all, emptyList(), "妈妈") { if (it == "95555") "妈妈" else null }))
        assertEquals(listOf(7L), ids(searchAll(all, emptyList(), "10086")))
    }
}
