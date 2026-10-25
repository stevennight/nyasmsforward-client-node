package app.nya.smsforward.node

import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.OkHttpNodeApi
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.node.IncomingHandler
import app.nya.smsforward.node.node.PairResult
import app.nya.smsforward.node.node.PairingManager
import app.nya.smsforward.node.sms.SmsPart
import app.nya.smsforward.node.view.EventStream
import app.nya.smsforward.node.view.MemoryViewSettings
import app.nya.smsforward.node.view.OkHttpViewApi
import app.nya.smsforward.node.view.ServerEvent
import app.nya.smsforward.node.view.ViewConnection
import app.nya.smsforward.node.view.ViewController
import app.nya.smsforward.node.view.ViewResult
import app.nya.smsforward.node.view.sourceLabel
import app.nya.smsforward.node.work.FlushResult
import app.nya.smsforward.node.work.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The 「查看」 feature against a REAL server: a receiver reports, the same phone logs in as a client (account login and
 * a client pairing code), lists the conversation labelled 「本机」, opens it, follows the live stream, marks read and
 * unread. Skipped unless NYASMS_E2E_URL is set (see RealServerE2ETest for how to start a server).
 */
class ViewE2ETest {
    private val baseUrl = System.getenv("NYASMS_E2E_URL")?.trimEnd('/')
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var cookie = ""

    private fun admin(method: String, path: String, body: String? = null): Pair<Int, JsonObject> {
        val b = Request.Builder().url("$baseUrl$path").header("X-NyaSms-CSRF", "1")
        if (cookie.isNotEmpty()) b.header("Cookie", cookie)
        b.method(method, body?.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            r.headers("Set-Cookie").firstOrNull { it.startsWith("nyasms_session=") }?.let { cookie = it.substringBefore(';') }
            val text = r.body?.string().orEmpty()
            return r.code to (if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject)
        }
    }

    private fun ensureAdmin() {
        val (status, _) = admin("POST", "/api/auth/setup", """{"password":"$PASSWORD"}""")
        if (status == 409) assertEquals(200, admin("POST", "/api/auth/login", """{"password":"$PASSWORD"}""").first) else assertEquals(201, status)
    }

    private fun pairingCode(kind: String): String {
        val (status, out) = admin("POST", "/api/v1/pairings", """{"kind":"$kind"}""")
        assertEquals(201, status, out.toString())
        return out.getValue("display").jsonPrimitive.content
    }

    @Test
    fun `view the platform as a client of the same server`() = runBlocking<Unit> {
        assumeTrue("set NYASMS_E2E_URL to run", baseUrl != null)
        ensureAdmin()
        val now = System.currentTimeMillis()

        // --- the receiver side of this phone: pair and report one message ---
        val nodeSettings = MemorySettings()
        val nodeTokens = MemoryTokens()
        val box = newOutbox()
        val nodeApi = OkHttpNodeApi()
        val pairing = PairingManager(nodeApi, nodeSettings, nodeTokens, "0.6.0", "接收端", sims = { listOf(SimInfo(1, 3, "中国移动", "13800001234")) }, onPaired = {})
        assertEquals(PairResult.Success, pairing.pair(baseUrl!!, pairingCode("phone"), "E2E 查看手机"))
        val peer = "1069${now % 100_000}"
        IncomingHandler(box, nodeSettings) { now }.onReceived(listOf(SmsPart(peer, "【E2E】验证码 246810，5 分钟内有效。", now - 1_000)), simSlot = 1)
        assertEquals(FlushResult.Done(1), Uploader(box, nodeApi, nodeSettings, nodeTokens) { now }.flush())

        // --- the 「查看」 side: its own identity ---
        val settings = MemoryViewSettings()
        val tokens = MemoryTokens()
        val idle = CoroutineScope(Job().apply { cancel() }) // load() is driven by the test
        val view = ViewController(OkHttpViewApi(), settings, tokens, idle, "0.6.0", "E2E（查看）", ownDeviceId = { nodeSettings.deviceId }, suggestedServer = { baseUrl })

        // A receiver's pairing code is refused for a client, without being used up.
        val phoneCode = pairingCode("phone")
        val wrong = assertIs<ViewResult.Error>(view.connectWithCode(baseUrl, phoneCode, ""))
        assertTrue("接收端的配对码" in wrong.message, wrong.message)
        // The admin password works; a client pairing code too (it replaces the identity).
        assertIs<ViewResult.Ok>(view.login(baseUrl, PASSWORD, "", ""))
        assertIs<ViewResult.Ok>(view.connectWithCode(baseUrl, pairingCode("client"), "E2E 客户端"))
        assertEquals(ViewConnection.CONNECTED, view.state.value.connection)
        assertTrue("read" in view.state.value.scopes)
        assertIs<ViewResult.Ok>(view.test())

        // The list: the reported message, labelled as this phone's.
        view.load()
        val s = view.state.value
        val thread = s.conversations.first { it.peerKey == peer }
        assertTrue(thread.unread >= 1)
        assertTrue(sourceLabel(thread.deviceId, thread.cardNumber, s).startsWith("本机"), sourceLabel(thread.deviceId, thread.cardNumber, s))
        val messages = assertIs<ApiResult.Ok<List<app.nya.smsforward.node.view.RemoteMessage>>>(view.loadThread(thread)).value
        assertEquals("246810", messages.single().code)

        // The live stream: a new report arrives as a message event.
        val events = Channel<ServerEvent>(Channel.UNLIMITED)
        val connected = Channel<Unit>(Channel.CONFLATED)
        val stream = EventStream(
            OkHttpViewApi(), endpoint = { baseUrl to tokens.token!! },
            onEvent = { events.trySend(it) }, onConnected = { connected.trySend(Unit) }, pause = {},
        )
        val job = launch(Dispatchers.IO) { stream.run() }
        withTimeout(10_000) { connected.receive() }
        IncomingHandler(box, nodeSettings) { now + 1 }.onReceived(listOf(SmsPart(peer, "第二条 E2E 短信", now)), simSlot = 1)
        assertEquals(FlushResult.Done(1), Uploader(box, nodeApi, nodeSettings, nodeTokens) { now + 1 }.flush())
        val live = withTimeout(10_000) {
            var e: ServerEvent
            do e = events.receive() while (e !is ServerEvent.Message || e.message.peerKey != peer)
            e
        }
        assertEquals("第二条 E2E 短信", live.message.body)
        assertTrue(live.notify)

        // Read and unread go to the server and come back on the stream.
        val changed = assertIs<ApiResult.Ok<List<Long>>>(view.markThreadRead(thread)).value
        assertEquals(2, changed.size)
        withTimeout(10_000) { do { val e = events.receive() } while (e !is ServerEvent.Read) }
        assertIs<ApiResult.Ok<Unit>>(view.markUnread(live.message.id))
        stream.stop()
        job.cancel()

        // Forgetting the client identity leaves the receiver untouched.
        view.disconnect()
        assertEquals(ViewConnection.NOT_CONNECTED, view.state.value.connection)
        assertTrue(nodeTokens.token != null)
    }

    private companion object {
        const val PASSWORD = "e2e-test-password-123"
    }
}
