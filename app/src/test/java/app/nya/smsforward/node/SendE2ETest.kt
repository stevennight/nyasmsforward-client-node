package app.nya.smsforward.node

import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.SqliteOutbox
import app.nya.smsforward.node.data.SqliteSendLedger
import app.nya.smsforward.node.net.ChannelState
import app.nya.smsforward.node.net.Frames
import app.nya.smsforward.node.net.HelloFrame
import app.nya.smsforward.node.net.NodeSocket
import app.nya.smsforward.node.net.OkHttpNodeApi
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.net.SocketConfig
import app.nya.smsforward.node.net.SocketEnd
import app.nya.smsforward.node.net.SocketHandler
import app.nya.smsforward.node.node.IncomingHandler
import app.nya.smsforward.node.node.PairResult
import app.nya.smsforward.node.node.PairingManager
import app.nya.smsforward.node.policy.SendPolicy
import app.nya.smsforward.node.send.SendCoordinator
import app.nya.smsforward.node.send.SendGate
import app.nya.smsforward.node.send.SendStatusTracker
import app.nya.smsforward.node.send.SendTask
import app.nya.smsforward.node.send.SimChoice
import app.nya.smsforward.node.send.SmsSender
import app.nya.smsforward.node.sms.SmsPart
import app.nya.smsforward.node.work.Uploader
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue

/**
 * The send channel (WebSocket, gate, coordinator, ledger) against a REAL server, with a loopback "radio" instead of
 * SmsManager. Skipped unless NYASMS_E2E_URL is set; see [RealServerE2ETest] for how to start the server.
 */
class SendE2ETest {
    private val baseUrl = System.getenv("NYASMS_E2E_URL")?.trimEnd('/')
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()
    private var cookie = ""

    private fun admin(method: String, path: String, body: String? = null): Pair<Int, JsonObject> {
        val b = Request.Builder().url("$baseUrl$path").header("X-NyaSms-CSRF", "1")
        if (cookie.isNotEmpty()) b.header("Cookie", cookie)
        b.method(method, body?.toRequestBody(jsonType) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(jsonType))
        http.newCall(b.build()).execute().use { r ->
            r.headers("Set-Cookie").firstOrNull { it.startsWith("nyasms_session=") }?.let { cookie = it.substringBefore(';') }
            val text = r.body?.string().orEmpty()
            return r.code to (if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject)
        }
    }

    private fun ensureAdmin() {
        val (status, _) = admin("POST", "/api/auth/setup", """{"password":"e2e-test-password-123"}""")
        if (status == 409) assertEquals(200, admin("POST", "/api/auth/login", """{"password":"e2e-test-password-123"}""").first) else assertEquals(201, status)
    }

    /** Waits (up to 15 s) until [check] holds; [what] names the expectation in the timeout failure. */
    private suspend fun eventually(what: String, check: () -> Boolean) {
        withTimeout(15_000) { while (!check()) delay(25) }
    }

    /** A radio that "sends" instantly and reports back on another thread, like the system's PendingIntents do. */
    private class LoopbackRadio(private val sims: List<SimInfo>) : SmsSender {
        val sent = CopyOnWriteArrayList<Pair<SendTask, SimChoice>>()
        lateinit var coordinator: SendCoordinator
        override fun canSend() = true
        override fun canReadSims() = true
        override fun activeSims() = sims
        override fun send(task: SendTask, sim: SimChoice) {
            sent += task to sim
            thread {
                Thread.sleep(50)
                coordinator.onPartSent(task.taskId, 0, 1, null)
                Thread.sleep(50)
                coordinator.onPartDelivered(task.taskId, 0, 1, true)
            }
        }
    }

