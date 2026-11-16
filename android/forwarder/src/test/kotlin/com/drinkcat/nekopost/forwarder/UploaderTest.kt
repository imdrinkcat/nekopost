package com.drinkcat.nekopost.forwarder

import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.forwarder.alert.AlertState
import com.drinkcat.nekopost.forwarder.crypto.IdempotencyKeys
import com.drinkcat.nekopost.forwarder.outbox.NewItem
import com.drinkcat.nekopost.forwarder.outbox.Outbox
import com.drinkcat.nekopost.forwarder.outbox.OutboxEntity
import com.drinkcat.nekopost.forwarder.outbox.OutgoingMessage
import com.drinkcat.nekopost.forwarder.outbox.toItem
import com.drinkcat.nekopost.forwarder.store.SecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException
import java.time.Duration

/** Outbox encryption and the upload state machine against MockWebServer. */
@RunWith(RobolectricTestRunner::class)
class UploaderTest {
    private val env = TestEnv(ApplicationProvider.getApplicationContext())
    private val server = MockWebServer().apply { start() }
    private val dao get() = env.db.dao()

    @After
    fun tearDown() {
        server.close()
        env.close()
    }

    private suspend fun enroll() = env.enroll(serverUrl = server.url("/").toString().removeSuffix("/"))

