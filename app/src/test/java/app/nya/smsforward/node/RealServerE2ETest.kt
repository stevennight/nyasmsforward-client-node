package app.nya.smsforward.node

import app.nya.smsforward.node.data.OutboxState
import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ConnectionVerdict
import app.nya.smsforward.node.net.OkHttpNodeApi
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.node.IncomingHandler
import app.nya.smsforward.node.node.PairResult
import app.nya.smsforward.node.node.PairingManager
import app.nya.smsforward.node.node.TestResult
import app.nya.smsforward.node.sms.SmsPart
import app.nya.smsforward.node.work.FlushResult
import app.nya.smsforward.node.work.Uploader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue

/**
 * The whole receiver pipeline (pairing, receiving, queueing, uploading, revocation, re-pairing) against a REAL server.
 *
 * Skipped unless NYASMS_E2E_URL is set, so normal builds and CI never need a server:
 *
 *   NYASMS_LISTEN=127.0.0.1:18080 NYASMS_DATA=<empty dir> go run ./cmd/server        # in nyasmsforward-server
 *   NYASMS_E2E_URL=http://127.0.0.1:18080 ./gradlew testDebugUnitTest --tests '*RealServerE2ETest'
 *
 * The server must be freshly started (an empty data directory): the test performs the first-run admin setup itself.
 */
class RealServerE2ETest {
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

    private fun messages(): List<JsonObject> =
        admin("GET", "/api/v1/messages?limit=100").second.getValue("items").jsonArray.map { it.jsonObject }

    private fun newPairingCode(deviceId: String? = null): String {
        val body = if (deviceId == null) """{"kind":"phone"}""" else """{"kind":"phone","deviceId":"$deviceId"}"""
        val (status, out) = admin("POST", "/api/v1/pairings", body)
        assertEquals(201, status, out.toString())
        return out.getValue("display").jsonPrimitive.content // "483 920", exactly as the console shows it
    }

    /** First-run setup, or a login when another test already did it against the same server. */
    private fun ensureAdmin() {
        val (status, _) = admin("POST", "/api/auth/setup", """{"password":"e2e-test-password-123"}""")
        if (status == 409) assertEquals(200, admin("POST", "/api/auth/login", """{"password":"e2e-test-password-123"}""").first)
        else assertEquals(201, status)
        assertTrue(cookie.isNotEmpty(), "a session must have started")
    }

