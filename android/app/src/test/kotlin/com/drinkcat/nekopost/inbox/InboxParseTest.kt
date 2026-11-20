package com.drinkcat.nekopost.inbox

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Server inbox items (org.json needs Robolectric). minSdk 30: Instant.parse rejects "+09:00". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class InboxParseTest {
    private val now = Instant.parse("2026-11-16T00:00:00Z")

    private fun item(extra: String = "") = JSONObject(
        """{"id":"m_01JB7QAB12CD34EF56GH78JK9M","seq":42,"type":"sms","priority":"high",
           "title":"******","preview":"验证码******","sender":"95555","has_code":true,"read":false,
           "source":{"id":"fold8","name":"Fold8 主力机","icon":"📱"},"category_text":"验证码",
           "received_at":"2026-11-16T09:41:00+09:00","body_expires_at":"2026-11-23T09:41:00+09:00",
           "body_state":"stored","app_only":true,
           "deliveries":[{"channel":"telegram","state":"skipped","aux":false},{"channel":"fcm","state":"delivered","aux":false}]
           $extra}""",
    )

    @Test
    fun offsetTimestampsParse() {
        val m = InboxMessage.parse(item(), now)
        assertEquals(Instant.parse("2026-11-16T00:41:00Z"), m.receivedAt)
        assertEquals(Instant.parse("2026-11-23T00:41:00Z"), m.expiresAt)
        assertFalse(m.expired)
        assertEquals("95555", m.displaySender)
        assertEquals(MessageKind.Sms, m.kind)
        assertEquals(MessagePriority.High, m.priority)
        assertTrue(m.appOnly)
        assertEquals("📱", m.sourceIcon)
        assertEquals(2, m.deliveries.size)
        assertTrue(m.deliveries[1].delivered)
    }

    @Test
    fun zuluTimestampsParseToo() {
        assertEquals(Instant.parse("2026-11-16T00:41:00Z"), InboxMessage.parseTimestamp("2026-11-16T00:41:00Z"))
        assertEquals(Instant.parse("2026-11-16T00:41:00.123Z"), InboxMessage.parseTimestamp("2026-11-16T09:41:00.123+09:00"))
        assertNull(InboxMessage.parseTimestamp("yesterday"))
    }

    @Test
    fun expiredOrShreddedBodyHidesContent() {
        val past = InboxMessage.parse(item(), Instant.parse("2027-01-01T00:00:00Z"))
        assertTrue(past.expired)
        assertEquals("", past.sender)
        assertEquals("", past.preview)
        assertEquals("Fold8 主力机", past.displaySender)
        val shredded = InboxMessage.parse(JSONObject(item().toString()).put("body_state", "shredded"), now)
        assertTrue(shredded.expired)
        val missing = InboxMessage.parse(JSONObject(item().toString()).apply { remove("body_expires_at") }, now)
        assertTrue(missing.expired)
    }

    @Test
    fun bodyKeptForeverStaysLiveWithoutADate() {
        val forever = InboxMessage.parse(
            JSONObject(item().toString()).put("body_expires_at", "9999-01-01T09:00:00+09:00"),
            Instant.parse("2027-01-01T00:00:00Z"),
        )
        assertFalse(forever.expired)
        assertTrue(forever.keptForever)
        val dated = InboxMessage.parse(item(), Instant.parse("2026-11-16T10:00:00Z"))
        assertFalse(dated.keptForever)
    }

    @Test
    fun olderServersWithoutSenderFallBack() {
        val old = JSONObject(item().toString()).apply {
            remove("sender")
            remove("app_only")
            put("title", "CI · build #42 passed")
        }
        val m = InboxMessage.parse(old, now)
        assertEquals("CI · build #42 passed", m.displaySender)
        assertFalse(m.appOnly)
        val bare = JSONObject(old.toString()).apply { put("title", "") }
        assertEquals("Fold8 主力机", InboxMessage.parse(bare, now).displaySender)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedIds() {
        InboxMessage.parse(JSONObject(item().toString()).put("id", "../etc"), now)
    }

    @Test
    fun pastedPairingInput() {
        val link = PairingInput.parse("nekopost://enroll?v=1&kind=device&url=https%3A%2F%2Fosaka.example.com%2F&code=Q2F0c0FyZUxpcXVpZF9ub3", "")
        assertEquals("osaka.example.com", link.server.host)
        assertTrue(PairingInput.isDeviceLink("  nekopost://enroll?v=1&kind=device&url=https%3A%2F%2Fosaka.example.com%2F&code=Q2F0c0FyZUxpcXVpZF9ub3 "))
        assertFalse(PairingInput.isDeviceLink("nekopost://enroll?v=1&kind=source&url=https%3A%2F%2Fosaka.example.com&id=x&token=y&name=z"))
        val short = PairingInput.parse("abcd-efgh-jk", "https://osaka.example.com/")
        assertEquals("ABCDEFGHJK", short.code)
    }
}
