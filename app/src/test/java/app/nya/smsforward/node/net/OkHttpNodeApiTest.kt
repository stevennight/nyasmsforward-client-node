package app.nya.smsforward.node.net

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class OkHttpNodeApiTest {
    private val server = MockWebServer().apply { start() }
    private val api = OkHttpNodeApi()
    private val base = server.url("/").toString().trimEnd('/')

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun failure(r: ApiResult<*>): ApiResult.Failure = assertIs<ApiResult.Failure>(r)

    @Test
    fun `claim sends the phone identity without any credentials and parses the token`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_abc","token":"nsf_secret","kind":"phone","scopes":[],"minClientVersion":"0.1.0","somethingNew":true}"""))

        val result = api.claim(
            base,
            ClaimRequest("483920", "Pixel 7", "0.1.0", listOf(SimInfo(1, 3, "中国移动"), SimInfo(2))),
        )

        val ok = assertIs<ApiResult.Ok<ClaimResponse>>(result).value // the unknown field must not break parsing
        assertEquals("d_abc", ok.deviceId)
        assertEquals("nsf_secret", ok.token)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/pair/claim", req.path)
        assertNull(req.getHeader("Authorization"), "the claim is the one call made without a token")
        val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("483920", sent.getValue("code").jsonPrimitive.content)
        assertEquals("phone", sent.getValue("kind").jsonPrimitive.content)
        assertEquals("android", sent.getValue("platform").jsonPrimitive.content)
        assertEquals("0.1.0", sent.getValue("appVersion").jsonPrimitive.content)
        val sims = sent.getValue("sims").jsonArray
        assertEquals(2, sims.size)
        assertEquals("中国移动", sims[0].jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals(3, sims[0].jsonObject.getValue("subscriptionId").jsonPrimitive.int)
    }

    @Test
    fun `a wrong pairing code is a plain HTTP failure carrying the server's code`() = runBlocking {
        server.enqueue(json("""{"error":"invalid_pair_code"}""", 400))
        val f = failure(api.claim(base, ClaimRequest("000000", "x", "0.1.0", emptyList())))
        assertEquals("invalid_pair_code", f.code)
        assertEquals(400, f.status)
        assertEquals(FailureKind.HTTP, f.kind)
    }

    @Test
    fun `the token travels in the Authorization header and never in the URL`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d_abc","kind":"phone","name":"Pixel 7","sendPolicy":"off","serverVersion":"0.1.0"}"""))
        val me = assertIs<ApiResult.Ok<MeResponse>>(api.me("$base/", "nsf_tok")).value
        assertEquals("Pixel 7", me.name)

        val req = server.takeRequest()
        assertEquals("Bearer nsf_tok", req.getHeader("Authorization"))
        assertEquals("/api/v1/me", req.path, "a trailing slash on the base URL must not produce //api")
        assertFalse(req.requestUrl.toString().contains("nsf_tok"))
    }

    @Test
    fun `upload sends the documented batch and returns one outcome per message`() = runBlocking {
        server.enqueue(json("""{"results":[{"dedupeKey":"sha256:a","status":"accepted","id":7},{"dedupeKey":"sha256:b","status":"duplicate","id":3},{"dedupeKey":"sha256:c","status":"rejected","error":"bad_body"}]}"""))

        val outcomes = assertIs<ApiResult.Ok<List<UploadOutcome>>>(
            api.upload(
                base, "nsf_tok",
                listOf(
                    UploadMessage("sha256:a", peer = "106901234", body = "验证码 583921", simSlot = 1, deviceTime = 1789830000000),
                    UploadMessage("sha256:b", peer = "示例银行", body = "余额", simSlot = null, deviceTime = 1789830001000),
                ),
            ),
        ).value
        assertEquals(listOf("accepted", "duplicate", "rejected"), outcomes.map { it.status })
        assertEquals("bad_body", outcomes[2].error)

        val req = server.takeRequest()
        assertEquals("/api/v1/device/messages", req.path)
        assertEquals("application/json; charset=utf-8", req.getHeader("Content-Type"))
        val sent = (Json.parseToJsonElement(req.body.readUtf8()).jsonObject.getValue("messages") as JsonArray)
        assertEquals(2, sent.size)
        val first = sent[0] as JsonObject
        assertEquals("in", first.getValue("direction").jsonPrimitive.content, "direction must be sent even though it is the default")
        assertEquals("验证码 583921", first.getValue("body").jsonPrimitive.content, "UTF-8 must survive the trip")
        assertFalse(first.getValue("backfill").jsonPrimitive.boolean)
        assertTrue("simSlot" !in (sent[1] as JsonObject), "an unknown SIM slot is omitted, not sent as null")
    }

    @Test
    fun `only a 401 with a token error code unpairs the phone`() = runBlocking {
        for (code in listOf("token_revoked", "token_invalid", "token_expired")) {
            server.enqueue(json("""{"error":"$code"}""", 401))
            assertEquals(ConnectionVerdict.NEEDS_PAIRING, failure(api.me(base, "nsf_x")).verdict, code)
        }
    }

    @Test
    fun `a bare 401 from a proxy keeps the token and blames the address`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic").setBody("Unauthorized"))
        val f = failure(api.upload(base, "nsf_x", emptyList()))
        assertEquals(ConnectionVerdict.ADDRESS_SUSPECT, f.verdict)
        assertNull(f.code)
    }

    @Test
    fun `server trouble is transient whatever the body says`() = runBlocking {
        for (status in listOf(429, 500, 502, 503, 504)) {
            server.enqueue(json("""{"error":"token_revoked"}""", status))
            assertEquals(ConnectionVerdict.TRANSIENT, failure(api.me(base, "nsf_x")).verdict, "status $status")
        }
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))
        assertEquals(ConnectionVerdict.TRANSIENT, failure(api.me(base, "nsf_x")).verdict)
    }

    @Test
    fun `a redirect is reported, not followed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "https://example.com/api/v1/me"))
        val f = failure(api.me(base, "nsf_x"))
        assertEquals(FailureKind.NOT_OUR_SERVER, f.kind)
        assertEquals(ConnectionVerdict.ADDRESS_SUSPECT, f.verdict)
        assertEquals(1, server.requestCount, "the redirect target must not be contacted with our token")
    }

    @Test
    fun `a 200 that is not our JSON means it is probably not our server`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>Sign in to Wi-Fi</html>"))
        val f = failure(api.me(base, "nsf_x"))
        assertEquals(FailureKind.NOT_OUR_SERVER, f.kind)
        assertEquals(ConnectionVerdict.ADDRESS_SUSPECT, f.verdict)
    }

    @Test
    fun `no connection is transient and keeps the token`() = runBlocking {
        server.shutdown()
        val f = failure(api.upload(base, "nsf_x", emptyList()))
        assertEquals(ConnectionVerdict.TRANSIENT, f.verdict)
        assertEquals(FailureKind.NETWORK, f.kind)
        assertNull(f.status)
    }

    @Test
    fun `a connection dropped mid-response is transient`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ConnectionVerdict.TRANSIENT, failure(api.me(base, "nsf_x")).verdict)
    }
}
