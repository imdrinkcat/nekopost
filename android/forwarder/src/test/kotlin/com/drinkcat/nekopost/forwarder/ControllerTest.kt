package com.drinkcat.nekopost.forwarder

import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import com.drinkcat.nekopost.core.enroll.EnrollmentParseResult
import com.drinkcat.nekopost.core.forwarder.EnrollFailure
import com.drinkcat.nekopost.core.forwarder.EnrollResult
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.core.forwarder.SendTestResult
import com.drinkcat.nekopost.forwarder.sms.InboxSms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class ControllerTest {
    private val env = TestEnv(ApplicationProvider.getApplicationContext())
    private val server = MockWebServer().apply { start() }
    private val controller = AndroidForwarderController(env.graph, env.scope)
    private val token = "mgs_abcdefghijklmnopqrstuvwxyz012345"

    @After
    fun tearDown() {
        server.close()
        env.close()
    }

    private fun link(): EnrollmentLink {
        // Debug-only plain http to localhost, as the debug app allows.
        val base = "http://localhost:${server.port}"
        val result = EnrollmentLink.create(base, "fold8", token, "Fold8 主力机", allowInsecureLocalhost = true)
        return (result as EnrollmentParseResult.Valid).link
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())
    }

    private suspend fun awaitState(predicate: (ForwarderState) -> Boolean): ForwarderState =
        withTimeout(5_000) { controller.state.first(predicate) }

    @Test
    fun enrollFailsClosedWithoutTheKeystore() = runBlocking {
        respond(404, """{"ok":false,"error":{"code":"not_found"}}""")
        env.keys.unavailable = true
        assertEquals(EnrollResult.Failure(EnrollFailure.SecureStorage), controller.enroll(link()))
        assertTrue(env.secretPrefs.all.isEmpty())
        assertNull(env.prefs.current().enrollment)
    }

    @Test
    fun enrollProbesWithoutCreatingAMessageAndSetsTheSmsStartPoint() = runBlocking {
        repeat(3) { env.inbox.rows += InboxSms(it + 1L, "+81", "旧短信", 0, 0, 1) }
        respond(404, """{"ok":false,"error":{"code":"not_found","message":"消息不存在或不属于该来源"}}""")

        val result = controller.enroll(link())

        assertTrue(result is EnrollResult.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.url.encodedPath.startsWith("/v1/messages/m_"))
        assertEquals("Bearer $token", request.headers["Authorization"])
        val state = awaitState { it.isEnrolled }
        assertEquals("fold8", state.enrollment!!.sourceId)
        assertEquals("Fold8 主力机", state.enrollment!!.sourceName)
        assertEquals(3L, env.prefs.current().smsLastSeenId)
        assertEquals(token, env.graph.secrets.token())
        assertFalse(env.secretPrefs.all.values.joinToString().contains(token))
        assertTrue(env.scheduler.periodic > 0)

        assertEquals(EnrollResult.Failure(EnrollFailure.AlreadyEnrolled), controller.enroll(link()))
    }

    @Test
    fun enrollFailures() = runBlocking {
        respond(401, """{"ok":false,"error":{"code":"unauthorized"}}""")
        assertEquals(EnrollResult.Failure(EnrollFailure.Unauthorized, 401), controller.enroll(link()))
        respond(403, """{"ok":false,"error":{"code":"source_disabled"}}""")
        assertEquals(EnrollResult.Failure(EnrollFailure.SourceDisabled, 403), controller.enroll(link()))
        respond(500, """{"ok":false,"error":{"code":"internal_error"}}""")
        assertEquals(EnrollResult.Failure(EnrollFailure.Server, 500), controller.enroll(link()))
        assertNull(env.prefs.current().enrollment)
        assertNull(env.graph.secrets.token())
        server.close()
        assertEquals(EnrollResult.Failure(EnrollFailure.Network), controller.enroll(link()))
    }

    @Test
    fun sendTestPostsATestMessage() = runBlocking {
        assertEquals(SendTestResult.NotEnrolled, controller.sendTest())
        respond(404, """{"ok":false,"error":{"code":"not_found"}}""")
        controller.enroll(link())
        server.takeRequest()

        respond(202, """{"ok":true,"id":"m_TEST","duplicate":false}""")
        assertEquals(SendTestResult.Accepted("m_TEST", false), controller.sendTest())
        val request = server.takeRequest()
        val json = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("test", json["type"]!!.jsonPrimitive.content)
        assertEquals("POST", request.method)

        val log = withTimeout(5_000) { controller.observeLog().first { it.isNotEmpty() } }
        assertEquals(ForwardKind.Test, log.first().kind)
        assertEquals(ForwardStatus.Accepted, log.first().status)
        assertEquals("测试消息", log.first().title)
    }

    @Test
    fun sendTestWhilePausedReportsTheProblemAndSuccessResumes() = runBlocking {
        respond(404, """{"ok":false,"error":{"code":"not_found"}}""")
        controller.enroll(link())
        respond(401, """{"ok":false,"error":{"code":"unauthorized"}}""")
        assertEquals(SendTestResult.Rejected(com.drinkcat.nekopost.core.forwarder.FailureReason.Unauthorized, 401), controller.sendTest())
        assertEquals(PauseReason.Unauthorized, awaitState { it.paused != null }.paused)

        respond(202, """{"ok":true,"id":"m_OK"}""")
        respond(202, """{"ok":true,"id":"m_OK2"}""")
        assertTrue(controller.sendTest() is SendTestResult.Accepted)
        assertNull(awaitState { it.paused == null }.paused)
    }

    @Test
    fun settingsAndStartPoint() = runBlocking {
        respond(404, """{"ok":false,"error":{"code":"not_found"}}""")
        controller.enroll(link())
        env.inbox.rows += InboxSms(10, "+81", "x", 0, 0, 1)

        controller.setSmsEnabled(true)
        controller.setNotificationsEnabled(true)
        controller.setNotificationApps(setOf("jp.co.rakuten.bank", "org.telegram.messenger"))
        val state = awaitState { it.smsEnabled && it.notificationsEnabled && it.notificationApps.isNotEmpty() }
        assertEquals(setOf("jp.co.rakuten.bank"), state.notificationApps)
        // Enabling SMS moves the start point to the current inbox end.
        assertEquals(10L, env.prefs.current().smsLastSeenId)

        controller.reportPermissions(PermissionStatus.All)
        assertEquals(PermissionStatus.All, awaitState { it.permissions == PermissionStatus.All }.permissions)
    }

    @Test
    fun backlogIsExposedAndCanBeSkipped() = runBlocking {
        respond(404, """{"ok":false,"error":{"code":"not_found"}}""")
        controller.enroll(link())
        controller.setSmsEnabled(true)
        awaitState { it.smsEnabled }
        env.clock.advance(Duration.ofHours(1))
        val received = env.clock.millis() - 60_000
        repeat(55) { env.inbox.rows += InboxSms(100L + it, "+8190$it", "积压 $it", received, received, 1) }

        env.graph.reconciler.run()
        assertEquals(55, awaitState { it.smsBacklog != null }.smsBacklog!!.count)

        controller.resolveSmsBacklog(upload = false)
        awaitState { it.smsBacklog == null }
        assertTrue(env.db.dao().all().isEmpty())
    }

    @Test
    fun unenrollWipesEverything() = runBlocking {
        respond(404, """{"ok":false,"error":{"code":"not_found"}}""")
        controller.enroll(link())
        server.close() // keep the test item queued
        controller.sendTest()
        awaitState { it.queue.pending == 1 }

        controller.unenroll()
        val state = awaitState { !it.isEnrolled && it.queue.pending == 0 }
        assertNull(state.lastFailure)
        assertNull(env.graph.secrets.token())
        assertTrue(env.secretPrefs.all.isEmpty())
        assertTrue(env.keys.destroyed > 0)
        assertTrue(env.scheduler.cancelled > 0)
        assertTrue(withTimeout(5_000) { controller.observeLog().first() }.isEmpty())
    }
}
