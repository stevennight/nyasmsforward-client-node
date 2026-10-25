package app.nya.smsforward.node.view

import app.nya.smsforward.node.MemoryTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryViewSettings : ViewSettings {
    override var enabled = false
    override var serverUrl: String? = null
    override var deviceId: String? = null
    override var deviceName: String? = null
    override var scopes: Set<String> = emptySet()
    override var needsLogin = false
}

class ViewControllerTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val settings = MemoryViewSettings()
    private val tokens = MemoryTokens()

    // A cancelled scope: the debounced refreshes the controller schedules never run, so each test drives load() itself.
    private val idle = CoroutineScope(Job().apply { cancel() })
    private val view = ViewController(
        OkHttpViewApi(), settings, tokens, idle, appVersion = "0.6.0", defaultName = "Pixel（查看）",
        ownDeviceId = { "d_me" }, suggestedServer = { base },
    )

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `a client pairing code logs in and switches the feature on`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read","reply","send","delete"]}"""))
        assertIs<ViewResult.Ok>(view.connectWithCode(base, "483 920", ""))

        assertEquals("nsf_c", tokens.token)
        assertEquals(base, settings.serverUrl)
        assertEquals("d_c", settings.deviceId)
        assertEquals("Pixel（查看）", settings.deviceName, "an empty name falls back to the default")
        assertTrue(settings.enabled)
        val s = view.state.value
        assertEquals(ViewConnection.CONNECTED, s.connection)
        assertEquals(setOf("read", "reply", "send", "delete"), s.scopes)
    }

    @Test
    fun `form mistakes are pinned to their field, before anything is sent`() = runBlocking {
        assertEquals(ViewResult.Field.CODE, (view.connectWithCode(base, "12345", "x") as ViewResult.Error).field)
        assertEquals(ViewResult.Field.URL, (view.connectWithCode("ftp://x", "123456", "x") as ViewResult.Error).field)
        assertEquals(ViewResult.Field.PASSWORD, (view.login(base, "", "", "x") as ViewResult.Error).field)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `server refusals become words`() = runBlocking {
        server.enqueue(json("""{"error":"pair_kind_mismatch"}""", 400))
        server.enqueue(json("""{"error":"invalid_credentials"}""", 401))
        server.enqueue(json("""{"error":"totp_required"}""", 401))

        val mismatch = view.connectWithCode(base, "123456", "x") as ViewResult.Error
        assertTrue("接收端的配对码" in mismatch.message, mismatch.message)
        assertEquals(ViewResult.Field.PASSWORD, (view.login(base, "wrong", "", "x") as ViewResult.Error).field)
        val totp = view.login(base, "pw", "", "x") as ViewResult.Error
        assertEquals(ViewResult.Field.TOTP, totp.field)
        assertNull(tokens.token)
    }

    @Test
    fun `a revoked client token asks for a new login and is forgotten`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read"]}"""))
        view.connectWithCode(base, "123456", "x")
        server.enqueue(json("""{"error":"token_revoked"}""", 401))

        assertIs<ViewResult.Error>(view.test())
        assertEquals(ViewConnection.NEEDS_LOGIN, view.state.value.connection)
        assertNull(tokens.token)
    }

    @Test
    fun `the list comes with phone and card names, and the phone filters apply locally`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read"]}"""))
        view.connectWithCode(base, "123456", "x")
        server.takeRequest()

        fun conv(device: String, card: String?, peer: String, id: Long) =
            """{"deviceId":"$device",${card?.let { "\"cardNumber\":\"$it\"," }.orEmpty()}"peer":"$peer","peerKey":"$peer","unread":0,"replyable":true,
               "last":{"id":$id,"deviceId":"$device","direction":"in","peer":"$peer","peerKey":"$peer","body":"b","deviceTime":$id}}"""
        server.enqueue(json("""{"items":[${conv("d_me", "13800001234", "10086", 3)},${conv("d_other", null, "95588", 2)}],"unread":4}"""))
        server.enqueue(json("""{"items":[{"id":"d_me","name":"本机名","online":true},{"id":"d_other","name":"老人机","online":false}]}"""))
        server.enqueue(json("""{"items":[{"number":"13800001234","label":"工作卡","createdAt":1}]}"""))
        view.load()

        val s = view.state.value
        assertEquals(4, s.unread)
        assertEquals(2, s.shown.size)
        assertEquals("本机 · 工作卡", sourceLabel(s.conversations[0].deviceId, s.conversations[0].cardNumber, s))
        assertEquals("老人机", sourceLabel(s.conversations[1].deviceId, s.conversations[1].cardNumber, s))
        assertEquals("其他手机 · 尾号9999", sourceLabel("d_unknown", "13800009999", s))

        view.setFilter(PlatformFilter.ThisPhone)
        assertEquals(listOf("10086"), view.state.value.shown.map { it.peer })
        view.setFilter(PlatformFilter.Phone("d_other"))
        assertEquals(listOf("95588"), view.state.value.shown.map { it.peer })
    }

    @Test
    fun `a message belongs to a thread by card, or by phone when there is no card`() {
        val byCard = RemoteConversation("d1", "13800000000", "10086", "10086", RemoteMessage(id = 1))
        val byPhone = RemoteConversation("d1", null, "10086", "10086", RemoteMessage(id = 1))
        // The card moved to another phone: still the same thread.
        assertTrue(RemoteMessage(id = 2, deviceId = "d2", peerKey = "10086", cardNumber = "13800000000").belongsTo(byCard))
        assertTrue(!RemoteMessage(id = 2, deviceId = "d1", peerKey = "10010", cardNumber = "13800000000").belongsTo(byCard))
        assertTrue(RemoteMessage(id = 3, deviceId = "d1", peerKey = "10086").belongsTo(byPhone))
        assertTrue(!RemoteMessage(id = 3, deviceId = "d2", peerKey = "10086").belongsTo(byPhone))
    }
}
