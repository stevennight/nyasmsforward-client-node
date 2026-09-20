package app.nya.smsforward.node

import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.SqliteOutbox
import app.nya.smsforward.node.data.SqliteSendLedger
import app.nya.smsforward.node.net.OkHttpNodeApi
import app.nya.smsforward.node.node.IncomingHandler
import app.nya.smsforward.node.node.PairResult
import app.nya.smsforward.node.node.PairingManager
import app.nya.smsforward.node.send.BodyHash
import app.nya.smsforward.node.sms.SentSync
import app.nya.smsforward.node.sms.SmsBox
import app.nya.smsforward.node.sms.SmsPart
import app.nya.smsforward.node.sms.SystemSms
import app.nya.smsforward.node.work.FlushResult
import app.nya.smsforward.node.work.Uploader
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Sent-message sync and history backfill against a REAL server (docs/协议.md §5.1): what the user sent from the phone,
 * quietly and already read; old history without duplicating what was reported live; and the SMS a task sent, seen again
 * in the sent box, not doubled. Skipped unless NYASMS_E2E_URL is set; see [RealServerE2ETest].
 */
class SentSyncE2ETest {
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

    private class Box : SmsBox {
        val sent = mutableListOf<SystemSms>()
        val inbox = mutableListOf<SystemSms>()
        override fun maxSentId() = sent.maxOfOrNull { it.id } ?: 0L
        override fun sentAfter(afterId: Long, limit: Int) = sent.filter { it.id > afterId }.sortedBy { it.id }
        override fun inboxSince(sinceMillis: Long, limit: Int) = inbox.filter { it.time >= sinceMillis }
        override fun sentSince(sinceMillis: Long, limit: Int) = sent.filter { it.time >= sinceMillis }
    }

    @Test
    fun `sent messages and history reach the server quietly and without duplicates`() = runBlocking<Unit> {
        assumeTrue("set NYASMS_E2E_URL to run", baseUrl != null)
        val (status, _) = admin("POST", "/api/auth/setup", """{"password":"e2e-test-password-123"}""")
        if (status == 409) assertEquals(200, admin("POST", "/api/auth/login", """{"password":"e2e-test-password-123"}""").first)
        val now = System.currentTimeMillis()
        val clock = { now }

        val settings = MemorySettings().apply { syncSent = true; backfillDays = 30 }
        val tokens = MemoryTokens()
        val db = JdbcSqlDb()
        val box = SqliteOutbox(db)
        val ledger = SqliteSendLedger(db)
        val api = OkHttpNodeApi()
        val pairing = PairingManager(api, settings, tokens, appVersion = "0.2.0", defaultDeviceName = "E2E", sims = { emptyList() }, onPaired = {})
        val code = admin("POST", "/api/v1/pairings", """{"kind":"phone"}""").second.getValue("code").jsonPrimitive.content
        assertEquals(PairResult.Success, pairing.pair(baseUrl!!, code, "E2E 同步测试"))
        val deviceId = settings.deviceId!!
        fun uploadAll() = runBlocking { Uploader(box, api, settings, tokens, clock).flush() }
        fun messages() = admin("GET", "/api/v1/messages?device=$deviceId&limit=100").second.getValue("items").jsonArray.map { it.jsonObject }

        // A message received live earlier, reported as usual.
        IncomingHandler(box, settings, clock).onReceived(listOf(SmsPart("106900", "验证码 583921", now - 3_600_000)), simSlot = 1)
        assertEquals(FlushResult.Done(1), uploadAll())

        // The SMS database: the same message under a slightly different timestamp, older history, and sent messages.
        val sms = Box()
        sms.inbox += SystemSms(1, "106900", "验证码 583921", now - 3_600_000 + 1_500, null) // already reported live
        sms.inbox += SystemSms(2, "13800000000", "上周的消息", now - 5 * 86_400_000L, null)
        sms.sent += SystemSms(10, "13800000000", "上周我回的", now - 5 * 86_400_000L + 60_000, null)
        // A reply the platform sent through a task earlier: the ledger remembers it, the server has it as origin=platform.
        ledger.begin("t_1", "13800000000", "reply", now - 60_000, BodyHash.of("TD"))
        val sync = SentSync(settings, sms, box, ledger, clock)

        // Switching sync on: first pass only notes the end of the sent box, then the history is read once.
        assertEquals(0, sync.syncSent())
        assertEquals(3, sync.backfill(), "history: the live one is queued too, the server decides it is a duplicate")
        val flushed = uploadAll()
        assertTrue(flushed is FlushResult.Done, flushed.toString())

        var stored = messages()
        assertEquals(3, stored.size, "live message once, plus the two older ones: " + stored.map { it.getValue("body").jsonPrimitive.content })
        val unread = stored.filter { it["readAt"] == null }.map { it.getValue("body").jsonPrimitive.content }
        assertEquals(listOf("验证码 583921"), unread, "only the live message is unread; history is quiet and already read")

        // A message the user now sends from the phone's SMS app, and the SMS the task sent showing up in the sent box.
        sms.sent += SystemSms(11, "+86 138 0000 0000", "我到家了", now, null)
        sms.sent += SystemSms(12, "13800000000", "TD", now - 50_000, null)
        assertEquals(1, sync.syncSent(), "the task's own SMS is skipped by the ledger")
        assertEquals(FlushResult.Done(1), uploadAll())

        stored = messages()
        val mine = stored.first { it.getValue("body").jsonPrimitive.content == "我到家了" }
        assertEquals("out", mine.getValue("direction").jsonPrimitive.content)
        assertEquals("device", mine.getValue("origin").jsonPrimitive.content)
        assertTrue(mine["readAt"] != null, "what the user sent is already read")
        assertEquals("13800000000", mine.getValue("peerKey").jsonPrimitive.content, "same conversation as the incoming ones")
        assertEquals(4, stored.size)
    }
}
