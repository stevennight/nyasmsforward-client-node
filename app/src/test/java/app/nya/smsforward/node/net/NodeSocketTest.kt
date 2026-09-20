package app.nya.smsforward.node.net

import app.nya.smsforward.node.send.ReceiptStatus
import app.nya.smsforward.node.send.SendReceipt
import app.nya.smsforward.node.send.SendTask
import app.nya.smsforward.node.send.TaskMode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class FramesTest {
    @Test
    fun `hello carries the phone's own policy and SIMs`() {
        val text = Frames.hello(HelloFrame("0.2.0", 80, "reply", sims = listOf(SimInfo(1, 3, "移动"))))
        assertTrue("\"type\":\"hello\"" in text && "\"sendPolicy\":\"reply\"" in text && "\"subscriptionId\":3" in text && "\"battery\":80" in text, text)
    }

    @Test
    fun `a receipt is a send_result frame`() {
        val text = Frames.result(SendReceipt("t_1", ReceiptStatus.FAILED, "sim_unavailable"))
        assertEquals("""{"taskId":"t_1","status":"failed","error":"sim_unavailable","type":"send_result"}""", text)
        assertEquals("""{"taskId":"t_1","status":"sent","type":"send_result"}""", Frames.result(SendReceipt("t_1", ReceiptStatus.SENT)))
    }

    @Test
    fun `a send_sms frame becomes a task, extra fields are ignored`() {
        val frame = Frames.parse(
            """{"type":"send_sms","taskId":"t_1","mode":"reply","simSlot":2,"to":"+8613800000000","body":"TD","replyToMessageId":9,"expiresAt":1789830600000,"future":true}""",
        )
        assertEquals(ServerFrame.SendSms(SendTask("t_1", TaskMode.REPLY, 2, "+8613800000000", "TD", 1789830600000)), frame)
        val noSim = Frames.parse("""{"type":"send_sms","taskId":"t_2","mode":"new","simSlot":null,"to":"13900000000","body":"你好","expiresAt":5}""")
        assertIs<ServerFrame.SendSms>(noSim)
        assertEquals(null, noSim.task.simSlot)
    }

    @Test
    fun `anything else is unknown, never an error`() {
        for (text in listOf(
            """{"type":"ping"}""", "not json", "{}", "[]",
            """{"type":"send_sms","taskId":"t","mode":"weird","to":"1","body":"x","expiresAt":1}""",
            """{"type":"send_sms","taskId":"t","mode":"reply","to":"1","body":"","expiresAt":1}""",
            """{"type":"send_sms","mode":"reply","to":"1","body":"x","expiresAt":1}""",
        )) {
            assertEquals(ServerFrame.Unknown, Frames.parse(text), text)
        }
    }
}

class NodeSocketTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @AfterTest
    fun tearDown() = server.shutdown()

    /** The server side of one connection. */
    private class Peer : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val opened = LinkedBlockingQueue<WebSocket>()
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.add(webSocket) }
        override fun onMessage(webSocket: WebSocket, text: String) { received.add(text) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        fun next(): String = received.poll(5, TimeUnit.SECONDS) ?: error("no frame within 5s")
        fun socket(): WebSocket = opened.poll(5, TimeUnit.SECONDS) ?: error("no connection within 5s")
    }

    private class Recorder : SocketHandler {
        val tasks = LinkedBlockingQueue<SendTask>()
        var opens = 0
        override fun onOpen() { opens++ }
        override fun onSendSms(task: SendTask) { tasks.add(task) }
    }

    private class Setup(
        val states: MutableList<ChannelState> = java.util.Collections.synchronizedList(mutableListOf()),
        val pauses: MutableList<Long> = mutableListOf(),
        val handler: Recorder = Recorder(),
    )

    private fun socket(setup: Setup, attempts: Int = Int.MAX_VALUE, token: () -> String = { "nsf_tok" }): NodeSocket {
        var left = attempts
        return NodeSocket(
            client = client,
            config = { if (left-- > 0) SocketConfig(server.url("/").toString().trimEnd('/'), token()) else null },
            hello = { HelloFrame("0.2.0", 55, "reply", sims = listOf(SimInfo(1, 1, "A"))) },
            handler = setup.handler,
            onState = { setup.states += it },
            pause = { setup.pauses += it },
            random = { 0.0 },
        )
    }

    private fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun `connects with the token in the header, says hello first, receives tasks and sends receipts`() {
        val peer = Peer()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        val setup = Setup()
        val ws = socket(setup, attempts = 1)

        run {
            val ended = async(Dispatchers.Default) { ws.run() }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer nsf_tok", request.getHeader("Authorization"))
            assertEquals("/api/v1/device/ws", request.path)
            assertTrue(request.requestUrl.toString().contains("nsf_tok").not(), "the token must never be in the URL")

            val hello = peer.next()
            assertTrue(""""type":"hello"""" in hello && """"sendPolicy":"reply"""" in hello && """"battery":55""" in hello, hello)

            val server = peer.socket()
            server.send("""{"type":"send_sms","taskId":"t_1","mode":"reply","simSlot":1,"to":"+8613800000000","body":"TD","expiresAt":9999999999999}""")
            server.send("""{"type":"something_new"}""") // must not disturb anything
            val task = setup.handler.tasks.poll(5, TimeUnit.SECONDS)
            assertNotNull(task)
            assertEquals("t_1", task.taskId)

            assertTrue(ws.send(Frames.result(SendReceipt("t_1", ReceiptStatus.SENT))))
            assertEquals("""{"taskId":"t_1","status":"sent","type":"send_result"}""", peer.next())

            server.close(1000, "bye") // an ordinary close: the phone reconnects (here: the config is gone, so it stops)
            assertEquals(SocketEnd.STOPPED, ended.await())
        }
        assertEquals(1, setup.handler.opens)
        assertTrue(ChannelState.Connected in setup.states)
    }

    @Test
    fun `a close with 4401 and a dead-token reason means needs pairing`() {
        val peer = Peer()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        val setup = Setup()
        run {
            val ended = async(Dispatchers.Default) { socket(setup).run() }
            peer.next() // hello
            peer.socket().close(4401, "token_revoked")
            assertEquals(SocketEnd.NEEDS_PAIRING, ended.await())
        }
        assertEquals(ChannelState.NeedsPairing, setup.states.last())
        assertTrue(setup.pauses.isEmpty(), "no retry after an explicit revocation")
    }

    @Test
    fun `an upgrade refused with 401 token_revoked means needs pairing`() {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody("""{"error":"token_revoked"}"""))
        run { assertEquals(SocketEnd.NEEDS_PAIRING, socket(Setup()).run()) }
    }

    @Test
    fun `a bare 401 from a proxy keeps the token and retries with growing delays`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(401).setBody("<html>Basic auth</html>")) }
        val setup = Setup()
        run { assertEquals(SocketEnd.STOPPED, socket(setup, attempts = 4).run()) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), setup.pauses)
        assertTrue(setup.states.any { it is ChannelState.Waiting && "地址" in it.reason }, "the address hint is shown")
        assertTrue(ChannelState.NeedsPairing !in setup.states)
    }

    @Test
    fun `server errors and a dead server keep the token and back off`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(502))
        val setup = Setup()
        run { assertEquals(SocketEnd.STOPPED, socket(setup, attempts = 2).run()) }
        assertEquals(listOf(1_000L, 2_000L), setup.pauses)

        server.shutdown() // nothing listens any more: connection refused
        val dead = Setup()
        val url = server.url("/").toString().trimEnd('/')
        run {
            var left = 3
            val ended = NodeSocket(
                client, { if (left-- > 0) SocketConfig(url, "t") else null }, { HelloFrame("x", null, "off") }, dead.handler,
                onState = { dead.states += it }, pause = { dead.pauses += it }, random = { 0.0 },
            ).run()
            assertEquals(SocketEnd.STOPPED, ended)
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L), dead.pauses)
    }

    @Test
    fun `the token is read again for every attempt, so a fresh pairing is picked up`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        var token = "nsf_old"
        val setup = Setup()
        run {
            val ws = NodeSocket(
                client, { SocketConfig(server.url("/").toString().trimEnd('/'), token) }, { HelloFrame("x", null, "off") }, setup.handler,
                onState = { setup.states += it }, pause = { setup.pauses += it; token = "nsf_new" }, random = { 0.0 },
            )
            val stopper = async(Dispatchers.Default) { runCatching { ws.run() } }
            assertEquals("Bearer nsf_old", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals("Bearer nsf_new", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            stopper.cancel()
        }
    }
}
