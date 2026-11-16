package com.drinkcat.nekopost.forwarder

import com.drinkcat.nekopost.core.forwarder.NotificationRule
import com.drinkcat.nekopost.forwarder.crypto.IdempotencyKeys
import com.drinkcat.nekopost.forwarder.outbox.OutgoingMessage
import com.drinkcat.nekopost.forwarder.outbox.SmsSeenEntity
import com.drinkcat.nekopost.forwarder.sms.IncomingSms
import com.drinkcat.nekopost.forwarder.sms.OtpDetector
import com.drinkcat.nekopost.forwarder.sms.OtpStats
import com.drinkcat.nekopost.forwarder.sms.SmsMerger
import com.drinkcat.nekopost.forwarder.sms.SmsPart
import com.drinkcat.nekopost.forwarder.store.ForwarderPrefs
import com.drinkcat.nekopost.forwarder.upload.ServerPins
import com.drinkcat.nekopost.forwarder.upload.Uploader
import com.drinkcat.nekopost.core.forwarder.MessagePriority
import com.drinkcat.nekopost.core.forwarder.OtpVerdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.CertificatePinner
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Plain JVM tests: keys, merging, OTP heuristics, payload clipping, pins, backoff. */
class KeysAndPolicyTest {
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val otpBody = "【测试】您的验证码是 123456"

    @Test
    fun smsIdempotencyKeyMatchesReferenceVector() {
        // Reference computed independently with Python's hmac module:
        // hmac.new(bytes(range(1,33)), "2\0+819012345678\0" "1700000000000\0" + body, sha256).hexdigest()[:32]
        val key = IdempotencyKeys.sms(secret, 2, "+819012345678", 1_700_000_000_000L, otpBody)
        assertEquals("sms:a11f1a4e9465ceed942e3be20858f2cd", key)
        // Every component matters.
        assertNotEquals(key, IdempotencyKeys.sms(secret, 1, "+819012345678", 1_700_000_000_000L, otpBody))
        assertNotEquals(key, IdempotencyKeys.sms(secret, 2, "+819012345678", 1_700_000_000_001L, otpBody))
        assertNotEquals(key, IdempotencyKeys.sms(ByteArray(32), 2, "+819012345678", 1_700_000_000_000L, otpBody))
    }

    @Test
    fun fingerprintIsKeyedAndIgnoresAddressFormatting() {
        assertEquals("3bbcb801b9c75c12ac587212052c0b55", IdempotencyKeys.smsFingerprint(secret, "+819012345678", otpBody))
        assertEquals(
            IdempotencyKeys.smsFingerprint(secret, "+81 90-1234-5678", otpBody),
            IdempotencyKeys.smsFingerprint(secret, "+819012345678", otpBody),
        )
        assertNotEquals(
            IdempotencyKeys.smsFingerprint(ByteArray(32), "+819012345678", otpBody),
            IdempotencyKeys.smsFingerprint(secret, "+819012345678", otpBody),
        )
    }

