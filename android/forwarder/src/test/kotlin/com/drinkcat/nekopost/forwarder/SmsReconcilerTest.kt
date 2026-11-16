package com.drinkcat.nekopost.forwarder

import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.forwarder.sms.IncomingSms
import com.drinkcat.nekopost.forwarder.sms.InboxSms
import com.drinkcat.nekopost.forwarder.sms.ReconcileResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SmsReconcilerTest {
    private val env = TestEnv(ApplicationProvider.getApplicationContext())
    private val reconciler get() = env.graph.reconciler
    private var nextId = 1L

    @After
    fun tearDown() = env.close()

    private fun inbox(body: String, receivedAgo: Duration, address: String = "+819012345678", subId: Int = 1): InboxSms {
        val received = env.clock.instant().minus(receivedAgo).toEpochMilli()
        return InboxSms(nextId++, address, body, received, received - 3_000, subId).also { env.inbox.rows += it }
    }

    /** Enroll, then let time pass so new inbox rows fall after enabled_at. */
    private suspend fun enrollThenWait() {
        env.enroll()
        env.clock.advance(Duration.ofHours(2))
    }

    @Test
    fun neverUploadsHistory() = runBlocking {
        // Years of SMS already on the phone.
        repeat(300) { inbox("旧短信 $it", Duration.ofDays(400L - it)) }
        inbox("昨天的验证码 123456", Duration.ofHours(20))
        env.enroll()

        assertEquals(ReconcileResult.Done(0, 301), reconciler.run())
        assertTrue(env.db.dao().all().isEmpty())
        assertEquals(301L, env.prefs.current().smsLastSeenId)
    }

    @Test
    fun firstScanWithoutStartPointOnlyRemembersTheInboxEnd() = runBlocking {
        repeat(5) { inbox("旧短信 $it", Duration.ofHours(1)) }
        // Enrolled while READ_SMS was missing: no start point yet.
        env.inbox.readable = false
        env.enroll()
        assertNull(env.prefs.current().smsLastSeenId)
        assertEquals(ReconcileResult.NoPermission, reconciler.run())

        env.inbox.readable = true
        assertEquals(ReconcileResult.StartPointSet(5), reconciler.run())
        assertTrue(env.db.dao().all().isEmpty())
        assertEquals(ReconcileResult.Done(0, 5), reconciler.run())
    }

    @Test
    fun uploadsMissedSmsWithinTheLookback() = runBlocking {
        inbox("旧短信", Duration.ofDays(30))
        env.enroll()
        env.clock.advance(Duration.ofHours(1))
        val missed = inbox("【测试】您的验证码是 123456", Duration.ofMinutes(10), subId = 2)
        env.sim[2] = "SIM 2"

        assertEquals(ReconcileResult.Done(1, missed.id), reconciler.run())
        val row = env.db.dao().all().single()
        val json = Json.parseToJsonElement(env.graph.outbox.open(row)).jsonObject
        assertEquals("+819012345678", json["title"]!!.jsonPrimitive.content)
        assertEquals(missed.body, json["body"]!!.jsonPrimitive.content)
        val meta = json["meta"] as JsonObject
        assertEquals("reconciler", meta["via"]!!.jsonPrimitive.content)
        assertEquals("SIM 2", meta["sim"]!!.jsonPrimitive.content)
        assertTrue(env.scheduler.uploadNow > 0)

        // Found only by the scan → OTP health reports a delay.
        val health = env.otpHealth()
        assertEquals(0, health.realtimeCount)
        assertEquals(1, health.reconciledCount)

        // A second scan finds nothing new.
        assertEquals(ReconcileResult.Done(0, missed.id), reconciler.run())
        assertEquals(1, env.db.dao().all().size)
    }

    @Test
    fun lookbackIsCappedAt72HoursAndEnabledAt() = runBlocking {
        env.enroll()
        val enabledAt = env.clock.instant()
        env.clock.advance(Duration.ofDays(5))
        // Arrived while the app could not see them (e.g. restored inbox), but before the window.
        val tooOld = inbox("四天前", Duration.ofDays(4))
        val inWindow = inbox("两天前", Duration.ofDays(2))
        val result = reconciler.run()
        assertEquals(ReconcileResult.Done(1, inWindow.id), result)
        assertEquals(inWindow.id, env.prefs.current().smsLastSeenId)
        assertTrue(tooOld.dateMillis < enabledAt.plus(Duration.ofDays(5)).minus(Duration.ofHours(72)).toEpochMilli())

        // Re-enabling SMS moves enabled_at forward: nothing before it is ever sent.
        env.graph.prefs.setSmsEnabled(true, env.inbox.maxId(), env.clock.instant())
        inbox("刚才（启用前）", Duration.ofMinutes(5))
        assertEquals(0, (reconciler.run() as ReconcileResult.Done).queued)
    }

    @Test
    fun smsAlreadyReceivedLiveIsNotSentAgain() = runBlocking {
        enrollThenWait()
        val body = "【测试】您的验证码是 777777"
        val row = inbox(body, Duration.ofSeconds(30))
        // The broadcast delivered it first (same sender, SMSC time, body).
        env.graph.smsIngest.onBroadcast(
            listOf(IncomingSms(row.address, row.subId, body, row.dateSentMillis)),
            Instant.ofEpochMilli(row.dateMillis),
        )
        assertEquals(1, env.db.dao().all().size)

        assertEquals(ReconcileResult.Done(0, row.id), reconciler.run())
        assertEquals(1, env.db.dao().all().size)
        val health = env.otpHealth()
        assertEquals(1, health.realtimeCount)
        assertEquals(0, health.reconciledCount)
    }

    @Test
    fun moreThan50NeedsConfirmation() = runBlocking {
        enrollThenWait()
        val start = env.prefs.current().smsLastSeenId
        repeat(51) { inbox("积压短信 $it", Duration.ofMinutes(60L - it)) }

        assertEquals(ReconcileResult.NeedsConfirmation(51), reconciler.run())
        assertTrue(env.db.dao().all().isEmpty())
        assertEquals(start, env.prefs.current().smsLastSeenId)
        val backlog = env.prefs.current().smsBacklog!!
        assertEquals(51, backlog.count)

        // Exactly 50 would have been fine; confirming uploads all 51.
        val done = reconciler.run(confirmBacklog = true) as ReconcileResult.Done
        assertEquals(51, done.queued)
        assertEquals(51, env.db.dao().all().size)
        assertNull(env.prefs.current().smsBacklog)
    }

    @Test
    fun skippingTheBacklogUploadsNothing() = runBlocking {
        enrollThenWait()
        repeat(60) { inbox("积压短信 $it", Duration.ofMinutes(60L - it)) }
        assertEquals(ReconcileResult.NeedsConfirmation(60), reconciler.run())

        reconciler.skipBacklog()
        assertNull(env.prefs.current().smsBacklog)
        assertEquals(60L, env.prefs.current().smsLastSeenId)
        assertEquals(ReconcileResult.Done(0, 60), reconciler.run())
        assertTrue(env.db.dao().all().isEmpty())
    }

    @Test
    fun exactly50IsUploadedWithoutAsking() = runBlocking {
        enrollThenWait()
        repeat(50) { inbox("短信 $it", Duration.ofMinutes(60L - it)) }
        assertEquals(50, (reconciler.run() as ReconcileResult.Done).queued)
    }

    @Test
    fun disabledOrNotEnrolledDoesNothing() = runBlocking {
        inbox("x", Duration.ofMinutes(1))
        assertEquals(ReconcileResult.NotEnabled, reconciler.run())
        env.enroll(smsEnabled = false)
        assertEquals(ReconcileResult.NotEnabled, reconciler.run())
    }
}
