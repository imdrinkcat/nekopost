package com.drinkcat.nekopost.forwarder

import com.drinkcat.nekopost.core.forwarder.EnrollFailure
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.forwarder.upload.ApiClient
import com.drinkcat.nekopost.forwarder.upload.HttpClients
import com.drinkcat.nekopost.forwarder.upload.ProbeOutcome
import com.drinkcat.nekopost.forwarder.upload.UploadOutcome
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Status mapping against a real HTTP server (docs/api.md §3, §7). */
class ApiClientTest {
    private val token = "mgs_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = ApiClient(server.url("/"), token, http)

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json; charset=utf-8")
        for ((name, value) in headers) builder.addHeader(name, value)
        server.enqueue(builder.build())
    }

    private fun post(): UploadOutcome = client().postMessage("""{"title":"t","body":"b","type":"sms"}""", "sms:0123456789abcdef0123456789abcdef")

    @Test
    fun accepted202SendsTokenKeyAndJson() {
        respond(202, """{"ok":true,"id":"m_01JB7Q4ZK3M8X2N5R6T7V8W9YA","status":"queued","duplicate":false}""")
        assertEquals(UploadOutcome.Accepted("m_01JB7Q4ZK3M8X2N5R6T7V8W9YA", false, 202), post())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.url.encodedPath)
        assertEquals("Bearer $token", request.headers["Authorization"])
        assertEquals("sms:0123456789abcdef0123456789abcdef", request.headers["Idempotency-Key"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("""{"title":"t","body":"b","type":"sms"}""", request.body!!.utf8())
    }

    @Test
    fun duplicate200() {
        respond(200, """{"ok":true,"id":"m_X","duplicate":true}""")
        assertEquals(UploadOutcome.Accepted("m_X", true, 200), post())
    }

    @Test
    fun unauthorizedAndDisabledPause() {
        respond(401, """{"ok":false,"error":{"code":"unauthorized"}}""", "WWW-Authenticate" to "Bearer realm=\"nekopost\"")
        assertEquals(UploadOutcome.Paused(PauseReason.Unauthorized, 401, "unauthorized"), post())
        respond(403, """{"ok":false,"error":{"code":"source_disabled"}}""")
        assertEquals(UploadOutcome.Paused(PauseReason.SourceDisabled, 403, "source_disabled"), post())
    }

    @Test
    fun permanentRejections() {
        respond(413, """{"ok":false,"error":{"code":"payload_too_large"}}""")
        assertEquals(UploadOutcome.Rejected(FailureReason.Rejected, 413, "payload_too_large"), post())
        respond(415, """{"ok":false,"error":{"code":"unsupported_media_type"}}""")
        assertEquals(UploadOutcome.Rejected(FailureReason.Rejected, 415, "unsupported_media_type"), post())
        respond(422, """{"ok":false,"error":{"code":"validation_failed","details":[{"field":"priority"}]}}""")
        assertEquals(UploadOutcome.Rejected(FailureReason.Rejected, 422, "validation_failed"), post())
    }

    @Test
    fun rateLimitHonoursRetryAfter() {
        respond(429, """{"ok":false,"error":{"code":"rate_limited"}}""", "Retry-After" to "17")
        assertEquals(UploadOutcome.Retry(FailureReason.RateLimited, 429, Duration.ofSeconds(17), "rate_limited"), post())
        respond(503, """{"ok":false,"error":{"code":"busy"}}""", "Retry-After" to "5")
        assertEquals(UploadOutcome.Retry(FailureReason.RateLimited, 503, Duration.ofSeconds(5), "busy"), post())
    }

    @Test
    fun retryAfterAsHttpDate() {
        val now = Instant.parse("2026-11-16T01:20:00Z")
        respond(429, "", "Retry-After" to "Mon, 16 Nov 2026 01:21:30 GMT")
        val outcome = ApiClient(server.url("/"), token, http) { now }.postMessage("{}", "k")
        assertEquals(UploadOutcome.Retry(FailureReason.RateLimited, 429, Duration.ofSeconds(90), "http_429"), outcome)
    }

    @Test
    fun serverErrorsRetry() {
        respond(500, """{"ok":false,"error":{"code":"internal_error"}}""")
        assertEquals(UploadOutcome.Retry(FailureReason.Server, 500, null, "internal_error"), post())
        respond(502, "<html>bad gateway</html>")
        assertEquals(UploadOutcome.Retry(FailureReason.Server, 502, null, "http_502"), post())
    }

    @Test
    fun offlineIsANetworkRetry() {
        val url = server.url("/")
        server.close()
        val outcome = ApiClient(url, token, http).postMessage("{}", "k")
        assertTrue(outcome is UploadOutcome.Retry)
        assertEquals(FailureReason.Network, (outcome as UploadOutcome.Retry).reason)
        assertEquals(null, outcome.httpStatus)
    }

    @Test
    fun probe() {
        respond(404, """{"ok":false,"error":{"code":"not_found"}}""")
        assertEquals(ProbeOutcome.Valid, client().probe())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/messages/${ApiClient.PROBE_ID}", request.url.encodedPath)
        assertEquals("Bearer $token", request.headers["Authorization"])

        respond(401, """{"ok":false,"error":{"code":"unauthorized"}}""")
        assertEquals(ProbeOutcome.Failed(EnrollFailure.Unauthorized, 401), client().probe())
        respond(403, """{"ok":false,"error":{"code":"source_disabled"}}""")
        assertEquals(ProbeOutcome.Failed(EnrollFailure.SourceDisabled, 403), client().probe())
        // A 404 that is not Nekopost's JSON (wrong host / proxy) is not a valid token.
        respond(404, "<html>not found</html>")
        assertEquals(ProbeOutcome.Failed(EnrollFailure.Server, 404), client().probe())
    }

    @Test
    fun pinningRejectsCertificatesOutsideTheIsrgRoots() {
        val localhost = InetAddress.getByName("localhost")
        val cert = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(localhost.canonicalHostName)
            .addSubjectAlternativeName(localhost.hostAddress!!)
            .build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val tls = MockWebServer()
        tls.useHttps(serverCerts.sslSocketFactory())
        tls.start()
        try {
            val base = OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
            val url = tls.url("/")
            assertTrue(url.isHttps)

            // Before expiry: the self-signed (trusted!) certificate is still refused.
            val pinned = HttpClients(base, MutableClock(Instant.parse("2026-11-16T00:00:00Z"))).forServer(url)
            val refused = ApiClient(url, token, pinned).postMessage("{}", "k")
            assertEquals(UploadOutcome.Retry(FailureReason.CertificatePinning, null, null, "certificate_pinning"), refused)
            // (No response is consumed: the client gives up right after the handshake.)
            assertEquals(ProbeOutcome.Failed(EnrollFailure.CertificatePinning, null), ApiClient(url, token, pinned).probe())

            // After expiry pinning fails open to normal certificate validation.
            val expired = HttpClients(base, MutableClock(Instant.parse("2028-01-01T00:00:00Z"))).forServer(url)
            tls.enqueue(MockResponse.Builder().code(202).body("""{"ok":true,"id":"m_P","duplicate":false}""").build())
            assertEquals(UploadOutcome.Accepted("m_P", false, 202), ApiClient(url, token, expired).postMessage("{}", "k"))
        } finally {
            tls.close()
        }
    }
}
