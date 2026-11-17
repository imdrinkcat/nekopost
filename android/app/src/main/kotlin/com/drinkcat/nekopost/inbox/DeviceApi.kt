package com.drinkcat.nekopost.inbox

import android.net.Uri
import com.drinkcat.nekopost.forwarder.upload.ServerPins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val messagePattern = Regex("^m_[0-9A-HJKMNP-TV-Z]{26}$")
internal val pairPattern = Regex("^p_[A-Z2-7]{26}$")
private val shortPattern = Regex("[0-9A-HJKMNP-TV-Z]{10}")
private val longPattern = Regex("[A-Za-z0-9_-]{22}")
private val sessionToken = Regex("mgd_[A-Za-z0-9_-]{43}")

internal data class PairingInput(val server: HttpUrl, val code: String) {
    companion object {
        fun manual(server: String, code: String): PairingInput {
            val raw = server.trim()
            val url = raw.toHttpUrlOrNull() ?: error("invalid server URL")
            require(url.isHttps && url.encodedPath == "/" && url.query == null && url.fragment == null &&
                url.username.isEmpty() && url.password.isEmpty()) { "server must be a bare HTTPS origin" }
            val normalized = code.trim()
            val short = normalized.replace("-", "").uppercase(java.util.Locale.ROOT)
            return PairingInput(url, when {
                shortPattern.matches(short) -> short
                longPattern.matches(normalized) -> normalized
                else -> error("invalid pairing code")
            })
        }

        /**
         * Pasted or typed text (CONTRACT §2.1): the full `nekopost://enroll?…kind=device`
         * link (from the Telegram bot, the web panel or the CLI), or the short code
         * `XXXX-XXXX-XX` / long code with [server] typed or prefilled.
         */
        fun parse(text: String, server: String): PairingInput {
            val trimmed = text.trim()
            return if (trimmed.startsWith("nekopost:", ignoreCase = true)) scanned(trimmed) else manual(server, trimmed)
        }

        /** True for a device pairing link (the wizard routes it to device pairing). */
        fun isDeviceLink(text: String): Boolean = runCatching { scanned(text.trim()) }.isSuccess

        fun scanned(value: String): PairingInput {
            require(value.length <= 2048)
            val uri = Uri.parse(value)
            require(uri.scheme == "nekopost" && uri.authority == "enroll" && uri.path.isNullOrEmpty() &&
                uri.fragment == null && uri.queryParameterNames == setOf("v", "kind", "url", "code") &&
                uri.queryParameterNames.all { uri.getQueryParameters(it).size == 1 }) { "invalid device pairing link" }
            require(uri.getQueryParameter("v") == "1" && uri.getQueryParameter("kind") == "device")
            return manual(uri.getQueryParameter("url").orEmpty(), uri.getQueryParameter("code").orEmpty())
        }
    }
}

internal data class DeviceSession(
    val server: HttpUrl,
    val deviceID: String,
    val token: String,
    val source: String,
    val scopes: Set<String>,
) {
    init {
        require(Regex("^d_[A-Z2-7]{26}$").matches(deviceID) && sessionToken.matches(token))
    }

    fun encode() = JSONObject().put("url", server.toString()).put("id", deviceID)
        .put("token", token).put("source", source).put("scopes", scopes.joinToString(",")).toString()

    companion object {
        fun decode(raw: String): DeviceSession = JSONObject(raw).let {
            val server = it.getString("url").toHttpUrlOrNull() ?: error("invalid server URL")
            require(server.isHttps && server.username.isEmpty() && server.password.isEmpty())
            DeviceSession(server, it.getString("id"), it.getString("token"), it.getString("source"),
                it.optString("scopes").split(",").filter(String::isNotBlank).toSet())
        }
    }

    override fun toString(): String = "DeviceSession(${server.host})"
}

/** Diagnostic messages only: the UI maps failures to string resources (DeviceInbox.failureText). */
internal class ApiFailure(val status: Int, val code: String) : IllegalStateException("server request failed ($status, $code)")

internal data class DeviceReply(val status: Int, val json: JSONObject)

internal class DeviceApi(private val keys: DeviceKeys) {
    private val offset = AtomicLong(0)
    private val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun call(
        server: HttpUrl,
        method: String,
        segments: List<String>,
        body: JSONObject? = null,
        token: String? = null,
        proof: Boolean = token != null,
        query: Map<String, String> = emptyMap(),
    ): DeviceReply = withContext(Dispatchers.IO) {
        val url = server.newBuilder().encodedPath("/").query(null).apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val target = url.newBuilder().query(null).build().toString()
        val client = base.newBuilder().certificatePinner(ServerPins.pinnerFor(url.host, Instant.now())).build()
        fun run(nonce: String?): Triple<DeviceReply, String?, Long?> {
            val builder = Request.Builder().url(url).method(method, body?.toString()?.toRequestBody(JSON))
            if (token != null) builder.header("Authorization", "DPoP $token")
            if (proof) {
                builder.header("DPoP", keys.proof(method, target, token, Instant.now().epochSecond + offset.get(), nonce))
            }
            return client.newCall(builder.build()).execute().use { res ->
                val data = res.peekBody(1024L * 1024).string()
                val json = if (data.isBlank()) JSONObject() else JSONObject(data)
                val remoteAt = runCatching {
                    ZonedDateTime.parse(res.header("Date"), DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                }.getOrNull()
                Triple(DeviceReply(res.code, json), res.header("DPoP-Nonce"), remoteAt)
            }
        }
        val (first, nonce, serverTime) = run(null)
        val final = if (first.status == 401 && proof && nonce != null && serverTime != null &&
            first.json.optJSONObject("error")?.optString("code") == "use_dpop_nonce"
        ) {
            offset.set(serverTime - Instant.now().epochSecond)
            run(nonce).first
        } else first
        if (final.status !in 200..299) {
            throw ApiFailure(final.status, final.json.optJSONObject("error")?.optString("code").orEmpty())
        }
        final
    }

    suspend fun events(session: DeviceSession, after: Long, onEvent: (Long?) -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val url = session.server.newBuilder().encodedPath("/v1/events").query(null).build()
            val request = Request.Builder().url(url)
                .header("Authorization", "DPoP ${session.token}")
                .header("DPoP", keys.proof("GET", url.toString(), session.token, Instant.now().epochSecond + offset.get(), null))
                .header("Last-Event-ID", after.toString()).build()
            val client = base.newBuilder().certificatePinner(ServerPins.pinnerFor(url.host, Instant.now()))
                .callTimeout(0, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw ApiFailure(it.code, "")
                            val source = it.body.source()
                            var event = ""
                            var id: Long? = null
                            while (!source.exhausted()) {
                                val line = source.readUtf8LineStrict(16384)
                                if (line.startsWith("id: ")) id = line.substring(4).toLongOrNull()
                                else if (line.startsWith("event: ")) event = line.substring(7)
                                else if (line.isBlank()) {
                                    if (event == "reset") onEvent(null)
                                    else if (event.startsWith("message.") && id != null) onEvent(id)
                                    id = null
                                    event = ""
                                }
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private companion object { val JSON = "application/json".toMediaType() }
}
