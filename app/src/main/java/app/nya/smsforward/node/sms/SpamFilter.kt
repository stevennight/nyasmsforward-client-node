package app.nya.smsforward.node.sms

/** One interception rule of the full edition (docs: README「两个版本」). */
data class BlockRule(val id: Long = 0, val kind: String, val value: String) {
    companion object {
        /** A number or sender id; a trailing "*" matches every number starting with it ("1069*"). */
        const val NUMBER = "number"

        /** Any message whose text contains the value (case-insensitive). */
        const val KEYWORD = "keyword"

        /** A built-in category that is switched on; the value is a [SpamCategory.id]. */
        const val BUILTIN = "builtin"

        /** A trusted number (same syntax as [NUMBER]): keyword and built-in rules never touch it. */
        const val ALLOW = "allow"

        /** Schema v6's marketing switch; Schema v7 turns it into BUILTIN "marketing". Old block records still name it. */
        const val MARKETING = "marketing"
    }
}

/** Why a message was intercepted, for the "拦截记录" list: a [BlockRule] kind plus the matching rule. */
data class BlockVerdict(val reason: String, val detail: String)

object SpamFilter {
    /**
     * Returns why [body] from [address] should be intercepted, or null to deliver it.
     *
     * A blocked number always wins: the user asked for it. Everything else (keywords, built-in rules) leaves alone a
     * [trusted] sender (a contact), a number on the allow list, and any message with a verification code, so a login is
     * never held back. Built-in rules also skip bank movements and parcel pickup codes.
     */
    fun check(address: String, body: String, rules: List<BlockRule>, trusted: Boolean = false): BlockVerdict? {
        val peer = PeerKey.normalize(address)
        rules.firstOrNull { it.kind == BlockRule.NUMBER && numberMatches(it.value, peer) }?.let { return BlockVerdict(BlockRule.NUMBER, it.value.trim()) }
        if (trusted || rules.any { it.kind == BlockRule.ALLOW && numberMatches(it.value, peer) }) return null
        if (VerificationCode.find(body) != null) return null
        for (rule in rules) {
            if (rule.kind == BlockRule.KEYWORD && rule.value.isNotBlank() && body.contains(rule.value.trim(), ignoreCase = true)) {
                return BlockVerdict(BlockRule.KEYWORD, rule.value.trim())
            }
        }
        val enabled = enabledCategories(rules)
        if (enabled.isEmpty() || SmsInsight.find(body) != null) return null
        val hit = SpamRules.match(body, enabled) ?: return null
        return BlockVerdict(BlockRule.BUILTIN, "${hit.category.id}:${hit.phrase}")
    }

    fun enabledCategories(rules: List<BlockRule>): Set<SpamCategory> =
        rules.mapNotNullTo(mutableSetOf()) { rule ->
            when (rule.kind) {
                BlockRule.BUILTIN -> SpamCategory.of(rule.value)
                BlockRule.MARKETING -> SpamCategory.MARKETING
                else -> null
            }
        }

    /** "1069*" matches every number that starts with 1069; anything else must be the same number. */
    fun numberMatches(rule: String, peer: String): Boolean {
        val want = rule.trim()
        return if (want.endsWith("*")) {
            val prefix = PeerKey.normalize(want.dropLast(1))
            prefix.isNotEmpty() && peer.startsWith(prefix)
        } else {
            PeerKey.normalize(want) == peer
        }
    }

    /** "黑名单号码 1069…", "关键词「贷款」", "疑似诈骗风险（“安全账户”）", "推广短信". */
    fun describe(reason: String, detail: String): String = when (reason) {
        BlockRule.NUMBER -> "黑名单号码 $detail"
        BlockRule.KEYWORD -> "关键词「$detail」"
        BlockRule.MARKETING -> "推广短信"
        BlockRule.BUILTIN -> {
            val category = SpamCategory.of(detail.substringBefore(':'))
            val phrase = detail.substringAfter(':', "")
            val label = category?.label ?: "内置规则"
            if (phrase.isBlank()) "疑似$label" else "疑似$label（“$phrase”）"
        }
        else -> "已拦截"
    }
}
