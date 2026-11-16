package com.drinkcat.nekopost.forwarder

import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.core.forwarder.OtpVerdict
import com.drinkcat.nekopost.core.rules.RuleAction
import com.drinkcat.nekopost.core.rules.SmsSenderRule
import com.drinkcat.nekopost.forwarder.outbox.toItem
import com.drinkcat.nekopost.forwarder.sms.IncomingSms
import com.drinkcat.nekopost.forwarder.sms.InboxSms
import com.drinkcat.nekopost.forwarder.sms.ReconcileResult
import com.google.crypto.tink.Aead
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException
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
    fun senderRulesSkipAndLogWithoutQueueing() = runBlocking {
        enrollThenWait()
        env.prefs.setSmsSenderRules(
            listOf(SmsSenderRule("+81 80-1111-2222", RuleAction.Forward), SmsSenderRule("+8180*", RuleAction.Skip)),
        )
        inbox("广告短信", Duration.ofMinutes(5), address = "+818099998888")
        inbox("白名单", Duration.ofMinutes(4), address = "+818011112222")
        val last = inbox("其他", Duration.ofMinutes(3))

        assertEquals(ReconcileResult.Done(2, last.id), reconciler.run())
        assertEquals(2, env.db.dao().all().size)
        val log = env.db.dao().latestLog(10).map { it.toItem() }
        val skipped = log.single { it.status == ForwardStatus.Skipped }
        assertEquals(ForwardKind.Sms, skipped.kind)
        assertEquals("+818099998888", skipped.title)

        // Live broadcast path applies the same rules; a skipped SMS is still "seen".
        assertEquals(
            0,
            env.graph.smsIngest.onBroadcast(listOf(IncomingSms("+818077776666", 1, "又一条广告", env.clock.millis())), env.clock.instant()),
        )
        assertEquals(2, env.db.dao().all().size)
        assertEquals(ReconcileResult.Done(0, last.id), reconciler.run())
    }

    @Test
    fun skippedSendersDoNotCountTowardsTheBacklog() = runBlocking {
        enrollThenWait()
        env.prefs.setSmsSenderRules(listOf(SmsSenderRule("SPAM*", RuleAction.Skip)))
        repeat(60) { inbox("广告 $it", Duration.ofMinutes(60L - it), address = "SPAM$it") }
        inbox("正常短信", Duration.ofMinutes(1))
        assertEquals(1, (reconciler.run() as ReconcileResult.Done).queued)
        assertNull(env.prefs.current().smsBacklog)
    }

    @Test
    fun senderRulesSurviveUnenroll() = runBlocking {
        enrollThenWait()
        val rules = listOf(SmsSenderRule("CMBCHINA", RuleAction.Skip))
        env.prefs.setSmsSenderRules(rules)
        env.prefs.clear()
        assertNull(env.prefs.current().enrollment)
        assertEquals(rules, env.prefs.current().smsSenderRules)
    }

    @Test
    fun identicalSmsMinutesApartAreBothForwarded() = runBlocking {
        enrollThenWait()
        val body = "【测试】您的验证码是 123456"
        val first = env.clock.millis() - 300_000
        val second = env.clock.millis()
        // The setup wizard's self-test, repeated a few minutes later: two real SMS.
        assertEquals(1, env.graph.smsIngest.onBroadcast(listOf(IncomingSms("+819012345678", 1, body, first)), env.clock.instant()))
        assertEquals(1, env.graph.smsIngest.onBroadcast(listOf(IncomingSms("+819012345678", 1, body, second)), env.clock.instant()))
        // A system redelivery of the second one is still recognised.
        assertEquals(0, env.graph.smsIngest.onBroadcast(listOf(IncomingSms("+819012345678", 1, body, second)), env.clock.instant()))
        assertEquals(2, env.db.dao().all().size)
        assertEquals(2, env.otpHealth().realtimeCount)

        // Their inbox rows pair up one to one with the two captures: nothing more is queued.
        env.inbox.rows += InboxSms(nextId++, "+819012345678", body, first + 2_000, first, 1)
        env.inbox.rows += InboxSms(nextId++, "+819012345678", body, second + 2_000, second, 1)
        assertEquals(0, (reconciler.run() as ReconcileResult.Done).queued)
        assertEquals(2, env.db.dao().all().size)
    }

    @Test
    fun reconcilerQueuesTheRepeatTheBroadcastMissed() = runBlocking {
        enrollThenWait()
        val body = "余额不足，请及时充值"
        val firstAt = env.clock.millis() - 600_000
        env.graph.smsIngest.onBroadcast(listOf(IncomingSms("10086", 1, body, firstAt)), Instant.ofEpochMilli(firstAt + 1_000))
        // The same alert again ten minutes later, while the app was killed: only in the inbox.
        env.inbox.rows += InboxSms(nextId++, "10086", body, firstAt + 1_000, firstAt, 1)
        val missed = InboxSms(nextId++, "10086", body, env.clock.millis(), env.clock.millis() - 1_000, 1).also { env.inbox.rows += it }

        assertEquals(ReconcileResult.Done(1, missed.id), reconciler.run())
        assertEquals(2, env.db.dao().all().size)
        // A later identical SMS cannot pair with the first capture any more.
        env.clock.advance(Duration.ofMinutes(5))
        val third = InboxSms(nextId++, "10086", body, env.clock.millis(), env.clock.millis() - 1_000, 1).also { env.inbox.rows += it }
        assertEquals(ReconcileResult.Done(1, third.id), reconciler.run())
    }

    @Test
    fun broadcastAfterTheReconcilerIsNotSentTwice() = runBlocking {
        enrollThenWait()
        val row = inbox("【测试】您的验证码是 246810", Duration.ofMinutes(2))
        assertEquals(1, (reconciler.run() as ReconcileResult.Done).queued)
        // The broadcast arrives late (system held it).
        assertEquals(0, env.graph.smsIngest.onBroadcast(listOf(IncomingSms(row.address, row.subId, row.body, row.dateSentMillis)), env.clock.instant()))
        assertEquals(1, env.db.dao().all().size)
    }

    @Test
    fun smsIsNotMarkedSeenWhenQueueingFails() = runBlocking {
        enrollThenWait()
        val body = "【测试】您的验证码是 135790"
        val smsc = env.clock.millis() - 3_000
        env.graph.secrets.deviceSecret()
        // Sealing the payload fails (Keystore hiccup): nothing may be half-written.
        val real = env.keys.current
        env.keys.current = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
                throw GeneralSecurityException("keystore hiccup")

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
                real.decrypt(ciphertext, associatedData)
        }
        try {
            env.graph.smsIngest.onBroadcast(listOf(IncomingSms("+819012345678", 1, body, smsc)), env.clock.instant())
            fail("expected the capture to fail")
        } catch (expected: GeneralSecurityException) {
        }
        env.keys.current = real
        assertTrue(env.db.dao().all().isEmpty())
        assertTrue(env.db.dao().latestLog(5).isEmpty())

        // The reconciler still finds it.
        val row = InboxSms(nextId++, "+819012345678", body, smsc + 3_000, smsc, 1).also { env.inbox.rows += it }
        assertEquals(ReconcileResult.Done(1, row.id), reconciler.run())
    }

    @Test
    fun reusedInboxIdIsStillScanned() = runBlocking {
        enrollThenWait()
        val first = inbox("第一条", Duration.ofMinutes(10))
        assertEquals(ReconcileResult.Done(1, first.id), reconciler.run())
        // The user deletes it; the provider hands the same _id to the next SMS.
        env.inbox.rows.remove(first)
        env.clock.advance(Duration.ofMinutes(5))
        val reused = InboxSms(first.id, "+819012345678", "第二条", env.clock.millis(), env.clock.millis() - 2_000, 1)
        env.inbox.rows += reused
        assertEquals(1, (reconciler.run() as ReconcileResult.Done).queued)
        assertEquals(2, env.db.dao().all().size)
        // And only once.
        assertEquals(0, (reconciler.run() as ReconcileResult.Done).queued)
    }

    @Test
    fun codesMissedAroundARebootAreNotReportedAsSystemDelay() = runBlocking {
        enrollThenWait()
        // Arrived before the first unlock after a reboot: SmsReceiver could not run.
        inbox("【银行】验证码 112233", Duration.ofMinutes(3))
        env.prefs.recordRestart(env.clock.instant().minus(Duration.ofMinutes(1)))
        assertEquals(1, (reconciler.run() as ReconcileResult.Done).queued)
        val health = env.otpHealth()
        assertEquals(0, health.reconciledCount)
        assertEquals(1, health.explainedCount)
        assertEquals(OtpVerdict.NoData, health.verdict)

        // One missed while the app should have been running still counts.
        env.clock.advance(Duration.ofHours(1))
        inbox("【银行】验证码 445566", Duration.ofMinutes(3))
        reconciler.run()
        assertEquals(OtpVerdict.DelayDetected, env.otpHealth().verdict)
    }

    @Test
    fun disabledOrNotEnrolledDoesNothing() = runBlocking {
        inbox("x", Duration.ofMinutes(1))
        assertEquals(ReconcileResult.NotEnabled, reconciler.run())
        env.enroll(smsEnabled = false)
        assertEquals(ReconcileResult.NotEnabled, reconciler.run())
    }
}
