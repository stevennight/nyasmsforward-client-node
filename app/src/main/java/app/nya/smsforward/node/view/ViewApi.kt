package app.nya.smsforward.node.view

import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.Connection
import app.nya.smsforward.node.net.ConnectionVerdict
import app.nya.smsforward.node.net.FailureKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * The client side of the server API (docs/协议.md §3, §4.0.2, §6.1), used by the 「查看」 feature with a token of
 * kind=client. A separate identity from the receiver's own token: the server never lets a receiver token read
 * messages, so a phone that also views holds both.
 */
interface ViewApi {
    /** Pairing code from the console (「客户端」 type). No authentication. */
    suspend fun claim(baseUrl: String, code: String, name: String, appVersion: String): ApiResult<ClientClaim>

    /** The admin password for a token (§3.1); the password is not kept. [totp] when the server has 2FA on. */
    suspend fun loginDevice(baseUrl: String, password: String, totp: String?, name: String, appVersion: String): ApiResult<ClientClaim>

    suspend fun me(baseUrl: String, token: String): ApiResult<ClientMe>

    suspend fun conversations(baseUrl: String, token: String, query: String? = null, unreadOnly: Boolean = false, deviceId: String? = null): ApiResult<ConversationPage>

    /** One thread, newest first; [before] is the id cursor for older messages. */
    suspend fun messages(baseUrl: String, token: String, thread: RemoteConversation, before: Long? = null, limit: Int = PAGE): ApiResult<MessagePage>

    suspend fun phones(baseUrl: String, token: String): ApiResult<List<RemotePhone>>

    suspend fun cards(baseUrl: String, token: String): ApiResult<List<RemoteCard>>

    /** Marks a whole thread read; returns the ids that changed. */
    suspend fun markThreadRead(baseUrl: String, token: String, thread: RemoteConversation): ApiResult<List<Long>>

    suspend fun markUnread(baseUrl: String, token: String, id: Long): ApiResult<Unit>

    suspend fun markRead(baseUrl: String, token: String, ids: List<Long>): ApiResult<Unit>

    /** Replies to a received message: recipient, phone and SIM come from that message. Needs `reply`. */
    suspend fun reply(baseUrl: String, token: String, messageId: Long, body: String): ApiResult<OutboundTask>

    /** A new message through [deviceId] (and [simSlot]); needs `send` and a phone whose policy allows it. */
    suspend fun sendNew(baseUrl: String, token: String, deviceId: String, simSlot: Int?, to: String, body: String): ApiResult<OutboundTask>

    /** The latest send tasks, optionally of one phone and number. */
    suspend fun outbound(baseUrl: String, token: String, deviceId: String? = null, peer: String? = null, limit: Int = 5): ApiResult<List<OutboundTask>>

    suspend fun cancelOutbound(baseUrl: String, token: String, taskId: String): ApiResult<OutboundTask>

    /** Moves messages to the platform recycle bin; [deletePhone] also asks the receiver phone to delete them. Needs `delete`. */
    suspend fun deleteMessages(baseUrl: String, token: String, ids: List<Long>, deletePhone: Boolean): ApiResult<List<Long>>

    /** Moves whole conversations to the recycle bin; returns how many messages moved. */
    suspend fun deleteConversations(baseUrl: String, token: String, threads: List<RemoteConversation>, deletePhone: Boolean): ApiResult<Int>

    /** The recycle bin, newest deletion first; [before] / [beforeAt] are the last item's id and deletedAt. */
    suspend fun deletedMessages(baseUrl: String, token: String, before: Long? = null, beforeAt: Long? = null): ApiResult<List<RemoteMessage>>

    suspend fun restoreMessage(baseUrl: String, token: String, id: Long): ApiResult<Unit>

    /** Asks the receiver phones to delete messages that are in the recycle bin already; returns the queued ids. */
    suspend fun deletePhoneCopies(baseUrl: String, token: String, ids: List<Long>): ApiResult<List<Long>>

    /** Opens the event stream. The caller reads and closes the body. */
    suspend fun openEvents(baseUrl: String, token: String, lastEventId: String?): ApiResult<OpenStream>

    companion object {
        const val PAGE = 100
    }
}

class OkHttpViewApi(client: OkHttpClient = defaultClient()) : ViewApi {
    // Redirects are not followed, as in OkHttpNodeApi: a redirect almost always means http:// where https:// is wanted.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    // The stream stays open for hours; the server sends a keep-alive every 20 s, so 70 s of silence means it is dead.
    private val streamHttp = http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()

    override suspend fun claim(baseUrl: String, code: String, name: String, appVersion: String): ApiResult<ClientClaim> =
        call(
            url(baseUrl, "/api/v1/pair/claim"), token = null,
            body = buildJsonObject {
                put("code", code); put("name", name); put("kind", "client"); put("platform", "android"); put("appVersion", appVersion)
            }.toString(),
            serializer = ClientClaim.serializer(),
        )

