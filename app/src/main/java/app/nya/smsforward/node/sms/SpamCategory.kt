package app.nya.smsforward.node.sms

/**
 * The full edition's built-in interception categories. Each one is a switch (a [BlockRule] whose kind is [kind]); none of
 * them ever touches a message that carries a verification code, and [SpamFilter] also spares contacts and trusted numbers.
 *
 * Every category is deliberately conservative: a single ordinary word ("红包", "贷款", "澳门") is never enough, only phrases
 * that practically only spam uses, or several weaker signals together (promo words plus a link, a scam story plus a link
 * from a personal number). What matched is returned so the "拦截记录" can say why.
 */
enum class SpamCategory(val kind: String, val label: String, val hint: String) {
    MARKETING(
        "marketing",
        "推广营销",
        "带“回T退订”“拒收请回复R”等退订说明的营销短信，以及同时有多个促销词和链接的广告。",
    ) {
        override fun find(body: String, compact: String, address: String): String? {
            unsubscribe.find(body)?.let { return it.value.trim() }
            if (!hasLink(body)) return null
            val hits = promo.filter { it in compact }
            return if (hits.size >= 2) hits.take(2).joinToString("、") else null
        }
    },
    LOAN(
        "loan",
        "贷款金融",
        "网贷、提额、套现、“最高可借”“秒批”之类的借贷广告。银行的收支、账单和还款提醒不受影响。",
    ) {
        override fun find(body: String, compact: String, address: String): String? {
            // A real account movement ("尾号 1234 放款 5000 元，余额 …") is the bank talking, not an ad.
            if (SmsInsight.find(body) is SmsInsight.Bank) return null
            val hits = loan.filter { it in compact }
            if (hits.isEmpty()) return null
            return if (hits.size >= 2 || hasLink(body) || unsubscribe.containsMatchIn(body)) hits.take(2).joinToString("、") else null
        }
    },
    GAMBLING(
        "gambling",
        "博彩色情",
        "网络博彩、棋牌充值、彩金返水、色情交友等。",
    ) {
        override fun find(body: String, compact: String, address: String): String? = gambling.firstOrNull { it in compact }
    },
    FRAUD(
        "fraud",
        "诈骗风险",
        "“安全账户”“刷单返利”，以及个人号码发来的“积分清零 / ETC 停用 / 快递理赔”加链接等常见骗局。",
    ) {
        override fun find(body: String, compact: String, address: String): String? {
            fraud.firstOrNull { it in compact }?.let { return it }
            // The classic phishing text: an official-sounding problem and a link to "fix" it, from a personal or overseas
            // number. Real notices of this kind come from service numbers (95xxx, 106…), which this never matches.
            if (!fromPerson(address) || !hasLink(body)) return null
            return phishing.find(compact)?.value
        }
    };

    /** The phrase that matched, or null. [compact] is [body] lower-cased with spaces and filler symbols removed. */
    protected abstract fun find(body: String, compact: String, address: String): String?

    fun match(address: String, body: String): String? = find(body, compact(body), address)

    companion object {
        fun ofKind(kind: String): SpamCategory? = entries.firstOrNull { it.kind == kind }


        /** Spammers split words to dodge filters ("贷 款", "博*彩"); matching ignores that filler. */
        fun compact(body: String): String = body.lowercase().replace(filler, "")

        private val filler = Regex("[\\s\\u200b\\u200c\\u200d\\ufeff*·•_|~^\\-—.,，。、/\\\\]+")

        // Mainland marketing SMS must say how to unsubscribe, which makes them easy to spot. A bare "回复N" is not enough:
        // "回复Y确认，回复N取消" is how banks and carriers ask for a confirmation.
        private val unsubscribe = Regex(
            "(?i)(退订|回复?\\s*(?:td|t|n|r|0000|0)\\s*(?:退订?|拒收?)|拒收请回复?\\s*[a-z0-9]{0,4}|退回\\s*t|unsubscribe|取消订阅)",
        )

        private val link = Regex(
            "(?i)(https?://|www\\.|\\b[a-z0-9-]{1,63}\\.(?:cn|com|net|org|top|xyz|vip|cc|me|io|co|tk|link|club|site|shop|icu|fun|ink|wang|live|pro|app)(?:/|\\b))",
        )

        fun hasLink(body: String): Boolean = link.containsMatchIn(body)

        private val mobile = Regex("^1[3-9][0-9]{9}$")

        /** An 11-digit mainland mobile or a foreign number: who phishing texts come from. */
        fun fromPerson(address: String): Boolean {
            val peer = PeerKey.normalize(address)
            return mobile.matches(peer) || (peer.startsWith("+") && !peer.startsWith("+86"))
        }

        private val promo = listOf(
            "优惠券", "领券", "限时", "秒杀", "大促", "特惠", "折起", "低至", "满减", "包邮", "清仓", "爆款", "福利",
            "抢购", "狂欢", "会员日", "专享", "免费领", "0元", "半价", "直降", "返现", "抽奖", "新品上市", "年终盛典",
            "双11", "双十一", "618", "点击领取", "立即领取", "立即抢",
        )

        private val loan = listOf(
            "秒批", "秒下款", "秒到账", "下款", "放款快", "低息", "免息借", "日息", "月息低", "无抵押", "免抵押", "无需抵押",
            "黑户", "征信花", "不看征信", "不查征信", "网贷", "借款额度", "最高可借", "最高可贷", "最高借", "可借额度",
            "额度已提升", "提额", "套现", "养卡", "信用贷", "急用钱", "资金周转", "贷款额度", "借钱", "放款",
        )

        private val gambling = listOf(
            "博彩", "彩金", "百家乐", "棋牌", "娱乐城", "真人荷官", "荷官", "首充", "返水", "六合彩", "时时彩", "老虎机",
            "体育投注", "电子游艺", "葡京", "威尼斯人", "太阳城", "线上娱乐", "充值送", "注册送", "彩票平台", "下注",
            "约炮", "裸聊", "同城约", "上门服务", "包夜", "一夜情", "援交", "私密视频", "激情视频",
        )

        private val fraud = listOf(
            "安全账户", "涉嫌洗钱", "涉嫌犯罪", "通缉令", "刷单", "点赞员", "兼职日结", "日结工资", "做任务返", "返利任务",
            "资金清查", "保证金解冻", "解冻费", "冻结您的", "注销校园贷", "注销网贷",
        )

        private val phishing = Regex(
            "(积分|etc|医保|社保|包裹|快递|订单|账户|信用卡|驾照|驾驶证|车险|个税|补贴)[^\\n]{0,20}?(清零|过期|到期|失效|停用|异常|冻结|理赔|退款|违章|扣分|补贴|领取)",
        )
    }
}