    private suspend fun queue(body: String = "验证码 123456", kind: ForwardKind = ForwardKind.Sms): Long =
        env.graph.outbox.enqueue(
            NewItem(kind, "+819012345678", null, IdempotencyKeys.random(), OutgoingMessage("sms", "+819012345678", body)),
        )!!

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
        for ((n, v) in headers) builder.addHeader(n, v)
        server.enqueue(builder.build())
    }

    // --- encryption ----------------------------------------------------------

    @Test
    fun payloadIsEncryptedAndBoundToItsRow() = runBlocking {
        enroll()
        val id = queue("秘密的验证码 987654")
        val row = dao.get(id)!!
        assertFalse(String(row.payloadCt, Charsets.ISO_8859_1).contains("987654"))
        assertFalse(String(row.payloadCt, Charsets.UTF_8).contains("秘密"))
        assertTrue(env.graph.outbox.open(row).contains("秘密的验证码 987654"))

        // AAD = row id: a ciphertext moved to another row does not decrypt.
        try {
            env.graph.outbox.open(row.copy(id = id + 1))
            fail("moved ciphertext must not decrypt")
        } catch (expected: GeneralSecurityException) {
        }
        // Crypto-shredding: with the key gone nothing is readable.
        env.keys.destroy()
        try {
            env.graph.outbox.open(row)
            fail("destroyed key must not decrypt")
        } catch (expected: GeneralSecurityException) {
        }
    }

    @Test
    fun secretsAreStoredEncrypted() {
        val token = "mgs_abcdefghijklmnopqrstuvwxyz012345"
        val prefs = env.secretPrefs
        val store = SecretStore(prefs, env.keys)
        store.setToken(token)
        val secret = store.deviceSecret()
        assertEquals(32, secret.size)
        assertArrayEquals(secret, store.deviceSecret())
        assertEquals(token, store.token())
        val raw = prefs.all.values.joinToString()
        assertFalse(raw.contains("mgs_"))
        assertFalse(raw.contains("abcdefghijklmnop"))

        env.keys.destroy()
        assertNull(store.token())
        store.clear()
        assertTrue(prefs.all.isEmpty())
    }

    // --- uploads -------------------------------------------------------------

    @Test
    fun acceptedItemsLeaveTheQueue() = runBlocking {
        enroll()
        val id = queue()
        respond(202, """{"ok":true,"id":"m_01","duplicate":false}""")

        assertEquals(1, env.graph.uploader.drain())
        assertNull(dao.get(id))
        val log = dao.latestLog(5).single().toItem()
        assertEquals(ForwardStatus.Accepted, log.status)
        assertEquals("m_01", log.serverMessageId)
        assertEquals(202, log.lastHttpStatus)
        assertEquals(1, log.attempts)
        assertEquals(ForwardKind.Sms, env.prefs.current().lastSuccess!!.kind)

        val request = server.takeRequest()
        assertEquals("Bearer mgs_abcdefghijklmnopqrstuvwxyz012345", request.headers["Authorization"])
        assertNotNull(request.headers["Idempotency-Key"])
        // Accepted keys are remembered: the same key is not queued again.
        val key = request.headers["Idempotency-Key"]!!
        assertNull(env.graph.outbox.enqueue(NewItem(ForwardKind.Sms, "x", null, key, OutgoingMessage("sms", "x", "y"))))
        assertEquals(0, dao.queueCounts().first().pending)
    }

    @Test
    fun duplicateIsAlsoSuccess() = runBlocking {
        enroll()
        queue()
        respond(200, """{"ok":true,"id":"m_02","duplicate":true}""")
        env.graph.uploader.drain()
        assertEquals(ForwardStatus.Duplicate, dao.latestLog(5).single().toItem().status)
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun rateLimitWaitsForRetryAfterAndHoldsTheWholeQueue() = runBlocking {
        enroll()
        val first = queue("a")
        val second = queue("b")
        respond(429, """{"ok":false,"error":{"code":"rate_limited"}}""", "Retry-After" to "120")

        assertEquals(0, env.graph.uploader.drain())
        assertEquals(1, server.requestCount)
        val now = env.clock.millis()
        val row = dao.get(first)!!
        assertEquals(OutboxEntity.STATE_PENDING, row.state)
        assertEquals(1, row.attempts)
        assertEquals(now + 120_000, row.nextAt)
        assertEquals(429, row.lastHttp)
        assertEquals(now + 120_000, dao.get(second)!!.nextAt)
        assertEquals(Duration.ofSeconds(120), env.scheduler.uploadAfter.last())
        assertEquals(FailureReason.RateLimited, env.prefs.current().lastFailure!!.reason)
        assertEquals(ForwardStatus.Retrying, dao.latestLog(5).first { it.id == row.logId }.toItem().status)

        // Not due yet: nothing is sent.
        assertEquals(0, env.graph.uploader.drain())
        assertEquals(1, server.requestCount)

        env.clock.advance(Duration.ofSeconds(120))
        respond(202, """{"ok":true,"id":"m_a"}""")
        respond(202, """{"ok":true,"id":"m_b"}""")
        assertEquals(2, env.graph.uploader.drain())
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun itemsQueuedDuringRetryAfterWaitToo() = runBlocking {
        enroll()
        queue("a")
        respond(429, """{"ok":false,"error":{"code":"rate_limited"}}""", "Retry-After" to "600")
        env.graph.uploader.drain()
        assertEquals(1, server.requestCount)

        // A new SMS arrives a minute later and triggers an immediate upload run.
        env.clock.advance(Duration.ofMinutes(1))
        val late = queue("b")
        assertEquals(0, env.graph.uploader.drain())
        assertEquals(1, server.requestCount)
        assertEquals(env.clock.millis() + 540_000, dao.get(late)!!.nextAt)
        assertEquals(Duration.ofSeconds(540), env.scheduler.uploadAfter.last())

        // "立即重试" overrides the wait.
        AndroidForwarderController(env.graph, env.scope).retryNow()
        withTimeout(5_000) {
            while (env.prefs.current().retryNotBefore != null || dao.due(env.clock.millis(), 10).size < 2) delay(10)
        }
        respond(202, """{"ok":true,"id":"m_a"}""")
        respond(202, """{"ok":true,"id":"m_b"}""")
        assertEquals(2, env.graph.uploader.drain())
    }

    @Test
    fun pausesAndStuckItemsRaiseAlerts() = runBlocking {
        enroll()
        queue()
        server.close()
        env.graph.uploader.drain()
        assertEquals(AlertState.None, env.alerts.current)

        // Still not delivered 30 minutes later: "有 N 条消息未能转发".
        env.clock.advance(Duration.ofMinutes(31))
        queue("second")
        env.graph.uploader.drain()
        assertEquals(AlertState(paused = null, stuck = 2), env.alerts.current)
    }

    @Test
    fun unauthorizedRaisesThePauseAlertAndAcceptanceClearsIt() = runBlocking {
        enroll()
        queue()
        respond(401, """{"ok":false,"error":{"code":"unauthorized"}}""")
        env.graph.uploader.drain()
        assertEquals(AlertState(paused = PauseReason.Unauthorized), env.alerts.current)

        // The test message goes out even while paused; success clears the pause and the alert.
        respond(202, """{"ok":true,"id":"m_t"}""")
        respond(202, """{"ok":true,"id":"m_1"}""")
        val id = queue("test")
        env.graph.uploader.sendOne(id)
        env.graph.uploader.drain()
        assertEquals(AlertState.None, env.alerts.current)
    }

    @Test
    fun keystoreOutageDoesNotFailItems() = runBlocking {
        enroll()
        val id = queue()
        val token = env.graph.secrets.token()
        assertNotNull(token)
        // The keyset cannot be loaded for a while (e.g. right after an OTA).
        env.keys.unavailable = true
        try {
            env.graph.uploader.drain()
            fail("expected the run to stop")
        } catch (expected: GeneralSecurityException) {
            // UploadWorker retries later.
        }
        env.keys.unavailable = false
        // Neither failed nor paused as "token unreadable": just not sent yet.
        assertEquals(OutboxEntity.STATE_PENDING, dao.get(id)!!.state)
        assertNull(env.prefs.current().paused)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun serverErrorsBackOffExponentially() = runBlocking {
        enroll()
        val id = queue()
        respond(500)
        env.graph.uploader.drain()
        assertEquals(env.clock.millis() + 30_000, dao.get(id)!!.nextAt)
        env.clock.advance(Duration.ofSeconds(30))
        respond(502)
        env.graph.uploader.drain()
        val row = dao.get(id)!!
        assertEquals(2, row.attempts)
        assertEquals(env.clock.millis() + 60_000, row.nextAt)
        assertEquals(FailureReason.Server, env.prefs.current().lastFailure!!.reason)
    }

    @Test
    fun offlineKeepsTheItem() = runBlocking {
        enroll()
        val id = queue()
        server.close()
        env.graph.uploader.drain()
        val row = dao.get(id)!!
        assertEquals(OutboxEntity.STATE_PENDING, row.state)
        assertEquals(FailureReason.Network, env.prefs.current().lastFailure!!.reason)
        assertEquals(ForwardStatus.Retrying, dao.latestLog(5).single().toItem().status)
    }

    @Test
    fun unauthorizedPausesAndKeepsTheQueue() = runBlocking {
        enroll()
        val first = queue("a")
        val second = queue("b")
        respond(401, """{"ok":false,"error":{"code":"unauthorized"}}""")

        env.graph.uploader.drain()
        assertEquals(PauseReason.Unauthorized, env.prefs.current().paused)
        assertEquals(FailureReason.Unauthorized, env.prefs.current().lastFailure!!.reason)
        assertNotNull(dao.get(first))
        assertNotNull(dao.get(second))
        assertTrue(dao.latestLog(5).all { it.toItem().status == ForwardStatus.Paused })

        // While paused nothing is sent, not even after the backoff.
        env.clock.advance(Duration.ofHours(1))
        assertEquals(0, env.graph.uploader.drain())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sourceDisabledPausesUntilTheServerAcceptsAgain() = runBlocking {
        enroll()
        queue()
        respond(403, """{"ok":false,"error":{"code":"source_disabled"}}""")
        env.graph.uploader.drain()
        assertEquals(PauseReason.SourceDisabled, env.prefs.current().paused)

        // The hourly check: the source was re-enabled.
        respond(202, """{"ok":true,"id":"m_ok"}""")
        assertEquals(1, env.graph.uploader.drain(ignorePause = true))
        assertNull(env.prefs.current().paused)
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun rejectedItemsFailPermanently() = runBlocking {
        enroll()
        val bad = queue("bad")
        val good = queue("good")
        respond(422, """{"ok":false,"error":{"code":"validation_failed"}}""")
        respond(202, """{"ok":true,"id":"m_good"}""")

        assertEquals(1, env.graph.uploader.drain())
        val row = dao.get(bad)!!
        assertEquals(OutboxEntity.STATE_FAILED, row.state)
        assertEquals(422, row.lastHttp)
        assertNull(dao.get(good))
        val counts = dao.queueCounts().first()
        assertEquals(0, counts.pending)
        assertEquals(1, counts.failed)
        assertEquals(ForwardStatus.Failed, dao.latestLog(5).first { it.id == row.logId }.toItem().status)

        // Failed items are never retried.
        env.clock.advance(Duration.ofDays(1))
        assertEquals(0, env.graph.uploader.drain())
        assertEquals(2, server.requestCount)
        assertEquals(1, dao.deleteFailed())
    }

    @Test
    fun retentionDropsOldFailuresAndExpiredItems() = runBlocking {
        enroll()
        val failed = queue("bad")
        respond(413)
        env.graph.uploader.drain()
        val stale = queue("stale")
        server.close()
        env.graph.uploader.drain()

        env.clock.advance(Duration.ofDays(8))
        env.graph.outbox.prune()
        assertNull(dao.get(failed))
        assertNotNull(dao.get(stale))

        env.clock.advance(Duration.ofDays(23))
        assertEquals(1, env.graph.outbox.prune())
        assertNull(dao.get(stale))
        assertEquals("expired", dao.latestLog(5).first().lastError)
        assertEquals(Outbox.MAX_ROWS, 5000)
    }

    @Test
    fun nothingHappensWhenNotEnrolled() = runBlocking {
        assertEquals(0, env.graph.uploader.drain())
        assertEquals(0, server.requestCount)
    }
}
