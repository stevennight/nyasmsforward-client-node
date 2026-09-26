package app.nya.smsforward.node.sms

/** One interception rule of the full edition (docs: README「两个版本」). */
data class BlockRule(val id: Long = 0, val kind: String, val value: String) {
    companion object {
        /** A number or sender id; a trailing "*" matches every number starting with it ("1069*"). */
        const val NUMBER = "number"

        /**
         * Any message whose text contains the value (case-insensitive, spaces inside the text ignored). Several words
         * separated by spaces must all appear: "贷款 额度".
         */
        const val KEYWORD = "keyword"

        /** A trusted number: never intercepted by anything, not even a matching blacklist prefix. Same syntax as [NUMBER]. */
        const val ALLOW = "allow"

        /** Present (value "on") when that built-in category is intercepted; see [SpamCategory]. */
        val MARKETING = SpamCategory.MARKETING.kind
    }
}

/** Why a message was intercepted, for the "拦截记录" list: the rule kind plus what matched. */
data class BlockVerdict(val reason: String, val detail: String)

object SpamFilter {
    /**
     * Returns why [body] from [address] should be intercepted, or null to deliver it.
     *
     * Order: a trusted number always passes; a blacklisted number is always held (even a verification code: the user
     * asked for it). Nothing else ever holds back a verification code, a contact ([isContact]), keyword rules come next,
     * then the built-in categories that are switched on.
     */
    fun check(address: String, body: String, rules: List<BlockRule>, isContact: Boolean = false): BlockVerdict? {
        val peer = PeerKey.normalize(address)
        if (rules.any { it.kind == BlockRule.ALLOW && numberMatches(it.value, peer) }) return null
        rules.firstOrNull { it.kind == BlockRule.NUMBER && numberMatches(it.value, peer) }?.let {
            return BlockVerdict(BlockRule.NUMBER, it.value.trim())
        }
        if (isContact || VerificationCode.find(body) != null) return null
        val compact = SpamCategory.compact(body)
        for (rule in rules) {
            if (rule.kind == BlockRule.KEYWORD && keywordMatches(rule.value, compact)) return BlockVerdict(BlockRule.KEYWORD, rule.value.trim())
        }
        val on = rules.mapNotNullTo(HashSet()) { SpamCategory.ofKind(it.kind) }
        for (category in SpamCategory.entries) {
            if (category !in on) continue
            category.match(address, body)?.let { return BlockVerdict(category.kind, it) }
        }
        return null
    }

    /** "1069*" matches every number starting with 1069; anything else must be the same number. */
    fun numberMatches(rule: String, peer: String): Boolean {
        val want = rule.trim()
        return if (want.endsWith("*")) {
            val prefix = PeerKey.normalize(want.dropLast(1))
            prefix.isNotEmpty() && peer.startsWith(prefix)
        } else {
            want.isNotEmpty() && PeerKey.normalize(want) == peer
        }
    }

    private fun keywordMatches(rule: String, compact: String): Boolean {
        val words = rule.trim().split(Regex("\\s+")).map { SpamCategory.compact(it) }.filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { it in compact }
    }

    /** "黑名单号码 1069…", "关键词「贷款」", "推广营销 · 回T退订". */
    fun describe(reason: String, detail: String): String = when (reason) {
        BlockRule.NUMBER -> "黑名单号码 $detail"
        BlockRule.KEYWORD -> "关键词「$detail」"
        else -> SpamCategory.ofKind(reason)?.let { if (detail.isBlank()) it.label else "${it.label} · $detail" } ?: "已拦截"
    }
}
