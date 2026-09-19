package app.nya.smsforward.node.node

import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ClaimRequest
import app.nya.smsforward.node.net.MeResponse
import app.nya.smsforward.node.net.NodeApi
import app.nya.smsforward.node.net.ServerUrl
import app.nya.smsforward.node.net.ServerUrlResult
import app.nya.smsforward.node.net.SimInfo

/** What a form submit can produce. [field] says which input the message belongs to. */
sealed interface PairResult {
    data object Success : PairResult
    data class Error(val message: String, val field: Field? = null) : PairResult

    enum class Field { URL, CODE }
}

sealed interface TestResult {
    data class Ok(val message: String, val info: MeResponse) : TestResult
    data class Error(val message: String) : TestResult
}

/**
 * Connecting this phone to a server, and looking after that connection afterwards (docs/开发计划.md §2.1).
 *
 * The receiver pairs with a one-time code only: a phone that sits in a corner receiving SMS should never hold the
 * admin password. The token that comes back is long-lived, so this is normally done once.
 */
class PairingManager(
    private val api: NodeApi,
    private val settings: NodeSettings,
    private val tokens: TokenStore,
    private val appVersion: String,
    private val defaultDeviceName: String,
    private val sims: () -> List<SimInfo>,
    /** Called after a successful pairing, to flush anything queued while the phone was not paired. */
    private val onPaired: () -> Unit,
) {
    suspend fun pair(rawUrl: String, rawCode: String, rawName: String): PairResult {
        val url = when (val r = ServerUrl.validate(rawUrl)) {
            is ServerUrlResult.Ok -> r.url
            is ServerUrlResult.Invalid -> return PairResult.Error(describeUrlProblem(r.reason), PairResult.Field.URL)
        }
        // The console shows the code as "483 920"; accept it however it was typed or pasted.
        // Also folds full-width digits (typed with a Chinese IME) into ASCII ones, which is what the server compares.
        val code = rawCode.mapNotNull { c -> Character.digit(c, 10).takeIf { it >= 0 }?.let { '0' + it } }.joinToString("")
        if (code.length != 6) return PairResult.Error("配对码是 6 位数字", PairResult.Field.CODE)
        val name = rawName.trim().ifEmpty { defaultDeviceName }.take(MAX_NAME)

        return when (val r = api.claim(url, ClaimRequest(code, name, appVersion, sims()))) {
            is ApiResult.Failure -> PairResult.Error(describePairFailure(r), if (r.code == "invalid_pair_code") PairResult.Field.CODE else null)
            is ApiResult.Ok -> {
                tokens.write(r.value.token)
                settings.serverUrl = url
                settings.deviceId = r.value.deviceId
                settings.deviceName = name
                settings.needsPairing = false
                settings.lastError = null
                onPaired()
                PairResult.Success
            }
        }
    }

    /** "Test connection": asks the server who we are. Also notices a revoked token. */
    suspend fun testConnection(): TestResult {
        val url = settings.serverUrl
        val token = tokens.read()
        if (url == null || token == null) return TestResult.Error("还没有配对")
        return when (val r = api.me(url, token)) {
            is ApiResult.Ok -> {
                settings.needsPairing = false
                TestResult.Ok("连接正常 · 服务器 ${r.value.serverVersion ?: "?"} · 令牌有效", r.value)
            }
            is ApiResult.Failure -> {
                if (r.verdict == app.nya.smsforward.node.net.ConnectionVerdict.NEEDS_PAIRING) {
                    settings.needsPairing = true
                    settings.lastError = "令牌已失效，需要重新配对"
                }
                TestResult.Error(describeFailure(r))
            }
        }
    }

    /**
     * Points the phone at another address (new domain, new port). No re-pairing: the token does not depend on the
     * address. The new address is only saved if the server there accepts our token, so a typo cannot lock the phone out.
     */
    suspend fun changeServerUrl(rawUrl: String): PairResult {
        val url = when (val r = ServerUrl.validate(rawUrl)) {
            is ServerUrlResult.Ok -> r.url
            is ServerUrlResult.Invalid -> return PairResult.Error(describeUrlProblem(r.reason), PairResult.Field.URL)
        }
        val token = tokens.read() ?: return PairResult.Error("还没有配对")
        return when (val r = api.me(url, token)) {
            is ApiResult.Ok -> {
                if (r.value.deviceId != settings.deviceId) {
                    return PairResult.Error("那个地址上的服务器不认识这台手机（设备不一致），没有保存。换服务器请重新配对", PairResult.Field.URL)
                }
                settings.serverUrl = url
                settings.needsPairing = false
                settings.lastError = null
                onPaired() // the new address may be reachable where the old one was not: flush what is queued
                PairResult.Success
            }
            is ApiResult.Failure -> PairResult.Error(
                if (r.verdict == app.nya.smsforward.node.net.ConnectionVerdict.NEEDS_PAIRING) {
                    "那个地址上的服务器不认识这台手机的令牌，没有保存。换服务器请重新配对"
                } else {
                    describeFailure(r)
                },
                PairResult.Field.URL,
            )
        }
    }

    /**
     * Forgets the token and the device identity. The server address and name stay so pairing again is quick, and the
     * local queue stays too: SMS that were received but not yet reported are still reported after the next pairing.
     */
    fun disconnect() {
        tokens.clear()
        settings.deviceId = null
        settings.needsPairing = false
        settings.lastError = null
    }

    private companion object {
        const val MAX_NAME = 60
    }
}