    @Test
    fun `receive, report, revoke, re-pair and catch up against the real server`() = runBlocking<Unit> {
        assumeTrue("set NYASMS_E2E_URL to run", baseUrl != null)
        var now = System.currentTimeMillis()
        val clock = { now }

        // --- the admin sets the server up and creates a pairing code, like in the web console ---
        ensureAdmin()

        // --- the phone: settings, token store, queue, all real classes; only Android itself is missing ---
        val settings = MemorySettings()
        val tokens = MemoryTokens()
        var box = newOutbox()
        val api = OkHttpNodeApi()
        val pairing = PairingManager(
            api, settings, tokens, appVersion = "0.1.0", defaultDeviceName = "接收端手机",
            sims = { listOf(SimInfo(1, 3, "中国移动"), SimInfo(2, 4, "中国联通")) }, onPaired = {},
        )
        fun handler() = IncomingHandler(box, settings, clock)
        fun uploader() = Uploader(box, api, settings, tokens, clock)

        // 1. pairing with the code typed exactly as displayed
        assertEquals(PairResult.Success, pairing.pair(baseUrl!!, newPairingCode(), "E2E 手机"))
        val deviceId = settings.deviceId!!
        assertTrue(tokens.token!!.startsWith("nsf_"))
        assertIs<TestResult.Ok>(pairing.testConnection())

        // 2. a wrong / reused code is refused with words, and the phone stays as it was
        val reused = pairing.pair(baseUrl, "000 000", "x")
        assertIs<PairResult.Error>(reused)
        assertTrue("配对码" in reused.message, reused.message)

        // 3. SMS arrive: a verification code, a two-part message, a sender name, the same message twice
        val h = handler()
        h.onReceived(listOf(SmsPart("106901234", "【示例商城】验证码 583921，5 分钟内有效，请勿泄露给他人。", now - 60_000)), simSlot = 1)
        h.onReceived(listOf(SmsPart("+8613800000000", "你到家了吗？这是第一段，", now - 50_000), SmsPart("+8613800000000", "这是第二段 🙂", now - 50_000)), simSlot = 2)
        h.onReceived(listOf(SmsPart("示例银行", "您尾号 1234 的账户支出 58.00 元，余额 2,310.45 元。", now - 40_000)), simSlot = 1)
        assertEquals(0, h.onReceived(listOf(SmsPart("106901234", "【示例商城】验证码 583921，5 分钟内有效，请勿泄露给他人。", now - 60_000)), simSlot = 1), "local dedupe")
        assertEquals(3, box.pendingCount())

        // 4. report them
        assertEquals(FlushResult.Done(3), uploader().flush())
        assertEquals(0, box.pendingCount())
        assertEquals(List(3) { OutboxState.DONE }, box.recent(10).map { it.state })

        val stored = messages().associateBy { it.getValue("peer").jsonPrimitive.content }
        assertEquals(3, stored.size, stored.keys.toString())
        assertEquals("583921", stored.getValue("106901234").getValue("code").jsonPrimitive.content)
        assertEquals("你到家了吗？这是第一段，这是第二段 🙂", stored.getValue("+8613800000000").getValue("body").jsonPrimitive.content, "multipart join and emoji must survive")
        assertEquals("13800000000", stored.getValue("+8613800000000").getValue("peerKey").jsonPrimitive.content, "server and phone agree on peer keys")
        assertEquals("示例银行", stored.getValue("示例银行").getValue("peer").jsonPrimitive.content, "UTF-8 sender name")
        assertTrue(stored.values.all { it.getValue("deviceId").jsonPrimitive.content == deviceId })

        // 5. a phone that lost its queue and reports the same SMS again: the server says duplicate, nothing doubles
        box = newOutbox()
        handler().onReceived(listOf(SmsPart("106901234", "【示例商城】验证码 583921，5 分钟内有效，请勿泄露给他人。", now - 60_000)), simSlot = 1)
        assertEquals(FlushResult.Done(1), uploader().flush())
        assertEquals(3, messages().size, "the server must dedupe by the phone's key")

        // 6. the admin revokes the token; SMS keep arriving and are kept
        assertEquals(204, admin("DELETE", "/api/v1/devices/$deviceId").first)
        now += 1000
        handler().onReceived(listOf(SmsPart("106987654", "登录验证码：204817（10 分钟内有效）", now)), simSlot = 1)
        assertEquals(FlushResult.NeedsPairing, uploader().flush())
        assertTrue(settings.needsPairing)
        assertEquals(1, box.pendingCount(), "nothing is lost while unpaired")
        // the old token is dead in exactly the way the phone is documented to recognise
        val dead = api.me(baseUrl, tokens.token!!)
        assertIs<ApiResult.Failure>(dead)
        assertEquals(ConnectionVerdict.NEEDS_PAIRING, dead.verdict)
        assertEquals("token_revoked", dead.code)

        // 7. re-pairing from the console keeps the device; the queued SMS go out on their own
        val oldToken = tokens.token
        assertEquals(PairResult.Success, pairing.pair(baseUrl, newPairingCode(deviceId), "E2E 手机"))
        assertEquals(deviceId, settings.deviceId, "re-pairing must keep the device identity")
        assertTrue(tokens.token != oldToken)
        assertEquals(FlushResult.Done(1), uploader().flush())
        assertEquals(4, messages().size)
        assertTrue(messages().all { it.getValue("deviceId").jsonPrimitive.content == deviceId }, "all history stays on one device")

        // 8. changing the address needs no re-pairing (same server here, reached through a different spelling)
        assertEquals(PairResult.Success, pairing.changeServerUrl(baseUrl.replace("127.0.0.1", "localhost")))
        assertIs<TestResult.Ok>(pairing.testConnection())
    }
}
