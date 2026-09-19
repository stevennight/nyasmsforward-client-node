package app.nya.smsforward.node.sms

/**
 * Normalizes a phone number / sender id so the same correspondent always maps to the same key
 * (docs/协议.md §5.2; the server implements the same rules, both are checked against the same vectors).
 *
 * - Numbers: spaces, hyphens and parentheses are removed; a mainland-China country prefix
 *   (`+86`, `0086`, `86`) is stripped when what remains is an 11-digit mobile number (1, then 3-9, then 9 more digits).
 * - Anything else (alphanumeric sender ids such as "ExampleBank"): trimmed and lower-cased.
 */
object PeerKey {
    private val numeric = Regex("""^\+?[0-9 \-()]+$""")
    private val chinaMobile = Regex("""^1[3-9][0-9]{9}$""")

    fun normalize(peer: String): String {
        val trimmed = peer.trim()
        if (!numeric.matches(trimmed)) return trimmed.lowercase()

        val compact = trimmed.filter { it.isDigit() || it == '+' }
        for (prefix in listOf("+86", "0086", "86")) {
            if (compact.startsWith(prefix)) {
                val rest = compact.removePrefix(prefix)
                if (chinaMobile.matches(rest)) return rest
            }
        }
        return compact
    }

    /** True when the peer is a real number (as opposed to an alphanumeric sender id, which cannot be replied to). */
    fun isReplyable(peer: String): Boolean = numeric.matches(peer.trim())
}
