package app.nya.smsforward.node.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/协议.md §2.2: only an explicit token error may unpair the phone. */
class ConnectionTest {
    @Test
    fun `a 401 with one of our token error codes means the token is dead`() {
        for (code in listOf("token_revoked", "token_invalid", "token_expired")) {
            assertEquals(ConnectionVerdict.NEEDS_PAIRING, Connection.classify(401, code), code)
        }
    }

    @Test
    fun `a bare 401 or 403 or 404 keeps the token and suspects the address`() {
        assertEquals(ConnectionVerdict.ADDRESS_SUSPECT, Connection.classify(401, null))
        assertEquals(ConnectionVerdict.ADDRESS_SUSPECT, Connection.classify(401, "something_else"))
        assertEquals(ConnectionVerdict.ADDRESS_SUSPECT, Connection.classify(403, null))
        assertEquals(ConnectionVerdict.ADDRESS_SUSPECT, Connection.classify(404, "not_found"))
    }

    @Test
    fun `network failures and server trouble are transient`() {
        assertEquals(ConnectionVerdict.TRANSIENT, Connection.classify(null))
        for (status in listOf(408, 429, 500, 502, 503, 504, 520)) {
            assertEquals(ConnectionVerdict.TRANSIENT, Connection.classify(status), "status $status")
        }
        // Even a token error code is ignored when the status is not 401: a 5xx must never unpair.
        assertEquals(ConnectionVerdict.TRANSIENT, Connection.classify(503, "token_revoked"))
    }

    @Test
    fun `websocket close 4401 follows the same rule`() {
        assertEquals(ConnectionVerdict.NEEDS_PAIRING, Connection.classify(null, "token_revoked", Connection.WS_UNAUTHORIZED))
        assertEquals(ConnectionVerdict.TRANSIENT, Connection.classify(null, null, Connection.WS_UNAUTHORIZED))
        assertEquals(ConnectionVerdict.TRANSIENT, Connection.classify(null, "token_revoked", 1006))
    }
}

class BackoffTest {
    @Test
    fun `starts at one second and doubles`() {
        assertEquals(1_000, Backoff.delayMillis(0, 0.0))
        assertEquals(2_000, Backoff.delayMillis(1, 0.0))
        assertEquals(4_000, Backoff.delayMillis(2, 0.0))
        assertEquals(256_000, Backoff.delayMillis(8, 0.0))
    }

    @Test
    fun `is capped at five minutes and never overflows`() {
        assertEquals(300_000, Backoff.delayMillis(9, 0.0))
        assertEquals(300_000, Backoff.delayMillis(50, 0.0))
        assertEquals(300_000, Backoff.delayMillis(Int.MAX_VALUE, 0.0))
    }

    @Test
    fun `jitter adds at most twenty percent`() {
        assertEquals(1_200, Backoff.delayMillis(0, 1.0))
        assertTrue(Backoff.delayMillis(20, 0.999) <= 360_000)
        assertTrue(Backoff.delayMillis(3, 0.5) in 8_000..9_600)
    }
}