    override suspend fun loginDevice(baseUrl: String, password: String, totp: String?, name: String, appVersion: String): ApiResult<ClientClaim> =
        call(
            url(baseUrl, "/api/v1/auth/login-device"), token = null,
            body = buildJsonObject {
                put("password", password); put("name", name); put("kind", "client"); put("platform", "android"); put("appVersion", appVersion)
                if (!totp.isNullOrBlank()) put("totp", totp.trim())
            }.toString(),
            serializer = ClientClaim.serializer(),
        )

    override suspend fun me(baseUrl: String, token: String): ApiResult<ClientMe> =
        call(url(baseUrl, "/api/v1/me"), token, body = null, serializer = ClientMe.serializer())

    override suspend fun conversations(baseUrl: String, token: String, query: String?, unreadOnly: Boolean, deviceId: String?): ApiResult<ConversationPage> =
        call(
            url(baseUrl, "/api/v1/conversations", "q" to query, "device" to deviceId, "unread" to (if (unreadOnly) "1" else null), "limit" to "500"),
            token, body = null, serializer = ConversationPage.serializer(),
        )

    override suspend fun messages(baseUrl: String, token: String, thread: RemoteConversation, before: Long?, limit: Int): ApiResult<MessagePage> {
        val byCard = !thread.cardNumber.isNullOrEmpty()
        return call(
            url(
                baseUrl, "/api/v1/messages",
                "card" to thread.cardNumber.takeIf { byCard }, "device" to thread.deviceId.takeIf { !byCard },
                "peer" to thread.peerKey, "before" to before?.toString(), "limit" to limit.toString(),
            ),
            token, body = null, serializer = MessagePage.serializer(),
        )
    }

    override suspend fun phones(baseUrl: String, token: String): ApiResult<List<RemotePhone>> =
        call(url(baseUrl, "/api/v1/phones"), token, body = null, serializer = PhoneList.serializer()).map { it.items }

    override suspend fun cards(baseUrl: String, token: String): ApiResult<List<RemoteCard>> =
        call(url(baseUrl, "/api/v1/cards"), token, body = null, serializer = CardList.serializer()).map { it.items }

    override suspend fun markThreadRead(baseUrl: String, token: String, thread: RemoteConversation): ApiResult<List<Long>> =
        call(
            url(baseUrl, "/api/v1/messages/read"), token,
            body = buildJsonObject {
                if (thread.cardNumber.isNullOrEmpty()) put("deviceId", thread.deviceId) else put("cardNumber", thread.cardNumber)
                put("peer", thread.peerKey)
            }.toString(),
            serializer = ChangedIds.serializer(),
        ).map { it.changed }

    override suspend fun markUnread(baseUrl: String, token: String, id: Long): ApiResult<Unit> =
        call(url(baseUrl, "/api/v1/messages/$id/unread"), token, body = "{}", serializer = Empty.serializer()).map { }

