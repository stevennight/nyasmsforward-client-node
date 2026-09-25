package app.nya.smsforward.node.sms

import java.text.Normalizer

/**
 * The full edition's built-in interception rules: a few kinds of junk SMS the user can switch on one by one, no download
 * needed. Each kind has strong phrases (one is enough) and weak phrases, which only count in pairs or next to a link / an
 * invitation to add someone on WeChat or QQ, because that is how nearly every spam SMS ends.
 *
 * Kept conservative on purpose: SpamFilter never applies these to a verification code, a bank movement, a parcel pickup
 * code, a contact or a trusted number, and whatever is caught stays in "骚扰拦截" for 30 days.
 */
enum class SpamCategory(val id: String, val label: String, val hint: String, val recommended: Boolean) {
    FRAUD("fraud", "诈骗风险", "刷单返佣、兼职日结、安全账户、屏幕共享、ETC/医保失效、中奖领奖等常见骗局", true),
    GAMBLING("gambling", "赌博博彩", "博彩、百家乐、娱乐城、首充返水、注册送彩金", true),
    PORN("porn", "色情招嫖", "约炮、裸聊、上门服务、同城交友引流", true),
    INVOICE("invoice", "发票办证", "代开发票、办证刻章、代考包过", true),
    LOAN("loan", "贷款借钱", "秒批下款、无抵押、征信花也能借、额度待领取（正规银行的推广也可能被拦）", false),
    MARKETING("marketing", "推广营销", "带“回T退订”“拒收请回复R”的营销短信，以及带链接的优惠券、秒杀、大促", false),
    ;

    companion object {
        fun of(id: String): SpamCategory? = entries.firstOrNull { it.id == id }
    }
}

/** Which built-in rule caught a message, and the phrase that decided it (for "拦截记录"). */
data class SpamHit(val category: SpamCategory, val phrase: String)

object SpamRules {
    private class Rules(strong: List<String>, weak: List<String>) {
        val strong = strong.map(::Regex)
        val weak = weak.map(::Regex)
    }

    private val rules: Map<SpamCategory, Rules> = mapOf(
        SpamCategory.FRAUD to Rules(
            strong = listOf(
                "安全账户",
                "屏幕共享|共享屏幕|开启共享",
                "(刷单|点赞|做任务|关注抖音|关注主播|抖音点赞|淘宝补单).{0,12}(佣金|日结|返现|返利|赚|提成)",
                "兼职.{0,8}(日结|日赚|日入|时薪|在家|手机就能|宝妈)",
                "(关闭|取消|注销).{0,8}(百万保障|百万医疗|校园贷|学生贷|学生账户)",
                "百万保障.{0,15}(关闭|取消|注销|扣费)|(校园贷|学生贷|学生账户).{0,15}(关闭|取消|注销)",
                "(日赚|日入|月入)\\d{3,}",
                "包赚不赔|稳赚不赔|内幕消息|带你赚钱|导师带单",
            ),
            weak = listOf(
                "(ETC|etc).{0,15}(失效|过期|停用|禁用|认证|异常|升级)",
                "(医保|社保|电子社保卡).{0,10}(停用|冻结|异常|失效|过期|注销)",
                "(冻结|注销|停用).{0,10}(账户|账号|银行卡|支付宝|微信|京东金条|借呗|花呗)",
                "(账户|账号|银行卡).{0,10}(冻结|异常|涉嫌|违规)",
                "中奖|领奖|幸运用户|恭喜.{0,10}(获得|抽中|中得)",
                "(退款|理赔|赔付|赔偿).{0,15}(联系|点击|添加|添加客服|办理)",
                "积分.{0,10}(清零|过期|到期|即将失效).{0,20}(兑换|领取)",
                "(快递|包裹).{0,10}(丢失|破损|理赔)",
                "(违章|罚款|罚单).{0,10}(未处理|逾期|点击|查看)",
                "(涉嫌|涉及).{0,6}(洗钱|违法|犯罪)",
                "兼职|高佣|返佣|佣金",
            ),
        ),
        SpamCategory.GAMBLING to Rules(
            strong = listOf(
                "博彩|赌场|赌博|百家乐|真人荷官|老虎机|娱乐城|菠菜|彩金|返水|体育投注|六合彩|时时彩|电子游艺|捕鱼游戏|牛牛|炸金花|斗牛",
                "(首充|充值|注册).{0,6}(送|赠|返)\\d+",
            ),
            weak = listOf("棋牌|首充|注册送|充值送|下注|投注|彩票|官方网址|永久网址|最新网址"),
        ),
        SpamCategory.PORN to Rules(
            strong = listOf("约炮|裸聊|招嫖|包夜|楼凤|一夜情|援交|外围女|外围模特|特殊服务|同城约|私密视频|成人视频|福利视频"),
            weak = listOf("同城交友|交友|寂寞|少妇|学生妹|嫩模|空姐|聊骚|私密|上门服务"),
        ),
        SpamCategory.INVOICE to Rules(
            strong = listOf(
                "代开.{0,4}发票|发票代开|代开(增值税|普票|专票)",
                "(开|有|代)(各类|各种|全额|正规)?(发票|票据).{0,8}(点数|点优|优惠|验证后付|可验证|可查验|低点)",
                "(快速|加急|专业|代)办证|办证.{0,6}(电话|微信|加急|包真|可查)|刻章|代办.{0,4}(驾照|学历|证件|学位)|代考",
                "(考试|驾照|驾考|证书|四六级|学历|考证).{0,6}包过|包拿证",
            ),
            weak = emptyList(),
        ),
        SpamCategory.LOAN to Rules(
            strong = listOf(
                "下款|放款快|秒批|秒到账|免抵押|无抵押|无需抵押|黑户|征信花|不看征信|不查征信|无视征信",
                "(可借|借款|提现|预授信)额度.{0,10}(\\d|待|已|最高)",
                "最高可借|最高可贷|额度.{0,6}待(激活|领取|提取|使用)|备用金.{0,6}待领",
            ),
            weak = listOf("贷款|借款|借钱|网贷|信用贷|额度|低息|免息|日息|利率低至|年化|分期|提现|周转|急用钱|审批"),
        ),
        SpamCategory.MARKETING to Rules(
            strong = listOf("退订|回[TNRD]{1,2}退|拒收请回|拒收回|回复[TNRD]{1,2}(退订|拒收)|unsubscribe"),
            weak = listOf(
                "优惠券|领券|神券|红包|秒杀|限时|抢购|低至|折起|特惠|大促|满减|福利|会员日|包邮|新品|直播间|爆款|钜惠|狂欢|囤货|立减|满\\d+减",
            ),
        ),
    )

