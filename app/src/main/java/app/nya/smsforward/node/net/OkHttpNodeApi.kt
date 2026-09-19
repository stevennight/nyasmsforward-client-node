package app.nya.smsforward.node.net

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OkHttpNodeApi(client: OkHttpClient = defaultClient()) : NodeApi {
    // Redirects are not followed: OkHttp would drop the body of a redirected POST (and the auth header across hosts),
    // hiding the real problem. A redirect almost always means "you typed http:// but the server wants https://".
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun claim(baseUrl: String, request: ClaimRequest): ApiResult<ClaimResponse> =
        call(
            baseUrl, "/api/v1/pair/claim", token = null,
            body = json.encodeToString(
                ClaimBody.serializer(),
                ClaimBody(request.code, request.name, appVersion = request.appVersion, sims = request.sims),
            ),
            serializer = ClaimResponse.serializer(),
        )

    override suspend fun me(baseUrl: String, token: String): ApiResult<MeResponse> =
        call(baseUrl, "/api/v1/me", token, body = null, serializer = MeResponse.serializer())

    override suspend fun upload(baseUrl: String, token: String, messages: List<UploadMessage>): ApiResult<List<UploadOutcome>> =
        call(
            baseUrl, "/api/v1/device/messages", token,
            body = json.encodeToString(UploadBody.serializer(), UploadBody(messages)),
            serializer = UploadResults.serializer(),
        ).let { r ->
            when (r) {
                is ApiResult.Ok -> ApiResult.Ok(r.value.results)
                is ApiResult.Failure -> r
            }
        }

    private suspend fun <T> call(baseUrl: String, path: String, token: String?, body: String?, serializer: KSerializer<T>): ApiResult<T> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl.trimEnd('/') + path)
            // The token only ever travels in this header, never in the URL (docs/协议.md §2).
            if (token != null) builder.header("Authorization", "Bearer $token")
            if (body != null) builder.post(body.toRequestBody(JSON_MEDIA)) else builder.get()

            try {
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> decodeOk(text, serializer)
                        response.isRedirect -> notOurServer(response.code, "redirect", "the server redirected the request")
                        else -> httpFailure(response.code, text)
                    }
                }
            } catch (e: SocketTimeoutException) {
                transient(FailureKind.TIMEOUT, e)
            } catch (e: SSLException) {
                transient(FailureKind.TLS, e)
            } catch (e: IOException) {
                transient(FailureKind.NETWORK, e)
            }
        }

    private fun <T> decodeOk(text: String, serializer: KSerializer<T>): ApiResult<T> =
        try {
            ApiResult.Ok(json.decodeFromString(serializer, text))
        } catch (e: Exception) {
            // A 2xx that is not our JSON: a captive portal, a proxy's landing page, the wrong service on that port.
            notOurServer(200, "bad_response", "unexpected response body")
        }

    private fun httpFailure(status: Int, text: String): ApiResult.Failure {
        val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        val code = err?.error
        return ApiResult.Failure(
            verdict = Connection.classify(httpStatus = status, errorCode = code),
            kind = FailureKind.HTTP,
            status = status,
            code = code,
            message = err?.message,
        )
    }

    private fun notOurServer(status: Int, code: String, message: String) = ApiResult.Failure(
        verdict = ConnectionVerdict.ADDRESS_SUSPECT, kind = FailureKind.NOT_OUR_SERVER, status = status, code = code, message = message,
    )

    private fun transient(kind: FailureKind, e: IOException) = ApiResult.Failure(
        verdict = Connection.classify(httpStatus = null), kind = kind, message = e.message,
    )

    @Serializable
    private data class ClaimBody(
        val code: String,
        val name: String,
        val kind: String = "phone",
        val platform: String = "android",
        val appVersion: String,
        val sims: List<SimInfo>,
    )

    @Serializable
    private data class UploadBody(val messages: List<UploadMessage>)

    @Serializable
    private data class UploadResults(val results: List<UploadOutcome>)

    @Serializable
    private data class ErrorBody(val error: String? = null, val message: String? = null)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // Unknown fields must be ignored: a newer server may send more (docs/协议.md §1).
        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
