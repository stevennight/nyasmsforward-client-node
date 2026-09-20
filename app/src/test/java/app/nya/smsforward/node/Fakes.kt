package app.nya.smsforward.node

import app.nya.smsforward.node.data.JdbcSqlDb
import app.nya.smsforward.node.data.SqliteOutbox
import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ClaimRequest
import app.nya.smsforward.node.net.ClaimResponse
import app.nya.smsforward.node.net.ConnectionVerdict
import app.nya.smsforward.node.net.FailureKind
import app.nya.smsforward.node.net.MeResponse
import app.nya.smsforward.node.net.NodeApi
import app.nya.smsforward.node.net.UploadMessage
import app.nya.smsforward.node.net.UploadOutcome
import app.nya.smsforward.node.node.NodeSettings
import app.nya.smsforward.node.policy.SendPolicy
import app.nya.smsforward.node.node.TokenStore

class MemorySettings : NodeSettings {
    override var serverUrl: String? = null
    override var deviceId: String? = null
    override var deviceName: String? = null
    override var needsPairing = false
    override var lastUploadAt = 0L
    override var lastError: String? = null
    override var sendPolicy = SendPolicy.OFF
    override var sendLimitPerHour = 10
}

class MemoryTokens(var token: String? = null) : TokenStore {
    override fun read() = token
    override fun write(token: String) { this.token = token }
    override fun clear() { token = null }
}

/** A real SQLite outbox on the JVM, so logic tests exercise the actual queue behaviour. */
fun newOutbox() = SqliteOutbox(JdbcSqlDb())

fun pairedSettings(url: String = "https://sms.example.com", deviceId: String = "d_test") =
    MemorySettings().apply { serverUrl = url; this.deviceId = deviceId; deviceName = "Pixel 7" }

fun httpFailure(status: Int, code: String? = null) = ApiResult.Failure(
    verdict = app.nya.smsforward.node.net.Connection.classify(status, code), kind = FailureKind.HTTP, status = status, code = code,
)

val networkFailure = ApiResult.Failure(ConnectionVerdict.TRANSIENT, FailureKind.NETWORK, message = "unreachable")

/** Scripted server: queue answers per call, inspect what was sent. */
class FakeApi : NodeApi {
    val claims = ArrayDeque<ApiResult<ClaimResponse>>()
    val mes = ArrayDeque<ApiResult<MeResponse>>()
    val uploads = ArrayDeque<ApiResult<List<UploadOutcome>>>()

    val claimRequests = mutableListOf<Pair<String, ClaimRequest>>()
    val meCalls = mutableListOf<Pair<String, String>>()
    val uploadCalls = mutableListOf<Triple<String, String, List<UploadMessage>>>()

    override suspend fun claim(baseUrl: String, request: ClaimRequest): ApiResult<ClaimResponse> {
        claimRequests += baseUrl to request
        return claims.removeFirst()
    }

    override suspend fun me(baseUrl: String, token: String): ApiResult<MeResponse> {
        meCalls += baseUrl to token
        return mes.removeFirst()
    }

    override suspend fun upload(baseUrl: String, token: String, messages: List<UploadMessage>): ApiResult<List<UploadOutcome>> {
        uploadCalls += Triple(baseUrl, token, messages)
        return uploads.removeFirst()
    }

    /** Accepts everything it is sent. */
    fun acceptAll(times: Int = 1) = repeat(times) { uploads += ApiResult.Ok(emptyList()) } // replaced by echo below
}

/** An api whose upload answers "accepted" for every message it is given (or a fixed status). */
class EchoUploadApi(private val status: String = "accepted", private val error: String? = null) : NodeApi {
    val batches = mutableListOf<List<UploadMessage>>()
    override suspend fun claim(baseUrl: String, request: ClaimRequest): ApiResult<ClaimResponse> = error("not used")
    override suspend fun me(baseUrl: String, token: String): ApiResult<MeResponse> = error("not used")
    override suspend fun upload(baseUrl: String, token: String, messages: List<UploadMessage>): ApiResult<List<UploadOutcome>> {
        batches += messages
        return ApiResult.Ok(messages.map { UploadOutcome(it.dedupeKey, status, id = 1, error = error) })
    }
}

fun me(deviceId: String = "d_test") = MeResponse(deviceId, "phone", "Pixel 7", "android", "off", "0.1.0", "0.1.0", 1L)
