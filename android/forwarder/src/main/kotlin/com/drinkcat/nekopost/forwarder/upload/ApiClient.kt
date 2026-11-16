package com.drinkcat.nekopost.forwarder.upload

import com.drinkcat.nekopost.core.forwarder.EnrollFailure
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.PauseReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SSLPeerUnverifiedException

/** Result of one `POST /v1/messages`. */
internal sealed interface UploadOutcome {
    /** 202 (new) or 200 (duplicate): delete the row. */
    data class Accepted(val serverMessageId: String?, val duplicate: Boolean, val httpStatus: Int) : UploadOutcome

    /** Network error, 429, 5xx …: try again later ([retryAfter] from the server if it sent one). */
    data class Retry(
        val reason: FailureReason,
        val httpStatus: Int?,
        val retryAfter: Duration?,
        val detail: String?,
    ) : UploadOutcome

    /** 400 / 413 / 415 / 422: this item can never succeed. */
    data class Rejected(val reason: FailureReason, val httpStatus: Int?, val detail: String?) : UploadOutcome

    /** 401 / 403: stop forwarding, keep the queue. */
    data class Paused(val reason: PauseReason, val httpStatus: Int?, val detail: String?) : UploadOutcome
}

internal sealed interface ProbeOutcome {
    data object Valid : ProbeOutcome
    data class Failed(val reason: EnrollFailure, val httpStatus: Int?) : ProbeOutcome
}

/**
 * Minimal Nekopost API client for one source token. Blocking; call it on an
 * IO dispatcher. Never logs: neither the token nor any body leaves this class
 * except in the request itself.
 */
internal class ApiClient(
    private val baseUrl: HttpUrl,
    private val token: String,
    private val http: OkHttpClient,
    private val now: () -> Instant = Instant::now,
    private val sourceId: String? = null,
) {
    fun postMessage(json: String, idempotencyKey: String): UploadOutcome {
        val source = sourceId
        val target = checkNotNull(baseUrl.resolve("/v1/messages"))
        val signer = ForwarderDeviceAuth.signer
        fun request(proof: ForwarderDeviceAuth.Proof?): Request = Request.Builder()
            .url(target)
            .header("Authorization", if (proof == null) "Bearer $token" else "DPoP ${proof.token}")
            .apply { if (proof != null) header("DPoP", proof.jws) }
            .header("Idempotency-Key", idempotencyKey)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(json.toRequestBody(JSON))
            .build()
        return try {
            val proof = source?.let { signer?.invoke(baseUrl, it, target.toString(), now(), null) }
            val outcome = http.newCall(request(proof)).execute().use { response ->
                val body = readSmall(response)
                val nonce = response.header("DPoP-Nonce")
                val signedAt = response.headers.getDate("Date")?.toInstant()
                if (proof != null && response.code == 401 && nonce != null && signedAt != null &&
                    parse(body)?.errorCode() == "use_dpop_nonce" && source != null
                ) {
                    signer?.invoke(baseUrl, source, target.toString(), signedAt, nonce) to classify(response.code, retryAfter(response), body)
                } else null to classify(response.code, retryAfter(response), body)
            }
            val retry = outcome.first
            if (retry == null) outcome.second else http.newCall(request(retry)).execute().use { response ->
                classify(response.code, retryAfter(response), readSmall(response))
            }
        } catch (e: IOException) {
            networkFailure(e)
        } catch (_: java.security.GeneralSecurityException) {
            UploadOutcome.Retry(FailureReason.Server, null, null, "device_key_unavailable")
        }
    }

    /**
     * Checks the token without creating anything: `GET /v1/messages/<probe id>`
     * answers 404 `not_found` for a valid token (the id does not exist),
     * 401 for a bad token and 403 for a disabled source.
     */
    fun probe(): ProbeOutcome {
        val request = Request.Builder()
            .url(baseUrl.resolve("/v1/messages/$PROBE_ID")!!)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                val body = parse(readSmall(response))
                when {
                    code == 404 && body?.errorCode() == "not_found" -> ProbeOutcome.Valid
                    code == 200 && body?.bool("ok") == true -> ProbeOutcome.Valid
                    code == 401 -> ProbeOutcome.Failed(EnrollFailure.Unauthorized, code)
                    code == 403 -> ProbeOutcome.Failed(EnrollFailure.SourceDisabled, code)
                    else -> ProbeOutcome.Failed(EnrollFailure.Server, code)
                }
            }
        } catch (e: IOException) {
            val reason = if (isPinningFailure(e)) EnrollFailure.CertificatePinning else EnrollFailure.Network
            ProbeOutcome.Failed(reason, null)
        }
    }

    private fun retryAfter(response: Response): Duration? {
        val raw = response.header("Retry-After")?.trim() ?: return null
        raw.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
        val date = response.headers.getDate("Retry-After") ?: return null
        return Duration.between(now(), date.toInstant()).takeIf { !it.isNegative } ?: Duration.ZERO
    }

    private fun readSmall(response: Response): String? = try {
        response.peekBody(MAX_RESPONSE_BYTES).string()
    } catch (e: IOException) {
        null
    }

    companion object {
        /** Well-formed message id that never exists. */
        const val PROBE_ID = "m_00000000000000000000000000"
        private const val USER_AGENT = "Nekopost-Android"
        private const val MAX_RESPONSE_BYTES = 64L * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Maps a server answer to what the queue does with the item (CONTRACT.md §1). */
        fun classify(code: Int, retryAfter: Duration?, body: String?): UploadOutcome {
            val json = parse(body)
            val errorCode = json?.errorCode()
            return when (code) {
                in 200..299 -> UploadOutcome.Accepted(
                    serverMessageId = json?.string("id"),
                    duplicate = json?.bool("duplicate") ?: (code == 200),
                    httpStatus = code,
                )
                401 -> UploadOutcome.Paused(PauseReason.Unauthorized, code, errorCode ?: "unauthorized")
                403 -> UploadOutcome.Paused(PauseReason.SourceDisabled, code, errorCode ?: "source_disabled")
                400, 413, 415, 422 -> UploadOutcome.Rejected(FailureReason.Rejected, code, errorCode ?: "http_$code")
                429, 503 -> UploadOutcome.Retry(FailureReason.RateLimited, code, retryAfter, errorCode ?: "http_$code")
                else -> UploadOutcome.Retry(FailureReason.Server, code, retryAfter, errorCode ?: "http_$code")
            }
        }

        fun networkFailure(e: IOException): UploadOutcome.Retry =
            if (isPinningFailure(e)) {
                UploadOutcome.Retry(FailureReason.CertificatePinning, null, null, "certificate_pinning")
            } else {
                UploadOutcome.Retry(FailureReason.Network, null, null, e.javaClass.simpleName)
            }

        /**
         * OkHttp tries every address of the host; when the pin check fails on
         * one and another address then refuses the connection, the pinning
         * error only survives as a suppressed exception, so search the chain.
         */
        private fun isPinningFailure(e: Throwable, depth: Int = 0): Boolean {
            if (e is SSLPeerUnverifiedException && e.message?.contains("pinning", ignoreCase = true) == true) return true
            if (depth >= 5) return false
            val cause = e.cause
            if (cause != null && cause !== e && isPinningFailure(cause, depth + 1)) return true
            return e.suppressed.any { isPinningFailure(it, depth + 1) }
        }

        private fun parse(body: String?): JsonObject? {
            if (body.isNullOrBlank()) return null
            return try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.errorCode(): String? = (this["error"] as? JsonObject)?.string("code")?.take(40)
    }
}
