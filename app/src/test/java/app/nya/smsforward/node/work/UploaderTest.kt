package app.nya.smsforward.node.work

import app.nya.smsforward.node.EchoUploadApi
import app.nya.smsforward.node.FakeApi
import app.nya.smsforward.node.MemoryTokens
import app.nya.smsforward.node.data.NewOutboxItem
import app.nya.smsforward.node.data.OutboxState
import app.nya.smsforward.node.httpFailure
import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.UploadOutcome
import app.nya.smsforward.node.networkFailure
import app.nya.smsforward.node.newOutbox
import app.nya.smsforward.node.pairedSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UploaderTest {
    private val box = newOutbox()
    private val settings = pairedSettings()
    private val tokens = MemoryTokens("nsf_tok")
    private var now = 5_000_000L

    private fun queue(n: Int) = repeat(n) { i -> box.enqueue(NewOutboxItem("sha256:$i", "106", "body $i", 1, 1_000L + i), now) }
    private fun uploader(api: app.nya.smsforward.node.net.NodeApi) = Uploader(box, api, settings, tokens) { now }

    @Test
    fun `everything accepted leaves the queue and is recorded`() = runBlocking {
        queue(3)
        val api = EchoUploadApi()
        val result = uploader(api).flush()

        assertEquals(FlushResult.Done(3), result)
        assertEquals(0, box.pendingCount())
        assertEquals(1, api.batches.size)
        assertEquals(now, settings.lastUploadAt)
        assertEquals(null, settings.lastError)
        assertEquals(List(3) { OutboxState.DONE }, box.recent(10).map { it.state })
    }

    @Test
    fun `a duplicate answer also counts as delivered`() = runBlocking {
        queue(2)
        assertEquals(FlushResult.Done(2), uploader(EchoUploadApi(status = "duplicate")).flush())
        assertEquals(0, box.pendingCount())
    }

    @Test
    fun `a rejected message is retired with its reason instead of blocking the queue forever`() = runBlocking {
        queue(2)
        assertEquals(FlushResult.Done(2), uploader(EchoUploadApi(status = "rejected", error = "bad_body")).flush())
        assertEquals(0, box.pendingCount())
        val recent = box.recent(10)
        assertEquals(List(2) { OutboxState.DEAD }, recent.map { it.state })
        assertEquals("bad_body", recent[0].error)
    }

    @Test
    fun `a mixed batch settles each message on its own`() = runBlocking {
        queue(3)
        val api = FakeApi().apply {
            uploads += ApiResult.Ok(
                listOf(
                    UploadOutcome("sha256:0", "accepted"),
                    UploadOutcome("sha256:1", "rejected", error = "bad_peer"),
                    // sha256:2 missing from the answer: it must stay queued
                ),
            )
            uploads += ApiResult.Ok(emptyList()) // second round: nothing settled -> stop, do not spin
        }
        val result = uploader(api).flush()
        assertIs<FlushResult.Retry>(result)
        assertEquals(1, box.pendingCount())
        assertEquals("sha256:2", box.pending(1).single().dedupeKey)
    }

    @Test
    fun `unknown statuses from a newer server keep the message queued`() = runBlocking {
        queue(1)
        val result = uploader(EchoUploadApi(status = "quarantined")).flush()
        assertIs<FlushResult.Retry>(result)
        assertEquals(1, box.pendingCount())
    }

    @Test
    fun `a network failure keeps every message and asks for a retry`() = runBlocking {
        queue(3)
        val api = FakeApi().apply { uploads += networkFailure }
        val result = uploader(api).flush()

        assertIs<FlushResult.Retry>(result)
        assertEquals(3, box.pendingCount())
        assertTrue(settings.lastError!!.contains("无法连接"))
        assertTrue(!settings.needsPairing, "a network error must never unpair the phone")
    }

    @Test
    fun `server errors and rate limits are retried without unpairing`() = runBlocking {
        for (status in listOf(429, 500, 503)) {
            val box2 = newOutbox().also { it.enqueue(NewOutboxItem("k", "1", "b", 1, 1), 1) }
            val api = FakeApi().apply { uploads += httpFailure(status) }
            val result = Uploader(box2, api, settings, tokens) { now }.flush()
            assertIs<FlushResult.Retry>(result, "status $status")
            assertEquals(1, box2.pendingCount())
            assertTrue(!settings.needsPairing)
        }
    }

    @Test
    fun `a bare 401 from a proxy does not unpair either`() = runBlocking {
        queue(1)
        val api = FakeApi().apply { uploads += httpFailure(401) }
        assertIs<FlushResult.Retry>(uploader(api).flush())
        assertTrue(!settings.needsPairing)
        assertEquals(1, box.pendingCount())
    }

    @Test
    fun `a revoked token pauses uploading and keeps the whole queue`() = runBlocking {
        queue(5)
        val api = FakeApi().apply { uploads += httpFailure(401, "token_revoked") }
        val result = uploader(api).flush()

        assertEquals(FlushResult.NeedsPairing, result)
        assertTrue(settings.needsPairing)
        assertEquals(5, box.pendingCount(), "nothing may be discarded when the token dies")

        // While unpaired, no request is made at all.
        val silent = FakeApi()
        assertEquals(FlushResult.NeedsPairing, uploader(silent).flush())
        assertTrue(silent.uploadCalls.isEmpty())
    }

    @Test
    fun `after re-pairing the queued messages go out`() = runBlocking {
        queue(2)
        settings.needsPairing = true
        assertEquals(FlushResult.NeedsPairing, uploader(EchoUploadApi()).flush())

        settings.needsPairing = false // what PairingManager does on success
        assertEquals(FlushResult.Done(2), uploader(EchoUploadApi()).flush())
    }

    @Test
    fun `a phone that is not paired has nothing to upload to`() = runBlocking {
        queue(1)
        val api = FakeApi()
        assertEquals(FlushResult.NotPaired, Uploader(box, api, settings, MemoryTokens(null)) { now }.flush())
        assertEquals(FlushResult.NotPaired, Uploader(box, api, pairedSettings().apply { deviceId = null }, tokens) { now }.flush())
        assertTrue(api.uploadCalls.isEmpty())
        assertEquals(1, box.pendingCount())
    }

    @Test
    fun `batches are at most 100 and go out oldest first`() = runBlocking {
        queue(250)
        val api = EchoUploadApi()
        assertEquals(FlushResult.Done(250), uploader(api).flush())

        assertEquals(listOf(100, 100, 50), api.batches.map { it.size })
        assertEquals("sha256:0", api.batches[0].first().dedupeKey)
        assertEquals("sha256:249", api.batches[2].last().dedupeKey)
        val sent = api.batches.first().first()
        assertEquals("in", sent.direction)
        assertEquals("body 0", sent.body)
    }

    @Test
    fun `each attempt is counted so a stuck message is visible`() = runBlocking {
        queue(1)
        val api = FakeApi().apply { uploads += networkFailure; uploads += networkFailure }
        uploader(api).flush()
        uploader(api).flush()
        assertEquals(2, box.pending(1).single().attempts)
    }

    @Test
    fun `the token and the address come from settings at flush time`() = runBlocking {
        queue(1)
        val api = FakeApi().apply { uploads += ApiResult.Ok(listOf(UploadOutcome("sha256:0", "accepted"))) }
        settings.serverUrl = "https://other.example.com"
        uploader(api).flush()
        assertEquals("https://other.example.com" to "nsf_tok", api.uploadCalls.single().let { it.first to it.second })
    }
}