    // Spammers break words up ("贷 款", "代✦开") and use full-width letters; matching happens on a cleaned-up copy.
    private val filler = Regex("[\\s\\u200B-\\u200F\\uFEFF*·•_~^`'\"|/\\\\()（）\\[\\]{}<>《》「」『』【】〔〕★☆✦✧◆◇♦❤♥♡●○◎■□▲△▼▽※#＃@＆&+=，,。.、:：;；!！?？-]")
    private val link = Regex("(?i)(https?://|www\\.|(?<![a-z0-9-])[a-z0-9-]{2,}\\.(cn|com|top|xyz|vip|cc|net|shop|icu|club|site|live|co|me|ink|fun|pro|info|asia|wang|link)(?![a-z0-9])|t\\.cn/|url\\.cn|dwz\\.)")
    private val lure = Regex("(?i)(加|添加|\\+)\\s*(微信|威信|薇信|徽信|v信|vx|wx|v(?![a-z0-9])|qq|扣扣|企鹅)|微信号|qq群|扣群|私信|私聊")

    /** The first enabled category [body] clearly belongs to, or null. [body] is the SMS text as received. */
    fun match(body: String, enabled: Set<SpamCategory>): SpamHit? {
        if (enabled.isEmpty()) return null
        val clean = clean(body)
        val invites = link.containsMatchIn(body) || lure.containsMatchIn(body) || lure.containsMatchIn(clean)
        // A registered sender's promotion carries an unsubscribe line; scams almost never do. Such a message may still be
        // "推广营销", but weak hints alone ("恭喜获得…" plus a link) do not make it a scam.
        val registeredPromo = rules.getValue(SpamCategory.MARKETING).strong.any { it.containsMatchIn(clean) }
        for (category in SpamCategory.entries) {
            if (category !in enabled) continue
            val r = rules.getValue(category)
            r.strong.firstNotNullOfOrNull { it.find(clean) }?.let { return SpamHit(category, it.value) }
            if (category == SpamCategory.FRAUD && registeredPromo) continue
            val weak = r.weak.flatMap { re -> re.findAll(clean).map { it.value } }.distinct()
            if (weak.size >= 2 || (weak.isNotEmpty() && invites)) return SpamHit(category, weak.take(2).joinToString("、"))
        }
        return null
    }

    internal fun clean(body: String): String = filler.replace(Normalizer.normalize(body, Normalizer.Form.NFKC), "")
}
