package com.drinkcat.nekopost.forwarder

import android.app.Notification
import android.app.Person
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.core.forwarder.MessagePriority
import com.drinkcat.nekopost.core.forwarder.NotificationRule
import com.drinkcat.nekopost.forwarder.notify.CapturedNotification
import com.drinkcat.nekopost.forwarder.notify.Extraction
import com.drinkcat.nekopost.forwarder.notify.NotificationDecision
import com.drinkcat.nekopost.forwarder.notify.NotificationExtractor
import com.drinkcat.nekopost.forwarder.notify.NotificationIngest
import com.drinkcat.nekopost.forwarder.notify.RedactionPlaceholders
import com.drinkcat.nekopost.forwarder.outbox.toItem
import com.drinkcat.nekopost.forwarder.sms.IncomingSms
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

@RunWith(RobolectricTestRunner::class)
class NotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val env = TestEnv(context)
    private val placeholders = RedactionPlaceholders(RedactionPlaceholders.BUILT_IN)
    private val bank = "jp.co.rakuten.bank"

    @After
    fun tearDown() = env.close()

    private fun builder() = Notification.Builder(context, "alerts").setSmallIcon(android.R.drawable.ic_dialog_info)

    private fun extract(n: Notification, pkg: String = bank): Extraction =
        NotificationExtractor.extract(pkg, "0|$pkg|1|null|10001", 1_763_255_000_000L, n, placeholders)

    private fun captured(n: Notification): CapturedNotification = (extract(n) as Extraction.Captured).notification

    // --- extraction ----------------------------------------------------------

    @Test
    fun readsTitleAndText() {
        val c = captured(builder().setContentTitle("ログイン通知").setContentText("新しい端末からログインがありました").build())
        assertEquals("ログイン通知", c.title)
        assertEquals("新しい端末からログインがありました", c.text)
        assertEquals("alerts", c.channelId)
        assertEquals(false, c.redacted)
    }

    @Test
    fun prefersBigText() {
        val n = builder().setContentTitle("出金").setContentText("短い")
            .setStyle(Notification.BigTextStyle().bigText("出金がありました：10,000円（全文）"))
            .build()
        assertEquals("出金がありました：10,000円（全文）", captured(n).text)
    }

    @Test
    fun messagingStyleTakesOnlyTheNewestMessage() {
        val me = Person.Builder().setName("我").build()
        val style = Notification.MessagingStyle(me)
            .setConversationTitle("家族群")
            .setGroupConversation(true)
            .addMessage(Notification.MessagingStyle.Message("第一条", 1_000L, Person.Builder().setName("妈妈").build()))
            .addMessage(Notification.MessagingStyle.Message("最新一条", 2_000L, Person.Builder().setName("爸爸").build()))
        val c = captured(builder().setStyle(style).build())
        assertEquals("家族群", c.title)
        assertEquals("爸爸: 最新一条", c.text)
    }

    @Test
    fun skipsNoise() {
        assertEquals(Extraction.Ignored("ongoing"), extract(builder().setContentTitle("t").setOngoing(true).build()))
        val fgs = builder().setContentTitle("t").build().apply { flags = flags or Notification.FLAG_FOREGROUND_SERVICE }
        assertEquals(Extraction.Ignored("foreground_service"), extract(fgs))
        assertEquals(
            Extraction.Ignored("group_summary"),
            extract(builder().setContentTitle("t").setGroup("g").setGroupSummary(true).build()),
        )
        for (category in listOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_CALL, Notification.CATEGORY_SERVICE)) {
            assertEquals(Extraction.Ignored("category"), extract(builder().setContentTitle("t").setCategory(category).build()))
        }
        assertEquals(Extraction.Ignored("media"), extract(builder().setContentTitle("t").setStyle(Notification.MediaStyle()).build()))
        assertEquals(Extraction.Ignored("progress"), extract(builder().setContentTitle("下载中").setProgress(100, 30, false).build()))
    }

    @Test
    fun detectsSystemRedaction() {
        val c = captured(builder().setContentTitle("SMS").setContentText("Sensitive notification content hidden").build())
        assertTrue(c.redacted)
        assertNull(c.text)
        assertEquals("SMS", c.title)
        assertTrue(captured(builder().setContentTitle("短信").setContentText("敏感通知内容已隐藏").build()).redacted)
        // No title and no text at all: the system stripped the extras.
        assertTrue(captured(builder().build()).redacted)
    }

    // --- ingest --------------------------------------------------------------

    private fun notif(text: String?, title: String? = "ログイン通知", key: String = "k1", pkg: String = bank, redacted: Boolean = false) =
        CapturedNotification(pkg, key, env.clock.millis(), "alerts", title, text, redacted)

    private suspend fun selectApps(vararg pkgs: String) {
        env.enroll()
        env.graph.prefs.setNotificationsEnabled(true)
        env.graph.prefs.setNotificationApps(pkgs.toSet())
    }

    private suspend fun queuedJson(index: Int = 0): JsonObject {
        val row = env.db.dao().all()[index]
        return Json.parseToJsonElement(env.graph.outbox.open(row)).jsonObject
    }

    @Test
    fun onlySelectedAppsAreForwarded() = runBlocking {
        val ingest = env.graph.notifications
        assertEquals(NotificationDecision.NotSelected, ingest.onPosted(notif("x")))
        selectApps(bank, "org.telegram.messenger", context.packageName)
        // Telegram and ourselves can never be selected, even if the store says so.
        assertEquals(NotificationDecision.NotSelected, ingest.onPosted(notif("x", pkg = "org.telegram.messenger")))
        assertEquals(NotificationDecision.NotSelected, ingest.onPosted(notif("x", pkg = context.packageName)))
        assertEquals(NotificationDecision.NotSelected, ingest.onPosted(notif("x", pkg = "com.other.app")))

        assertTrue(ingest.onPosted(notif("新しい端末からログインがありました")) is NotificationDecision.Queued)
        val json = queuedJson()
        assertEquals("notification", json["type"]!!.jsonPrimitive.content)
        assertEquals("楽天銀行 · ログイン通知", json["title"]!!.jsonPrimitive.content)
        assertEquals("新しい端末からログインがありました", json["body"]!!.jsonPrimitive.content)
        val meta = json["meta"] as JsonObject
        assertEquals(bank, meta["pkg"]!!.jsonPrimitive.content)
        assertEquals("alerts", meta["channel"]!!.jsonPrimitive.content)
        // Notification idempotency keys are random UUIDs.
        assertTrue(env.db.dao().all().single().idemKey.matches(Regex("[0-9a-f-]{36}")))
        val log = env.db.dao().latestLog(5).single().toItem()
        assertEquals("楽天銀行", log.title)
        assertEquals(bank, log.sourcePackage)
    }

    @Test
    fun dedupeWithinTenMinutes() = runBlocking {
        selectApps(bank)
        val ingest = env.graph.notifications
        assertTrue(ingest.onPosted(notif("同じ内容")) is NotificationDecision.Queued)
        assertEquals(NotificationDecision.Duplicate, ingest.onPosted(notif("同じ内容")))
        // Same key, new content → forwarded.
        assertTrue(ingest.onPosted(notif("別の内容")) is NotificationDecision.Queued)
        env.clock.advance(Duration.ofMinutes(10))
        assertTrue(ingest.onPosted(notif("同じ内容")) is NotificationDecision.Queued)
        assertEquals(3, env.db.dao().all().size)
    }

    @Test
    fun rateLimitCoalesces() = runBlocking {
        selectApps(bank)
        val ingest = env.graph.notifications
        repeat(30) { assertTrue(ingest.onPosted(notif("通知 $it", key = "k$it")) is NotificationDecision.Queued) }
        val first = ingest.onPosted(notif("通知 30", key = "k30")) as NotificationDecision.Coalesced
        assertTrue(first.first)
        repeat(4) { ingest.onPosted(notif("通知 ${31 + it}", key = "k${31 + it}")) }
        assertEquals(30, env.db.dao().all().size)

        assertEquals(0, ingest.flushDue()) // window still open
        env.clock.advance(Duration.ofSeconds(61))
        assertEquals(1, ingest.flushDue())
        val summary = queuedJson(30)
        assertEquals("楽天銀行 · 5 条通知", summary["title"]!!.jsonPrimitive.content)
        assertEquals("5", (summary["meta"] as JsonObject)["coalesced"]!!.jsonPrimitive.content)
        assertEquals("low", summary["priority"]!!.jsonPrimitive.content)
        // New window: forwarding resumes.
        assertTrue(ingest.onPosted(notif("通知 next", key = "k-next")) is NotificationDecision.Queued)
    }

    @Test
    fun redactedContentBecomesAPlaceholder() = runBlocking {
        selectApps(bank)
        assertTrue(env.graph.notifications.onPosted(notif(null, title = "SMS", redacted = true)) is NotificationDecision.Queued)
        val json = queuedJson()
        assertEquals("系统隐藏了验证码内容", json["body"]!!.jsonPrimitive.content)
        assertEquals("true", (json["meta"] as JsonObject)["redacted"]!!.jsonPrimitive.content)
    }

    @Test
    fun perAppRules() = runBlocking {
        selectApps(bank)
        env.graph.prefs.setNotificationRule(
            bank,
            NotificationRule(NotificationRule.Mode.IncludeKeywords, listOf("出金"), includeTitle = false, priority = MessagePriority.High),
        )
        val ingest = env.graph.notifications
        assertEquals(NotificationDecision.Skipped("rule"), ingest.onPosted(notif("ポイントが貯まりました", key = "a")))
        assertTrue(ingest.onPosted(notif("出金がありました", key = "b")) is NotificationDecision.Queued)
        val json = queuedJson()
        assertEquals("楽天銀行", json["title"]!!.jsonPrimitive.content)
        assertEquals("high", json["priority"]!!.jsonPrimitive.content)
        val statuses = env.db.dao().latestLog(10).map { it.toItem().status }
        assertEquals(listOf(ForwardStatus.Queued, ForwardStatus.Skipped), statuses)
    }

    @Test
    fun smsAppNotificationOfAForwardedSmsIsSkipped() = runBlocking {
        selectApps(bank)
        env.graph.smsIngest.onBroadcast(listOf(IncomingSms("+819012345678", 1, "【测试】您的验证码是 123456", env.clock.millis())), env.clock.instant())
        assertEquals(
            NotificationDecision.Skipped("sms_duplicate"),
            env.graph.notifications.onPosted(notif("【测试】您的验证码是 123456", key = "sms")),
        )
        assertEquals(NotificationIngest.RATE_LIMIT, 30)
    }
}
