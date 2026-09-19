package app.nya.smsforward.node.net

import kotlin.math.min
import kotlin.math.pow

/**
 * What a failed request means for the stored token (docs/协议.md §2.2).
 *
 * Getting this wrong is expensive: treating a flaky proxy as "token revoked" would unpair a phone that sits in a
 * corner and silently stop reporting SMS. So only an explicit `401` carrying one of the server's own error codes
 * unpairs; everything else keeps the token and retries.
 */
enum class ConnectionVerdict {
    /** The server says this token is dead: go to "not paired", but keep the local outbox. */
    NEEDS_PAIRING,

    /** Answered, but not like our server (bare 401 from a proxy, 403, 404): keep the token, hint the address may be wrong. */
    ADDRESS_SUSPECT,

    /** Network error, timeout, 429 or 5xx: keep the token and retry with backoff. */
    TRANSIENT,
}

object Connection {
    private val deadTokenCodes = setOf("token_revoked", "token_invalid", "token_expired")

    /**
     * @param httpStatus the HTTP status, or null when no response arrived (I/O failure, timeout, TLS error)
     * @param errorCode the `error` field of a JSON error body, if the body was our JSON
     * @param websocketCloseCode the WebSocket close code, if the failure was a closed socket
     */
    fun classify(httpStatus: Int?, errorCode: String? = null, websocketCloseCode: Int? = null): ConnectionVerdict {
        if (websocketCloseCode == WS_UNAUTHORIZED) {
            return if (errorCode in deadTokenCodes) ConnectionVerdict.NEEDS_PAIRING else ConnectionVerdict.TRANSIENT
        }
        return when {
            httpStatus == null -> ConnectionVerdict.TRANSIENT
            httpStatus == 401 && errorCode in deadTokenCodes -> ConnectionVerdict.NEEDS_PAIRING
            httpStatus == 401 || httpStatus == 403 || httpStatus == 404 -> ConnectionVerdict.ADDRESS_SUSPECT
            else -> ConnectionVerdict.TRANSIENT // 429, 5xx (incl. 502/503/504 from a proxy), anything unexpected
        }
    }

    /** WebSocket close code the server uses for a rejected token. */
    const val WS_UNAUTHORIZED = 4401
}

/** Reconnect delay: 1s, doubling, capped at 5 minutes, with up to 20% jitter. Retries never stop. */
object Backoff {
    private const val BASE_MS = 1_000L
    private const val CAP_MS = 5 * 60 * 1_000L

    /** @param attempt 0 for the first retry. @param random a value in [0, 1) so tests can be deterministic. */
    fun delayMillis(attempt: Int, random: Double): Long {
        val exp = min(BASE_MS * 2.0.pow(min(attempt, 30)), CAP_MS.toDouble())
        return (exp * (1.0 + 0.2 * random)).toLong().coerceAtMost((CAP_MS * 1.2).toLong())
    }
}
