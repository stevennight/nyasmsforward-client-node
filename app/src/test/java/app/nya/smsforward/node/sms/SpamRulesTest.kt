package app.nya.smsforward.node.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpamRulesTest {
    private val all = SpamCategory.entries.toSet()
    private val allRules = SpamCategory.entries.map { BlockRule(kind = BlockRule.BUILTIN, value = it.id) }

    private fun category(body: String) = SpamRules.match(body, all)?.category

    @Test
    fun `catches common scams`() {
        assertEquals(SpamCategory.FRAUD, category("您好，为配合调查请将资金转入安全账户，否则将冻结。"))
        assertEquals(SpamCategory.FRAUD, category("招聘抖音点赞员，一单一结，点赞即可赚佣金，日赚300+，加V：abc123"))
        assertEquals(SpamCategory.FRAUD, category("【重要】您的ETC已失效，请及时登录 etc-renz.top 重新认证，以免影响通行"))
        assertEquals(SpamCategory.FRAUD, category("您的百万保障即将扣费，如需关闭请联系客服 400xxxx"))
        assertEquals(SpamCategory.FRAUD, category("您的医保卡已被冻结，请点击 http://a.cn/x 处理"))
        assertEquals(SpamCategory.FRAUD, category("兼 职 日 结，在家手机就能做，宝妈学生均可"))
        assertEquals(SpamCategory.FRAUD, category("恭喜您被抽中为幸运用户，获得iPhone一部，领奖请访问 lj88.cn"))
    }

    @Test
    fun `catches gambling, porn, invoices and loans`() {
        assertEquals(SpamCategory.GAMBLING, category("澳门娱乐城上线，首充送100%，真人百家乐，永久网址 xx88.vip"))
        assertEquals(SpamCategory.GAMBLING, category("注册即送88彩金，每日返水不停"))
        assertEquals(SpamCategory.PORN, category("同城寂寞少妇在线，加微信 abc 私密聊"))
        assertEquals(SpamCategory.PORN, category("附近约炮不花钱"))
        assertEquals(SpamCategory.INVOICE, category("代✦开各类发票，点数优惠，验证后付款"))
        assertEquals(SpamCategory.INVOICE, category("专业办证刻章，电话 138xxxx"))
        assertEquals(SpamCategory.LOAN, category("【XX金融】您有最高可借20万额度待领取，秒批秒到账，回T退订"))
        assertEquals(SpamCategory.LOAN, category("急用钱？无抵押，征信花也能下款"))
        assertEquals(SpamCategory.LOAN, category("贷 款 低 息，点击 dk.cn/a 申请"))
    }

    @Test
    fun `catches marketing`() {
        assertEquals(SpamCategory.MARKETING, category("【示例商城】秋季大促全场5折，回T退订"))
        assertEquals(SpamCategory.MARKETING, category("【示例】会员日福利已到账，拒收请回复R"))
        assertEquals(SpamCategory.MARKETING, category("双11秒杀，优惠券限时领取 https://s.example.com/a"))
    }

    @Test
    fun `leaves ordinary messages alone`() {
        val ordinary = listOf(
            "晚上一起吃饭吗？",
            "面包过期了，别吃",
            "【示例物业】明天上午 9 点提供家电维修上门服务，请留意",
            "【示例银行】您的信用卡临时额度已调整，详询客服",
            "【示例银行】您的信用卡账单已出，请按时还款。",
            "【12123】您有一条交通违法记录，请登录交管12123 App 查看",
            "【示例学校】本周五下午体育课改到操场，请带运动鞋",
            "【示例快递】您的包裹已到小区驿站，请尽快领取",
            "我下周去办证件，你那边要带什么材料？",
            "我这边兼职做家教，周末有空",
            "【示例政务】您的社保缴费已到账，感谢配合",
            "明天开会带上发票",
            "【示例外卖】您的订单已送达，祝您用餐愉快",
            "【示例商城】您的订单已发货，点击 https://3.cn/abc 查看物流",
            "【示例出行】您的行程已结束，本次消费 23.5 元",
            "【示例支付】你有一笔退款已原路退回",
            "【示例旅行】恭喜您获得一张酒店优惠券，已放入账户",
            "妈，我换号了，加我微信吧",
            "【示例保险】您的百万医疗险本月保费已扣除，保障正常",
        )
        for (body in ordinary) assertNull(SpamRules.match(body, all), body)
    }

    @Test
    fun `only switched-on categories count`() {
        assertNull(SpamRules.match("真人百家乐", setOf(SpamCategory.FRAUD)))
        assertNull(SpamRules.match("真人百家乐", emptySet()))
    }

    @Test
    fun `a registered promotion is not a scam by weak hints alone`() {
        assertEquals(SpamCategory.MARKETING, category("【示例商城】恭喜您获得 20 元优惠券，戳 s.example.com 使用，回T退订"))
        assertNull(SpamRules.match("【示例商城】恭喜您获得 20 元优惠券，戳 s.example.com 使用，回T退订", setOf(SpamCategory.FRAUD)))
    }

    @Test
    fun `the filter keeps codes, bank movements and parcels out of built-in rules`() {
        assertNull(SpamFilter.check("95555", "【示例】您的验证码 583921，贷款申请请勿泄露，回T退订", allRules))
        assertNull(SpamFilter.check("95555", "【示例银行】您尾号1234账户收入5,000.00元，可借额度最高可提升，回T退订", allRules))
        assertNull(SpamFilter.check("10690", "【菜鸟驿站】取件码 4-7-2011，双11秒杀优惠券限时领取 s.example.com", allRules))
        assertEquals(BlockRule.BUILTIN, SpamFilter.check("10690", "真人百家乐", allRules)?.reason)
    }
}