    override suspend fun markRead(baseUrl: String, token: String, ids: List<Long>): ApiResult<Unit> =
        call(
            url(baseUrl, "/api/v1/messages/read"), token,
            body = buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }.toString(),
            serializer = Empty.serializer(),
        ).map { }

    override suspend fun reply(baseUrl: String, token: String, messageId: Long, body: String): ApiResult<OutboundTask> =
        call(
            url(baseUrl, "/api/v1/outbound"), token,
            body = buildJsonObject { put("replyToMessageId", messageId); put("body", body) }.toString(),
            serializer = OutboundTask.serializer(),
        )

    override suspend fun sendNew(baseUrl: String, token: String, deviceId: String, simSlot: Int?, to: String, body: String): ApiResult<OutboundTask> =
        call(
            url(baseUrl, "/api/v1/outbound"), token,
            body = buildJsonObject {
                put("deviceId", deviceId)
                if (simSlot != null) put("simSlot", simSlot)
                put("to", to)
                put("body", body)
            }.toString(),
            serializer = OutboundTask.serializer(),
        )

    override suspend fun outbound(baseUrl: String, token: String, deviceId: String?, peer: String?, limit: Int): ApiResult<List<OutboundTask>> =
        call(
            url(baseUrl, "/api/v1/outbound", "device" to deviceId, "peer" to peer, "limit" to limit.toString()),
            token, body = null, serializer = TaskList.serializer(),
        ).map { it.items }

    override suspend fun cancelOutbound(baseUrl: String, token: String, taskId: String): ApiResult<OutboundTask> =
        call(url(baseUrl, "/api/v1/outbound/$taskId/cancel"), token, body = "{}", serializer = OutboundTask.serializer())

    override suspend fun deleteMessages(baseUrl: String, token: String, ids: List<Long>, deletePhone: Boolean): ApiResult<List<Long>> =
        call(
            url(baseUrl, "/api/v1/messages/delete"), token,
            body = buildJsonObject {
                put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
                if (deletePhone) put("deletePhone", true)
            }.toString(),
            serializer = DeletedIds.serializer(),
        ).map { it.deleted }

    override suspend fun deleteConversations(baseUrl: String, token: String, threads: List<RemoteConversation>, deletePhone: Boolean): ApiResult<Int> =
        call(
            url(baseUrl, "/api/v1/messages/delete-conversations"), token,
            body = buildJsonObject {
                put(
                    "conversations",
                    JsonArray(
                        threads.map { t ->
                            buildJsonObject {
                                put("peerKey", t.peerKey)
                                if (t.cardNumber.isNullOrEmpty()) put("deviceId", t.deviceId) else put("cardNumber", t.cardNumber)
                            }
                        },
                    ),
                )
                if (deletePhone) put("deletePhone", true)
            }.toString(),
            serializer = DeletedCount.serializer(),
        ).map { it.deleted }

    override suspend fun deletedMessages(baseUrl: String, token: String, before: Long?, beforeAt: Long?): ApiResult<List<RemoteMessage>> =
        call(
            url(baseUrl, "/api/v1/messages/deleted", "before" to before?.toString(), "beforeAt" to beforeAt?.toString(), "limit" to ViewApi.PAGE.toString()),
            token, body = null, serializer = MessagePage.serializer(),
        ).map { it.items }

    override suspend fun restoreMessage(baseUrl: String, token: String, id: Long): ApiResult<Unit> =
        call(url(baseUrl, "/api/v1/messages/$id/restore"), token, body = "{}", serializer = Empty.serializer()).map { }

    override suspend fun deletePhoneCopies(baseUrl: String, token: String, ids: List<Long>): ApiResult<List<Long>> =
        call(
            url(baseUrl, "/api/v1/messages/deleted/delete-phone"), token,
            body = buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }.toString(),
            serializer = QueuedIds.serializer(),
        ).map { it.queued }

    override suspend fun openEvents(baseUrl: String, token: String, lastEventId: String?): ApiResult<OpenStream> =
        withContext(Dispatchers.IO) {
            val target = url(baseUrl, "/api/v1/events") ?: return@withContext badAddress()
            val request = Request.Builder().url(target)
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
                .build()
            try {
                val call = streamHttp.newCall(request)
                val response = call.execute()
                when {
                    response.code == 200 -> ApiResult.Ok(OpenStream(response, call::cancel))
                    else -> response.use { r ->
                        if (r.isRedirect) notOurServer(r.code) else httpFailure(r.code, r.body?.string().orEmpty())
                    }
                }
            } catch (e: IOException) {
                transient(e)
            }
        }

    private fun url(baseUrl: String, path: String, vararg query: Pair<String, String?>): HttpUrl? {
        val base = (baseUrl.trimEnd('/') + path).toHttpUrlOrNull() ?: return null
        return base.newBuilder().apply {
            for ((k, v) in query) if (!v.isNullOrEmpty()) addQueryParameter(k, v)
        }.build()
    }

    private suspend fun <T> call(url: HttpUrl?, token: String?, body: String?, serializer: KSerializer<T>): ApiResult<T> =
        withContext(Dispatchers.IO) {
            if (url == null) return@withContext badAddress()
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            // The token only ever travels in this header, never in the URL (docs/协议.md §2).
            if (token != null) builder.header("Authorization", "Bearer $token")
            if (body != null) builder.post(body.toRequestBody(JSON_MEDIA)) else builder.get()
            try {
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> decodeOk(text, serializer)
                        response.isRedirect -> notOurServer(response.code)
                        else -> httpFailure(response.code, text)
                    }
                }
            } catch (e: IOException) {
                transient(e)
            }
        }

    private fun <T> decodeOk(text: String, serializer: KSerializer<T>): ApiResult<T> =
        try {
            ApiResult.Ok(json.decodeFromString(serializer, text.ifBlank { "{}" }))
        } catch (e: Exception) {
            // A 2xx that is not our JSON: a captive portal, a proxy's landing page, the wrong service on that port.
            notOurServer(200)
        }

    private fun httpFailure(status: Int, text: String): ApiResult.Failure {
        val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        return ApiResult.Failure(
            verdict = Connection.classify(httpStatus = status, errorCode = err?.error),
            kind = FailureKind.HTTP, status = status, code = err?.error, message = err?.message,
        )
    }

    private fun notOurServer(status: Int) = ApiResult.Failure(
        verdict = ConnectionVerdict.ADDRESS_SUSPECT, kind = FailureKind.NOT_OUR_SERVER, status = status, code = "bad_response",
    )

    private fun badAddress() = ApiResult.Failure(verdict = ConnectionVerdict.ADDRESS_SUSPECT, kind = FailureKind.NOT_OUR_SERVER, code = "bad_url")

    private fun transient(e: IOException) = ApiResult.Failure(
        verdict = Connection.classify(httpStatus = null),
        kind = when (e) {
            is SocketTimeoutException -> FailureKind.TIMEOUT
            is SSLException -> FailureKind.TLS
            else -> FailureKind.NETWORK
        },
        message = e.message,
    )

    @Serializable
    private data class ErrorBody(val error: String? = null, val message: String? = null)

    @Serializable
    private class Empty

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * An open event stream. Read [response] on one thread; [cancel] is the only thing safe to call from another one (closing
 * the body there races the reader inside okio).
 */
class OpenStream(val response: Response, private val cancelCall: () -> Unit) : java.io.Closeable {
    fun cancel() = cancelCall()
    override fun close() = response.close()
}

/** Ok values mapped, failures passed through. */
inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.Failure -> this
}