    @Test
    fun randomKeysAreUuids() {
        val a = IdempotencyKeys.random()
        assertTrue(a.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        assertNotEquals(a, IdempotencyKeys.random())
    }

    @Test
    fun mergeJoinsSegmentsPerSenderAndSim() {
        val merged = SmsMerger.merge(
            listOf(
                SmsPart("+819012345678", 1, "前半", 1000),
                SmsPart("CMBCHINA", 1, "单条", 1500),
                SmsPart("+819012345678", 1, "后半", 1001),
                SmsPart("+819012345678", 2, "副卡", 2000),
            ),
        )
        assertEquals(
            listOf(
                IncomingSms("+819012345678", 1, "前半后半", 1000, 2),
                IncomingSms("CMBCHINA", 1, "单条", 1500, 1),
                IncomingSms("+819012345678", 2, "副卡", 2000, 1),
            ),
            merged,
        )
    }

    @Test
    fun otpDetector() {
        assertTrue(OtpDetector.looksLikeCode(otpBody))
        assertTrue(OtpDetector.looksLikeCode("Your verification code is 482913"))
        assertTrue(OtpDetector.looksLikeCode("認証コード：5521"))
        assertTrue(OtpDetector.looksLikeCode("Your code: 123456\n\n@example.com #123456"))
        assertFalse(OtpDetector.looksLikeCode("明天 10 点开会"))
        assertFalse(OtpDetector.looksLikeCode("您的快递已到，取件码见 App"))
        assertFalse(OtpDetector.looksLikeCode("2026 年会员续费提醒"))
    }

    @Test
    fun otpStats() {
        val now = Instant.parse("2026-11-16T01:20:00Z")
        val ms = now.toEpochMilli()
        fun ev(via: String, smscAgo: Long, seenAgo: Long, code: Boolean = true) =
            SmsSeenEntity(fingerprint = "x", smscAt = ms - smscAgo, seenAt = ms - seenAgo, via = via, isCode = code)
        val b = SmsSeenEntity.VIA_BROADCAST
        val r = SmsSeenEntity.VIA_RECONCILER
        assertEquals(OtpVerdict.NoData, OtpStats.compute(emptyList(), now).verdict)

        val healthy = OtpStats.compute(listOf(ev(b, 5_000, 3_000), ev(b, 10_000, 1_000), ev(b, 60_000, 50_000, code = false)), now)
        assertEquals(2, healthy.realtimeCount)
        assertEquals(0, healthy.reconciledCount)
        assertEquals(Duration.ofMillis(5_500), healthy.medianDelay)
        assertEquals(Duration.ofSeconds(9), healthy.maxDelay)
        assertEquals(Instant.ofEpochMilli(ms - 1_000), healthy.lastCodeAt)
        assertEquals(OtpVerdict.NoDelay, healthy.verdict)

        val held = OtpStats.compute(
            listOf(ev(b, 5_000, 3_000), ev(r, Duration.ofMinutes(185).toMillis(), 0), ev(b, Duration.ofDays(8).toMillis(), Duration.ofDays(8).toMillis())),
            now,
        )
        assertEquals(1, held.realtimeCount)
        assertEquals(1, held.reconciledCount)
        assertEquals(Duration.ofMinutes(185), held.maxDelay)
        assertEquals(OtpVerdict.DelayDetected, held.verdict)

        // Missed before boot completed / during an update: not a delay.
        val e = SmsSeenEntity.VIA_RECONCILER_EXPLAINED
        val reboot = OtpStats.compute(listOf(ev(b, 5_000, 3_000), ev(e, Duration.ofMinutes(40).toMillis(), 0)), now)
        assertEquals(1, reboot.realtimeCount)
        assertEquals(0, reboot.reconciledCount)
        assertEquals(1, reboot.explainedCount)
        assertEquals(Duration.ofSeconds(2), reboot.maxDelay)
        assertEquals(OtpVerdict.NoDelay, reboot.verdict)
    }

    @Test
    fun payloadIsClippedToServerLimits() {
        val json = OutgoingMessage(
            type = "sms",
            title = "第一行\n第二行 " + "长".repeat(400),
            body = "😀".repeat(13_000),
            priority = MessagePriority.High,
            occurredAt = Instant.parse("2026-11-16T01:20:30.456Z"),
            meta = mapOf("sim" to "SIM 2", "parts" to 3, "redacted" to true),
        ).toJson(ZoneOffset.ofHours(9))
        val obj = Json.parseToJsonElement(json).jsonObject
        val title = obj["title"]!!.jsonPrimitive.content
        val body = obj["body"]!!.jsonPrimitive.content
        assertFalse(title.contains('\n'))
        assertEquals(256, title.codePointCount(0, title.length))
        assertEquals(12_000, body.codePointCount(0, body.length))
        assertEquals("sms", obj["type"]!!.jsonPrimitive.content)
        assertEquals("high", obj["priority"]!!.jsonPrimitive.content)
        assertEquals("2026-11-16T10:20:30+09:00", obj["occurred_at"]!!.jsonPrimitive.content)
        val meta = obj["meta"] as JsonObject
        assertEquals("SIM 2", meta["sim"]!!.jsonPrimitive.content)
        assertEquals("3", meta["parts"]!!.jsonPrimitive.content)
        assertEquals("true", meta["redacted"]!!.jsonPrimitive.content)
    }

    @Test
    fun pinsAreTheVerifiedIsrgRootsAndExpire() {
        assertEquals(
            listOf(
                "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
                "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
                "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
                "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=",
            ),
            ServerPins.ALL,
        )
        val before = ServerPins.pinnerFor("osaka.drinkcat.com", Instant.parse("2027-11-15T00:00:00Z"))
        assertEquals(4, before.pins.size)
        assertTrue(before.pins.all { it.pattern == "osaka.drinkcat.com" })
        val after = ServerPins.pinnerFor("osaka.drinkcat.com", Instant.parse("2027-11-16T00:00:01Z"))
        assertSame(CertificatePinner.DEFAULT, after)
        // About a year of pinning from this release.
        val days = Duration.between(Instant.parse("2026-11-16T00:00:00Z"), ServerPins.EXPIRES_AT).toDays()
        assertTrue(days in 330..400)
    }

    @Test
    fun backoffDoublesFrom30sUpTo30min() {
        assertEquals(Duration.ofSeconds(30), Uploader.backoff(1))
        assertEquals(Duration.ofSeconds(60), Uploader.backoff(2))
        assertEquals(Duration.ofMinutes(16), Uploader.backoff(6))
        assertEquals(Duration.ofMinutes(30), Uploader.backoff(7))
        assertEquals(Duration.ofMinutes(30), Uploader.backoff(50))
    }

    @Test
    fun notificationRulesRoundTrip() {
        val rules = mapOf(
            "jp.co.rakuten.bank" to NotificationRule(NotificationRule.Mode.IncludeKeywords, listOf("ログイン", "出金"), includeTitle = false, priority = MessagePriority.High),
            "com.sagawa.app" to NotificationRule.Default,
        )
        assertEquals(rules, ForwarderPrefs.decodeRules(ForwarderPrefs.encodeRules(rules)))
        assertEquals(emptyMap<String, NotificationRule>(), ForwarderPrefs.decodeRules("not json"))
        assertNull(ForwarderPrefs.decodeRules(null)["x"])
    }
}
