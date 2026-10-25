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
    override var backgroundAlerts = true
    override var lastEventId: String? = null
    override var showOwn = false
    override var deleteOnPlatform = false
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
    fun `with the inbox showing this phone's messages, 全部 leaves them out but 本机 still lists them`() {
        fun conv(device: String, peer: String) = RemoteConversation(device, null, peer, peer, RemoteMessage(id = peer.length.toLong(), deviceId = device))
        val base = ViewState(conversations = listOf(conv("d_me", "10086"), conv("d_other", "95588")), ownDeviceId = "d_me")
        assertEquals(2, base.shown.size, "without the SMS-app feature everything is listed")
        val inbox = base.copy(ownInInbox = true)
        assertEquals(listOf("95588"), inbox.shown.map { it.peer })
        assertEquals(1, inbox.hiddenOwn)
        assertEquals(2, inbox.copy(showOwn = true).shown.size)
        assertEquals(listOf("10086"), inbox.copy(filter = PlatformFilter.ThisPhone).shown.map { it.peer })
    }

    @Test
    fun `alerts only for new received messages, once, not for the open conversation, not twice for this phone`() {
        val m = RemoteMessage(id = 7, deviceId = "d_me", peerKey = "10086")
        val e = ServerEvent.Message(m, notify = true)
        fun alert(event: ServerEvent.Message = e, already: Boolean = false, fg: Boolean = false, open: String? = null, own: Boolean = false) =
            shouldAlert(event, already, fg, open, "d_me", own)

        assertTrue(alert())
        assertTrue(!alert(ServerEvent.Message(m, notify = false)), "the server said not to (history, sent from the platform)")
        assertTrue(!alert(ServerEvent.Message(m.copy(direction = "out"), notify = true)))
        assertTrue(!alert(already = true), "a replay after reconnecting")
        assertTrue(!alert(fg = true, open = m.conversationKey), "that conversation is on screen")
        assertTrue(alert(fg = false, open = m.conversationKey), "left open in the background still alerts")
        assertTrue(!alert(own = true), "the 「短信」 inbox alerts about this phone's own message")
        assertTrue(alert(ServerEvent.Message(m.copy(deviceId = "d_other"), notify = true), own = true))
    }

    @Test
    fun `a reply needs the scope, a number, a received message and a phone that allows it`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read"]}"""))
        view.connectWithCode(base, "123456", "x")
        val thread = RemoteConversation("d1", null, "10086", "10086", RemoteMessage(id = 1))
        val received = listOf(RemoteMessage(id = 1, deviceId = "d1", direction = "in"), RemoteMessage(id = 2, deviceId = "d1", direction = "out"))
        assertTrue("回复" in view.replyBlockedReason(thread, received)!!)

        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read","reply"]}"""))
        view.connectWithCode(base, "123456", "x")
        assertTrue("字母" in view.replyBlockedReason(thread.copy(replyable = false), received)!!)
        assertTrue("没有收到过" in view.replyBlockedReason(thread, received.filter { !it.incoming })!!)
        assertEquals(1L, view.replyTarget(received)?.id)
        assertNull(view.replyBlockedReason(thread, received), "phone not known yet: let the server decide")

        assertNull(RemotePhone("d1", effectivePolicy = "reply").blockedReason(reply = true))
        assertTrue("新发" in RemotePhone("d1", effectivePolicy = "reply").blockedReason(reply = false)!!)
        assertTrue("吊销" in RemotePhone("d1", revoked = true, effectivePolicy = "any").blockedReason(reply = true)!!)
    }

    @Test
    fun `deleting in the inbox reaches the platform only with the option on, logged in and allowed`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read","delete"]}"""))
        view.connectWithCode(base, "123456", "x")
        assertTrue(!view.deletesOnPlatform(), "off by default")
        view.setDeleteOnPlatform(true)
        assertTrue(view.deletesOnPlatform())
        settings.scopes = setOf("read")
        assertTrue(!view.deletesOnPlatform())
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
