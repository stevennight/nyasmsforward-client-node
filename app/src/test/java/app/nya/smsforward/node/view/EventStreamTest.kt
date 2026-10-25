package app.nya.smsforward.node.view

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventStreamTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun stream(events: MutableList<ServerEvent>, statuses: MutableList<StreamStatus>, connected: IntArray) = EventStream(
        OkHttpViewApi(),
        endpoint = { base to "nsf_c" },
        onEvent = { events += it },
        onConnected = { connected[0]++ },
        onStatus = { statuses += it },
        random = { 0.0 },
        pause = {}, // no real waiting between attempts
    )

    @Test
    fun `reconnects with the last id and stops when the token is revoked`() = runBlocking {
        server.enqueue(
            sse(
                "retry: 3000\n\n:keepalive\n\n" +
                    "id: 1042\nevent: message\ndata: {\"id\":1042,\"deviceId\":\"d1\",\"direction\":\"in\",\"peer\":\"10086\",\"body\":\"x\",\"deviceTime\":1,\"notify\":true}\n\n" +
                    "event: read\ndata: {\"ids\":[1042]}\n\n",
            ),
        ) // then the server closes the stream
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"token_revoked"}"""))

        val events = mutableListOf<ServerEvent>()
        val statuses = mutableListOf<StreamStatus>()
        val connected = IntArray(1)
        val s = stream(events, statuses, connected)
        s.run() // returns once the token is dead

        assertEquals(2, events.size)
        assertEquals(1042, (events[0] as ServerEvent.Message).message.id)
        assertEquals(ServerEvent.Read(listOf(1042)), events[1])
        assertEquals(1, connected[0])
        assertEquals(StreamStatus.TOKEN_DEAD, s.status)
        assertEquals(StreamStatus.LIVE, statuses[1])

        assertNull(server.takeRequest().getHeader("Last-Event-ID"), "a first connection is not replayed")
        assertEquals("1042", server.takeRequest().getHeader("Last-Event-ID"))
        assertEquals("1042", server.takeRequest().getHeader("Last-Event-ID"))
    }

    @Test
    fun `a token without the read permission stops the stream`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"scope_denied","message":"read"}"""))
        val s = stream(mutableListOf(), mutableListOf(), IntArray(1))
        s.run()
        assertEquals(StreamStatus.FORBIDDEN, s.status)
    }

    @Test
    fun `nothing to connect to means idle`() = runBlocking {
        val s = EventStream(OkHttpViewApi(), endpoint = { null }, onEvent = {}, pause = {})
        s.run()
        assertEquals(StreamStatus.IDLE, s.status)
        assertEquals(0, server.requestCount)
    }
}
