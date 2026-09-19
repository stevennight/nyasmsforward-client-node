package app.nya.smsforward.node.net

import kotlinx.serialization.Serializable

/** One SIM slot as reported at pairing (docs/协议.md §3). Labels need READ_PHONE_STATE; without it only slots are known. */
@Serializable
data class SimInfo(val slot: Int, val subscriptionId: Int? = null, val label: String? = null)

data class ClaimRequest(
    val code: String,
    val name: String,
    val appVersion: String,
    val sims: List<SimInfo>,
)

@Serializable
data class ClaimResponse(
    val deviceId: String,
    val token: String,
    val kind: String? = null,
    val scopes: List<String> = emptyList(),
    val minClientVersion: String? = null,
)

@Serializable
data class MeResponse(
    val deviceId: String,
    val kind: String,
    val name: String,
    val platform: String? = null,
    val sendPolicy: String? = null,
    val serverVersion: String? = null,
    val minClientVersion: String? = null,
    val serverTime: Long? = null,
)

/** One message in an upload batch (docs/协议.md §5). v1 phones only report incoming messages. */
@Serializable
data class UploadMessage(
    val dedupeKey: String,
    val direction: String = "in",
    val peer: String,
    val body: String,
    val simSlot: Int? = null,
    val deviceTime: Long,
    val backfill: Boolean = false,
)

/** The server's verdict on one uploaded message. */
@Serializable
data class UploadOutcome(
    val dedupeKey: String,
    /** accepted | duplicate | rejected */
    val status: String,
    val id: Long? = null,
    val error: String? = null,
)

/** Why a request failed, for choosing the words shown to the user. */
enum class FailureKind {
    /** The server answered with an HTTP error. */
    HTTP,

    /** No answer: DNS failure, connection refused, unreachable. */
    NETWORK,

    /** No answer in time. */
    TIMEOUT,

    /** The TLS handshake failed: bad or untrusted certificate, wrong host name. */
    TLS,

    /** It answered, but not the way our server does (an HTML page, a redirect): probably the wrong address. */
    NOT_OUR_SERVER,
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /**
     * @property verdict what this means for the stored token (see [Connection])
     * @property code the server's own `error` code, when the body was our JSON
     */
    data class Failure(
        val verdict: ConnectionVerdict,
        val kind: FailureKind,
        val status: Int? = null,
        val code: String? = null,
        val message: String? = null,
    ) : ApiResult<Nothing>
}

/** The server API the receiver app uses. Everything else (SSE, admin) is not this app's business. */
interface NodeApi {
    /** Exchanges a pairing code for a long-lived device token. No authentication. */
    suspend fun claim(baseUrl: String, request: ClaimRequest): ApiResult<ClaimResponse>

    /** "Is my token good?" — also the connection test. */
    suspend fun me(baseUrl: String, token: String): ApiResult<MeResponse>

    suspend fun upload(baseUrl: String, token: String, messages: List<UploadMessage>): ApiResult<List<UploadOutcome>>
}