    @Test
    fun `replies and new messages go out through the real WebSocket, and the phone's own policy has the last word`() = runBlocking<Unit> {
        assumeTrue("set NYASMS_E2E_URL to run", baseUrl != null)
        ensureAdmin()
        val clock = { System.currentTimeMillis() }

        // --- a paired phone that has received one message from a mainland number ---
        val settings = MemorySettings().apply { sendPolicy = SendPolicy.REPLY }
        val tokens = MemoryTokens()
        val db = JdbcSqlDb()
        val box = SqliteOutbox(db)
        val ledger = SqliteSendLedger(db)
        val sims = listOf(SimInfo(1, 3, "中国移动"), SimInfo(2, 4, "中国联通"))
        val api = OkHttpNodeApi()
        val pairing = PairingManager(api, settings, tokens, appVersion = "0.2.0", defaultDeviceName = "E2E", sims = { sims }, onPaired = {})
        val code = admin("POST", "/api/v1/pairings", """{"kind":"phone"}""").second.getValue("code").jsonPrimitive.content
        assertEquals(PairResult.Success, pairing.pair(baseUrl!!, code, "E2E 发送测试"))
        val deviceId = settings.deviceId!!

        IncomingHandler(box, settings, clock).onReceived(listOf(SmsPart("+86 138 0000 0000", "验证码 583921", clock() - 5_000)), simSlot = 1)
        assertEquals(1, Uploader(box, api, settings, tokens, clock).flush().let { (it as app.nya.smsforward.node.work.FlushResult.Done).uploaded })
        val messageId = admin("GET", "/api/v1/messages?device=$deviceId").second.getValue("items").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content

        // --- the send channel, wired as in the app: socket -> coordinator -> radio, receipts back over the socket ---
        val radio = LoopbackRadio(sims)
        lateinit var socket: NodeSocket
        val coordinator = SendCoordinator(SendGate(settings, box, ledger, clock), ledger, radio, SendStatusTracker(), clock) { socket.send(Frames.result(it)) }
        radio.coordinator = coordinator
        val states = CopyOnWriteArrayList<ChannelState>()
        socket = NodeSocket(
            OkHttpClient(),
            config = { SocketConfig(baseUrl, tokens.token!!) },
            hello = { HelloFrame("0.2.0", 90, settings.sendPolicy.wire, sims = sims) },
            handler = object : SocketHandler {
                override fun onOpen() { (coordinator.settleStale() + coordinator.receiptsToReplay()).forEach { socket.send(Frames.result(it)) } }
                override fun onSendSms(task: SendTask) { thread { coordinator.onTask(task) } }
            },
            onState = { states += it },
        )
        val running = async(Dispatchers.Default) { socket.run() }

        fun device() = admin("GET", "/api/v1/devices").second.getValue("items").jsonArray.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == deviceId }
        fun task(id: String) = admin("GET", "/api/v1/outbound/$id").second
        fun createReply(body: String) = admin("POST", "/api/v1/outbound", """{"replyToMessageId":$messageId,"body":"$body"}""")
        fun createNew(to: String, body: String, sim: Int? = null) =
            admin("POST", "/api/v1/outbound", """{"deviceId":"$deviceId","to":"$to","body":"$body"${sim?.let { ""","simSlot":$it""" } ?: ""}}""")

        eventually("the phone shows up online with its own policy") { device()["online"]?.jsonPrimitive?.booleanOrNull == true && device()["phoneSendPolicy"]?.jsonPrimitive?.content == "reply" }
        assertEquals("off", device().getValue("effectivePolicy").jsonPrimitive.content, "the platform default is off, and the stricter side wins")

        // 1. the platform allows replies: the reply leaves from the SIM the message came in on, to the same number
        assertEquals(200, admin("PATCH", "/api/v1/devices/$deviceId", """{"sendPolicy":"reply"}""").first)
        val created = createReply("TD")
        assertEquals(201, created.first, created.second.toString())
        val taskId = created.second.getValue("taskId").jsonPrimitive.content
        eventually("the reply is delivered") { task(taskId).getValue("status").jsonPrimitive.content == "delivered" }
        val (sentTask, sentSim) = radio.sent.single()
        assertEquals("+8613800000000", sentTask.to, "the number as the phone reported it, formatting removed")
        assertEquals("TD", sentTask.body)
        assertEquals(SimChoice.Subscription(3), sentSim, "slot 1 of the original message is subscription 3")
        val outMessage = admin("GET", "/api/v1/messages?device=$deviceId&direction=out").second.getValue("items").jsonArray.single().jsonObject
        assertEquals("platform", outMessage.getValue("origin").jsonPrimitive.content)
        assertEquals(messageId, outMessage.getValue("replyTo").jsonPrimitive.content)

        // 2. new messages: refused by the server while either side says "reply"...
        admin("PATCH", "/api/v1/devices/$deviceId", """{"sendPolicy":"any"}""")
        assertEquals(403, createNew("13900000000", "你好").first)

        // ...allowed once the phone's owner switches it to "any" and the phone reports it
        settings.sendPolicy = SendPolicy.ANY
        assertTrue(socket.sendHello())
        eventually("the server learns the new phone policy") { device().getValue("effectivePolicy").jsonPrimitive.content == "any" }
        val fresh = createNew("139 0000 0000", "你好", sim = 2)
        assertEquals(201, fresh.first, fresh.second.toString())
        val freshId = fresh.second.getValue("taskId").jsonPrimitive.content
        eventually("the new message is delivered") { task(freshId).getValue("status").jsonPrimitive.content == "delivered" }
        assertEquals(SimChoice.Subscription(4), radio.sent.last().second)
        assertEquals("13900000000", radio.sent.last().first.to)

        // 3. the phone enforces its policy even when the server has not heard about a change: back to "reply" locally
        //    without a hello, so the server still believes "any" and hands over a new-message task
        settings.sendPolicy = SendPolicy.REPLY
        val sneaky = createNew("13700000000", "hi")
        assertEquals(201, sneaky.first)
        val sneakyId = sneaky.second.getValue("taskId").jsonPrimitive.content
        eventually("the phone refuses") { task(sneakyId).getValue("status").jsonPrimitive.content == "failed" }
        assertEquals("policy_denied", task(sneakyId).getValue("error").jsonPrimitive.content)
        assertEquals(2, radio.sent.size, "nothing was sent for the refused task")

        // 4. revoking the token closes the channel with the documented code, and the phone stops instead of retrying
        assertEquals(204, admin("DELETE", "/api/v1/devices/$deviceId").first)
        assertEquals(SocketEnd.NEEDS_PAIRING, withTimeout(15_000) { running.await() })
        assertEquals(ChannelState.NeedsPairing, states.last())
    }
}
