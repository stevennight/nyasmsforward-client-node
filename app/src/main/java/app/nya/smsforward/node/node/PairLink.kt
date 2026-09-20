package app.nya.smsforward.node.node

import java.net.URLDecoder

/**
 * The content of the pairing QR code the web console shows (docs/协议.md §3.2):
 * `nyasmsforward://pair?server=https%3A%2F%2Fsms.example.com&code=483920`. It carries no long-lived credential, only the
 * address and a one-time code that expires in 10 minutes. Plain string handling so it can be tested without Android.
 */
data class PairLink(val server: String, val code: String) {
    companion object {
        private const val PREFIX = "nyasmsforward://pair?"

        /** Null when [text] is not a pairing link, or lacks the address or a 6-digit code. */
        fun parse(text: String): PairLink? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
            val params = trimmed.substring(PREFIX.length).split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val key = decode(part.substring(0, i)) ?: return@mapNotNull null
                val value = decode(part.substring(i + 1)) ?: return@mapNotNull null
                key to value
            }.toMap()
            val server = params["server"]?.trim().orEmpty()
            // The console shows the code as "483 920"; accept it however it was encoded.
            val code = params["code"].orEmpty().filter { Character.digit(it, 10) >= 0 }
            if (server.isEmpty() || code.length != 6) return null
            return PairLink(server, code)
        }

        private fun decode(s: String): String? = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
