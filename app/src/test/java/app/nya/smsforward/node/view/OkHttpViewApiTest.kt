package app.nya.smsforward.node.view

import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ConnectionVerdict
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class OkHttpViewApiTest {
    private val server = MockWebServer().apply { start() }
    private val api = OkHttpViewApi()
    private val base = server.url("/").toString().trimEnd('/')

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun sent() = server.takeRequest().let { it to Json.parseToJsonElement(it.body.readUtf8().ifEmpty { "{}" }).jsonObject }

    @Test
    fun `claim asks for a client identity without credentials`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read","reply"],"minClientVersion":"0.1.0"}"""))
        val ok = assertIs<ApiResult.Ok<ClientClaim>>(api.claim(base, "483920", "Pixel（查看）", "0.6.0")).value
        assertEquals(ClientClaim("d_c", "nsf_c", listOf("read", "reply")), ok)

        val (req, body) = sent()
        assertEquals("/api/v1/pair/claim", req.path)
        assertNull(req.getHeader("Authorization"))
        assertEquals("client", body.getValue("kind").jsonPrimitive.content)
        assertEquals("android", body.getValue("platform").jsonPrimitive.content)
        assertEquals("483920", body.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `the account login sends the code only when there is one`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read"]}"""))
        server.enqueue(json("""{"deviceId":"d_c","token":"nsf_c","scopes":["read"]}"""))
        api.loginDevice(base, "pw", " 123456 ", "n", "0.6.0")
        api.loginDevice(base, "pw", "", "n", "0.6.0")

        val (req, withCode) = sent()
        assertEquals("/api/v1/auth/login-device", req.path)
        assertEquals("pw", withCode.getValue("password").jsonPrimitive.content)
        assertEquals("123456", withCode.getValue("totp").jsonPrimitive.content)
        assertFalse("totp" in sent().second)
    }

    @Test
    fun `conversations are read with the token in the header and unknown fields ignored`() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"deviceId":"d1","cardNumber":"13800000000","peer":"10086","peerKey":"10086","unread":2,"replyable":true,
                   "last":{"id":9,"deviceId":"d1","direction":"in","origin":"device","peer":"10086","peerKey":"10086","body":"余额","simSlot":null,"deviceTime":5,"serverTime":6}}],
                   "unread":7,"extra":true}""",
            ),
        )
        val page = assertIs<ApiResult.Ok<ConversationPage>>(api.conversations(base, "nsf_c", query = "余额", unreadOnly = true)).value
        assertEquals(7, page.unread)
        assertEquals("card:13800000000|10086", page.items.single().key)
        assertEquals(true, page.items.single().last.unread)

        val req = server.takeRequest()
        assertEquals("Bearer nsf_c", req.getHeader("Authorization"))
        val url = req.requestUrl!!
        assertEquals("/api/v1/conversations", url.encodedPath)
        assertEquals("余额", url.queryParameter("q"))
        assertEquals("1", url.queryParameter("unread"))
        assertNull(url.queryParameter("device"))
    }

    @Test
    fun `a thread is fetched by card when it has one, by phone otherwise`() = runBlocking {
        server.enqueue(json("""{"items":[],"unread":0}"""))
        server.enqueue(json("""{"items":[],"unread":0}"""))
        val last = RemoteMessage(id = 1)
        api.messages(base, "t", RemoteConversation("d1", "13800000000", "10086", "10086", last), before = 50)
        api.messages(base, "t", RemoteConversation("d1", null, "10086", "10086", last))

        val byCard = server.takeRequest().requestUrl!!
        assertEquals("13800000000", byCard.queryParameter("card"))
        assertNull(byCard.queryParameter("device"))
        assertEquals("50", byCard.queryParameter("before"))
        val byPhone = server.takeRequest().requestUrl!!
        assertEquals("d1", byPhone.queryParameter("device"))
        assertNull(byPhone.queryParameter("card"))
    }

    @Test
    fun `marking a thread read names the card or the phone`() = runBlocking {
        server.enqueue(json("""{"changed":[3,4]}"""))
        val changed = api.markThreadRead(base, "t", RemoteConversation("d1", "13800000000", "+8610086", "10086", RemoteMessage(id = 1)))
        assertEquals(listOf(3L, 4L), assertIs<ApiResult.Ok<List<Long>>>(changed).value)
        val (_, body) = sent()
        assertEquals("13800000000", body.getValue("cardNumber").jsonPrimitive.content)
        assertEquals("10086", body.getValue("peer").jsonPrimitive.content)
        assertFalse("deviceId" in body)
    }

    @Test
    fun `a revoked token is told apart from a proxy's bare 401`() = runBlocking {
        server.enqueue(json("""{"error":"token_revoked"}""", 401))
        server.enqueue(MockResponse().setResponseCode(401).setBody("<html>auth</html>"))
        val dead = assertIs<ApiResult.Failure>(api.me(base, "t"))
        assertEquals(ConnectionVerdict.NEEDS_PAIRING, dead.verdict)
        val proxy = assertIs<ApiResult.Failure>(api.me(base, "t"))
        assertEquals(ConnectionVerdict.ADDRESS_SUSPECT, proxy.verdict)
        assertEquals("登录已失效，需要重新登录", describeViewFailure(dead))
    }

    @Test
    fun `the event stream is opened with the last event id`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("retry: 3000\n\n"))
        val r = assertIs<ApiResult.Ok<OpenStream>>(api.openEvents(base, "nsf_c", "1041"))
        r.value.close()
        val req = server.takeRequest()
        assertEquals("/api/v1/events", req.path)
        assertEquals("1041", req.getHeader("Last-Event-ID"))
        assertEquals("Bearer nsf_c", req.getHeader("Authorization"))
    }
}
