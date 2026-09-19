package app.nya.smsforward.node.net

/**
 * Validation of the server address typed on the pairing / connection settings screen (docs/协议.md §2.4):
 * `https://` is required, `http://` is only accepted for localhost and private LAN addresses.
 */
sealed interface ServerUrlResult {
    /** [url] has no trailing slash. [insecure] is true for http:// LAN / localhost addresses, so the UI can warn. */
    data class Ok(val url: String, val insecure: Boolean) : ServerUrlResult

    data class Invalid(val reason: Reason) : ServerUrlResult

    enum class Reason { EMPTY, BAD_SCHEME, BAD_HOST, PUBLIC_HTTP }
}

object ServerUrl {
    private val hostPattern = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$""")
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun validate(input: String): ServerUrlResult {
        val raw = input.trim()
        if (raw.isEmpty()) return ServerUrlResult.Invalid(ServerUrlResult.Reason.EMPTY)

        val scheme = raw.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "https" && scheme != "http") return ServerUrlResult.Invalid(ServerUrlResult.Reason.BAD_SCHEME)

        val authority = raw.substringAfter("://").substringBefore('/')
        val host = authority.substringBeforeLast(':', authority).removeSurrounding("[", "]")
        val port = if (authority.contains(':') && !authority.endsWith("]")) authority.substringAfterLast(':') else null
        if (host.isEmpty() || !hostPattern.matches(host) || (port != null && port.toIntOrNull()?.let { it in 1..65535 } != true)) {
            return ServerUrlResult.Invalid(ServerUrlResult.Reason.BAD_HOST)
        }
        // Only now is it safe to drop trailing slashes: the authority is non-empty, so "scheme://" survives.
        val text = raw.trimEnd('/')

        if (scheme == "http") {
            return if (isLocalOrPrivate(host)) {
                ServerUrlResult.Ok(text, insecure = true)
            } else {
                ServerUrlResult.Invalid(ServerUrlResult.Reason.PUBLIC_HTTP)
            }
        }
        return ServerUrlResult.Ok(text, insecure = false)
    }

    private fun isLocalOrPrivate(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val m = ipv4.matchEntire(host) ?: return false
        val (a, b, c, d) = m.destructured.toList().map { it.toInt() }
        if (listOf(a, b, c, d).any { it > 255 }) return false
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
