package app.nya.smsforward.node.sms

/** One interception rule of the full edition (docs: README「两个版本」). */
data class BlockRule(val id: Long = 0, val kind: String, val value: String) {
    companion object {
        /** A number or sender id; a trailing "*" matches every number starting with it ("1069*"). */
        const val NUMBER = "number"

        /** Any message whose text contains the value (case-insensitive). */
        const val KEYWORD = "keyword"

        /** Present (value "on") when marketing SMS ("回T退订") are intercepted. */
        const val MARKETING = "marketing"
    }
}

/** Why a message was intercepted, for the "拦截记录" list: "number" / "keyword" / "marketing" plus the matching rule. */
data class BlockVerdict(val reason: String, val detail: String)

object SpamFilter {
    /**
     * Returns why [body] from [address] should be intercepted, or null to deliver it. A blocked number always wins; keyword
     * and marketing rules never touch a message that carries a verification code, so a login is never held back by them.
     */
    fun check(address: String, body: String, rules: List<BlockRule>): BlockVerdict? {
        val peer = PeerKey.normalize(address)
        for (rule in rules) {
            if (rule.kind != BlockRule.NUMBER) continue
            val want = rule.value.trim()
            val hit = if (want.endsWith("*")) {
                val prefix = PeerKey.normalize(want.dropLast(1))
                prefix.isNotEmpty() && peer.startsWith(prefix)
            } else {
                PeerKey.normalize(want) == peer
            }
            if (hit) return BlockVerdict(BlockRule.NUMBER, want)
        }
        if (VerificationCode.find(body) != null) return null
        for (rule in rules) {
            if (rule.kind == BlockRule.KEYWORD && rule.value.isNotBlank() && body.contains(rule.value.trim(), ignoreCase = true)) {
                return BlockVerdict(BlockRule.KEYWORD, rule.value.trim())
            }
        }
        if (rules.any { it.kind == BlockRule.MARKETING } && Marketing.looksLike(body)) return BlockVerdict(BlockRule.MARKETING, "")
        return null
    }

    /** "黑名单号码 1069…", "关键词「贷款」", "推广短信". */
    fun describe(reason: String, detail: String): String = when (reason) {
        BlockRule.NUMBER -> "黑名单号码 $detail"
        BlockRule.KEYWORD -> "关键词「$detail」"
        BlockRule.MARKETING -> "推广短信"
        else -> "已拦截"
    }
}
