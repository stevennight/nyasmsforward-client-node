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
import kotlin.test.assertTrue

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
    fun `replies, new messages and deletes send what the protocol asks for`() = runBlocking {
        val task = """{"taskId":"t_1","deviceId":"d1","mode":"reply","recipient":"10086","recipientKey":"10086","body":"TD","status":"queued","error":"","createdAt":1}"""
        server.enqueue(json(task, 201))
        server.enqueue(json(task, 201))
        server.enqueue(json("""{"deleted":[3]}"""))
        server.enqueue(json("""{"deleted":12}"""))
        server.enqueue(json("""{"queued":[3]}"""))
        server.enqueue(json("""{"items":[{"id":3,"deviceId":"d1","peer":"10086","body":"x","deviceTime":1,"deletedAt":9}]}"""))

        val t = assertIs<ApiResult.Ok<OutboundTask>>(api.reply(base, "t", 1042, "TD")).value
        assertEquals("排队中（等待手机上线）", t.summary)
        api.sendNew(base, "t", "d1", 2, "13800000000", "你好")
        assertEquals(listOf(3L), assertIs<ApiResult.Ok<List<Long>>>(api.deleteMessages(base, "t", listOf(3), deletePhone = true)).value)
        assertEquals(12, assertIs<ApiResult.Ok<Int>>(api.deleteConversations(base, "t", listOf(RemoteConversation("d1", "13800000000", "10086", "10086", RemoteMessage(id = 1))), deletePhone = false)).value)
        api.deletePhoneCopies(base, "t", listOf(3))
        val bin = assertIs<ApiResult.Ok<List<RemoteMessage>>>(api.deletedMessages(base, "t", before = 5, beforeAt = 99)).value
        assertEquals(9L, bin.single().deletedAt)

        val (r1, reply) = sent()
        assertEquals("/api/v1/outbound", r1.path)
        assertEquals(1042, reply.getValue("replyToMessageId").jsonPrimitive.content.toInt())
        val (_, new) = sent()
        assertEquals("d1", new.getValue("deviceId").jsonPrimitive.content)
        assertEquals("2", new.getValue("simSlot").jsonPrimitive.content)
        assertEquals("13800000000", new.getValue("to").jsonPrimitive.content)
        assertFalse("replyToMessageId" in new)
        val (r3, del) = sent()
        assertEquals("/api/v1/messages/delete", r3.path)
        assertEquals("true", del.getValue("deletePhone").jsonPrimitive.content)
        val (r4, conv) = sent()
        assertEquals("/api/v1/messages/delete-conversations", r4.path)
        assertFalse("deletePhone" in conv)
        val one = conv.getValue("conversations").toString()
        assertTrue("13800000000" in one && "deviceId" !in one, one)
        assertEquals("/api/v1/messages/deleted/delete-phone", sent().first.path)
        val list = server.takeRequest().requestUrl!!
        assertEquals("5", list.queryParameter("before"))
        assertEquals("99", list.queryParameter("beforeAt"))
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
