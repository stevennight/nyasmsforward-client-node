package app.nya.smsforward.node.node

import app.nya.smsforward.node.FakeApi
import app.nya.smsforward.node.MemorySettings
import app.nya.smsforward.node.MemoryTokens
import app.nya.smsforward.node.httpFailure
import app.nya.smsforward.node.me
import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ClaimResponse
import app.nya.smsforward.node.net.FailureKind
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.networkFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PairingManagerTest {
    private val api = FakeApi()
    private val settings = MemorySettings()
    private val tokens = MemoryTokens()
    private var paired = 0

    private fun manager(sims: List<SimInfo> = listOf(SimInfo(1, 3, "中国移动"))) =
        PairingManager(api, settings, tokens, appVersion = "0.1.0", defaultDeviceName = "接收端手机", sims = { sims }, onPaired = { paired++ })

    private val claimed = ApiResult.Ok(ClaimResponse("d_new", "nsf_secret", "phone", emptyList(), "0.1.0"))

    @Test
    fun `pairing stores the token and identity, then triggers a first upload`() = runBlocking {
        api.claims += claimed
        val result = manager().pair(" https://sms.example.com/ ", "483 920", "Pixel 7")

        assertEquals(PairResult.Success, result)
        assertEquals("nsf_secret", tokens.token)
        assertEquals("https://sms.example.com", settings.serverUrl)
        assertEquals("d_new", settings.deviceId)
        assertEquals("Pixel 7", settings.deviceName)
        assertEquals(1, paired, "queued SMS must be flushed right after pairing")

        val (url, req) = api.claimRequests.single()
        assertEquals("https://sms.example.com", url)
        assertEquals("483920", req.code, "the code is sent without the display space")
        assertEquals("0.1.0", req.appVersion)
        assertEquals(listOf(SimInfo(1, 3, "中国移动")), req.sims)
    }

    @Test
    fun `the code is accepted however it was typed or pasted`() = runBlocking {
        // plain, as displayed in the console, pasted with junk, and typed with full-width digits by a Chinese IME
        for (typed in listOf("483920", "483 920", " 483-920 ", "４８３ ９２０")) {
            api.claims += claimed
            assertEquals(PairResult.Success, manager().pair("https://sms.example.com", typed, ""), typed)
            assertEquals("483920", api.claimRequests.last().second.code, "typed as '$typed'")
        }
    }

    @Test
    fun `a blank name falls back to the default and a long one is cut`() = runBlocking {
        api.claims += claimed; api.claims += claimed
        manager().pair("https://sms.example.com", "483920", "   ")
        assertEquals("接收端手机", api.claimRequests[0].second.name)
        manager().pair("https://sms.example.com", "483920", "x".repeat(200))
        assertEquals(60, api.claimRequests[1].second.name.length)
    }

    @Test
    fun `bad input never reaches the server`() = runBlocking {
        val m = manager()
        val url = m.pair("http://sms.example.com", "483920", "")
        assertIs<PairResult.Error>(url)
        assertEquals(PairResult.Field.URL, url.field)
        assertTrue("HTTPS" in url.message)

        val empty = m.pair("", "483920", "")
        assertEquals(PairResult.Field.URL, (empty as PairResult.Error).field)

        val code = m.pair("https://sms.example.com", "12345", "")
        assertEquals(PairResult.Field.CODE, (code as PairResult.Error).field)
        assertEquals("配对码是 6 位数字", code.message)

        assertTrue(api.claimRequests.isEmpty())
        assertNull(tokens.token)
    }

    @Test
    fun `LAN addresses over http are allowed`() = runBlocking {
        api.claims += claimed
        assertEquals(PairResult.Success, manager().pair("http://192.168.1.5:8080", "483920", ""))
        assertEquals("http://192.168.1.5:8080", settings.serverUrl)
    }

    @Test
    fun `server errors are explained in words and pair nothing`() = runBlocking {
        val cases = mapOf<ApiResult.Failure, String>(
            httpFailure(400, "invalid_pair_code") to "配对码错误",
            httpFailure(429, "too_many_attempts") to "太多",
            httpFailure(400, "pair_kind_mismatch") to "客户端的配对码",
            networkFailure to "无法连接",
            ApiResult.Failure(app.nya.smsforward.node.net.ConnectionVerdict.TRANSIENT, FailureKind.TLS) to "证书",
            ApiResult.Failure(app.nya.smsforward.node.net.ConnectionVerdict.ADDRESS_SUSPECT, FailureKind.NOT_OUR_SERVER) to "不像 NyaSmsForward",
            httpFailure(503) to "暂时不可用",
        )
        for ((failure, expected) in cases) {
            api.claims += failure
            val r = manager().pair("https://sms.example.com", "483920", "")
            assertTrue(expected in (r as PairResult.Error).message, "expected '$expected' in '${r.message}'")
        }
        assertNull(tokens.token)
        assertNull(settings.deviceId)
        assertEquals(0, paired)
    }

    @Test
    fun `a wrong code points at the code field`() = runBlocking {
        api.claims += httpFailure(400, "invalid_pair_code")
        assertEquals(PairResult.Field.CODE, (manager().pair("https://sms.example.com", "000000", "") as PairResult.Error).field)
    }

    @Test
    fun `re-pairing clears the needs-pairing flag`() = runBlocking {
        settings.needsPairing = true
        settings.lastError = "令牌已失效"
        api.claims += claimed
        manager().pair("https://sms.example.com", "483920", "")
        assertFalse(settings.needsPairing)
        assertNull(settings.lastError)
    }

    // --- connection settings ------------------------------------------------------------------------------------

    private fun pairedManager(): PairingManager {
        settings.serverUrl = "https://sms.example.com"; settings.deviceId = "d_test"; settings.deviceName = "Pixel 7"
        tokens.token = "nsf_tok"
        return manager()
    }

    @Test
    fun `test connection reports the server and a healthy token`() = runBlocking {
        api.mes += ApiResult.Ok(me())
        val r = pairedManager().testConnection()
        assertIs<TestResult.Ok>(r)
        assertTrue("令牌有效" in r.message)
        assertEquals("https://sms.example.com" to "nsf_tok", api.meCalls.single())
    }

    @Test
    fun `test connection notices a revoked token and flags the phone for re-pairing`() = runBlocking {
        api.mes += httpFailure(401, "token_revoked")
        val r = pairedManager().testConnection()
        assertIs<TestResult.Error>(r)
        assertTrue("重新配对" in r.message)
        assertTrue(settings.needsPairing)
        assertEquals("nsf_tok", tokens.token, "the token itself is only cleared by the user")
    }

    @Test
    fun `test connection with a network problem does not unpair`() = runBlocking {
        api.mes += networkFailure
        assertIs<TestResult.Error>(pairedManager().testConnection())
        assertFalse(settings.needsPairing)
    }

    @Test
    fun `test connection before pairing says so`() = runBlocking {
        assertEquals(TestResult.Error("还没有配对"), manager().testConnection())
    }

    @Test
    fun `changing the address needs no re-pairing when the new server knows this phone`() = runBlocking {
        api.mes += ApiResult.Ok(me("d_test"))
        val m = pairedManager()
        assertEquals(PairResult.Success, m.changeServerUrl("https://new.example.com/"))
        assertEquals("https://new.example.com", settings.serverUrl)
        assertEquals("nsf_tok", tokens.token)
        assertEquals("d_test", settings.deviceId)
        assertEquals("https://new.example.com" to "nsf_tok", api.meCalls.single())
        assertEquals(1, paired, "queued SMS are retried on the new address right away")
    }

    @Test
    fun `a typo in the new address is not saved, so the phone cannot lock itself out`() = runBlocking {
        val m = pairedManager()

        api.mes += networkFailure
        val unreachable = m.changeServerUrl("https://typo.example.com")
        assertIs<PairResult.Error>(unreachable)
        assertEquals(PairResult.Field.URL, unreachable.field)
        assertEquals("https://sms.example.com", settings.serverUrl)

        api.mes += httpFailure(401, "token_invalid") // some other server that has never heard of us
        assertTrue("不认识" in (m.changeServerUrl("https://other.example.com") as PairResult.Error).message)
        assertEquals("https://sms.example.com", settings.serverUrl)

        api.mes += ApiResult.Ok(me("d_someone_else")) // a server that answers, for a different device
        assertTrue("不一致" in (m.changeServerUrl("https://other2.example.com") as PairResult.Error).message)
        assertEquals("https://sms.example.com", settings.serverUrl)

        assertIs<PairResult.Error>(m.changeServerUrl("http://public.example.com")) // invalid: public http
        assertEquals(3, api.meCalls.size, "invalid input is rejected before any request")
    }

    @Test
    fun `disconnecting forgets the token and identity but keeps the address`() {
        val m = pairedManager()
        settings.needsPairing = true
        m.disconnect()
        assertNull(tokens.token)
        assertNull(settings.deviceId)
        assertFalse(settings.needsPairing)
        assertEquals("https://sms.example.com", settings.serverUrl)
        assertEquals("Pixel 7", settings.deviceName)
    }
}
