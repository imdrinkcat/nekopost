package com.drinkcat.nekopost.forwarder

import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.forwarder.crypto.IdempotencyKeys
import com.drinkcat.nekopost.forwarder.outbox.SmsSeenEntity
import com.drinkcat.nekopost.forwarder.outbox.toItem
import com.drinkcat.nekopost.forwarder.sms.IncomingSms
import com.drinkcat.nekopost.forwarder.sms.SmsReceiver
import kotlinx.coroutines.flow.first
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
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.time.Instant

/** GSM 03.40 SMS-DELIVER PDUs → SMS_RECEIVED → merged messages → outbox rows. */
@RunWith(RobolectricTestRunner::class)
class SmsReceiverTest {
    private val env = TestEnv(ApplicationProvider.getApplicationContext())
    private val smsc = Instant.parse("2026-11-16T01:20:30Z")

    @After
    fun tearDown() = env.close()

    @Test
    fun singleGsm7Message() {
        val intent = smsIntent(listOf(Pdu.gsm7("+819012345678", "Your code is 123456")), subId = 1)
        val parsed = SmsReceiver.parse(intent)
        assertEquals(listOf(IncomingSms("+819012345678", 1, "Your code is 123456", smsc.toEpochMilli(), 1)), parsed)
    }

    @Test
    fun multipartUcs2IsMergedInOrder() {
        val first = "【招商银行】您尾号1234的账户于11月16日10:20支出人民币"
        val second = "88.00元，余额1,234.56元。验证码 654321。"
        val intent = smsIntent(
            listOf(
                Pdu.ucs2("+8613800138000", first, concat = Triple(0x2A, 2, 1)),
                Pdu.ucs2("+8613800138000", second, concat = Triple(0x2A, 2, 2)),
            ),
            subId = 2,
        )
        val parsed = SmsReceiver.parse(intent)
        assertEquals(1, parsed.size)
        assertEquals(first + second, parsed[0].body)
        assertEquals(2, parsed[0].parts)
        assertEquals(2, parsed[0].subId)
        assertEquals("+8613800138000", parsed[0].address)
    }

    @Test
    fun legacySubscriptionExtraAndMissingSubscription() {
        val pdu = Pdu.gsm7("+819012345678", "hello")
        val legacy = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .putExtra("pdus", arrayOf<Any>(pdu) as Serializable)
            .putExtra("format", "3gpp")
            .putExtra("subscription", 7)
        assertEquals(7, SmsReceiver.subscriptionId(legacy))
        val none = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        assertEquals(SubscriptionManager.INVALID_SUBSCRIPTION_ID, SmsReceiver.subscriptionId(none))
    }

    @Test
    fun receivedSmsGoesToTheOutboxWithHmacKeyAndSimLabel() = runBlocking {
        env.enroll()
        env.sim[2] = "SIM 2"
        val body = "【测试】您的验证码是 123456"
        val messages = SmsReceiver.parse(smsIntent(listOf(Pdu.ucs2("+819012345678", body)), subId = 2))
        val receivedAt = smsc.plusSeconds(2)

        assertEquals(1, env.graph.smsIngest.onBroadcast(messages, receivedAt))

        val rows = env.db.dao().all()
        assertEquals(1, rows.size)
        val secret = env.graph.secrets.deviceSecret()
        assertEquals(IdempotencyKeys.sms(secret, 2, "+819012345678", smsc.toEpochMilli(), body), rows[0].idemKey)
        assertTrue(rows[0].idemKey.matches(Regex("sms:[0-9a-f]{32}")))

        val json = Json.parseToJsonElement(env.graph.outbox.open(rows[0])).jsonObject
        assertEquals("sms", json["type"]!!.jsonPrimitive.content)
        assertEquals("+819012345678", json["title"]!!.jsonPrimitive.content)
        assertEquals(body, json["body"]!!.jsonPrimitive.content)
        assertEquals("2026-11-16T10:20:30+09:00", json["occurred_at"]!!.jsonPrimitive.content)
        assertEquals("SIM 2", (json["meta"] as JsonObject)["sim"]!!.jsonPrimitive.content)

        val log = env.db.dao().latestLog(10).map { it.toItem() }
        assertEquals(ForwardKind.Sms, log.single().kind)
        assertEquals("+819012345678", log.single().title)
        assertEquals(ForwardStatus.Queued, log.single().status)
        assertTrue(env.scheduler.uploadNow > 0)

        // Counted for OTP health as a real-time delivery, 2 s late.
        val health = env.otpHealth()
        assertEquals(1, health.realtimeCount)
        assertEquals(java.time.Duration.ofSeconds(2), health.medianDelay)

        // The same broadcast again (redelivery) is not queued twice.
        assertEquals(0, env.graph.smsIngest.onBroadcast(messages, receivedAt))
        assertEquals(1, env.db.dao().all().size)
    }

    @Test
    fun singleSimHasNoSimLabel() = runBlocking {
        env.enroll()
        val messages = SmsReceiver.parse(smsIntent(listOf(Pdu.gsm7("+819012345678", "hello there")), subId = 1))
        env.graph.smsIngest.onBroadcast(messages, smsc)
        val json = Json.parseToJsonElement(env.graph.outbox.open(env.db.dao().all().single())).jsonObject
        assertNull(json["meta"])
    }

    @Test
    fun nothingIsCapturedWhenNotEnrolledOrSmsOff() = runBlocking {
        val messages = SmsReceiver.parse(smsIntent(listOf(Pdu.gsm7("+819012345678", "hello")), subId = 1))
        assertEquals(0, env.graph.smsIngest.onBroadcast(messages, smsc))
        env.enroll(smsEnabled = false)
        assertEquals(0, env.graph.smsIngest.onBroadcast(messages, smsc))
        assertTrue(env.db.dao().all().isEmpty())
    }

    @Test
    fun otherActionsAreIgnored() {
        ForwarderGraph.install(env.graph)
        try {
            val fake = Intent("com.example.FAKE_SMS")
                .putExtra("pdus", arrayOf<Any>(Pdu.gsm7("+819012345678", "spoof")) as Serializable)
                .putExtra("format", "3gpp")
            SmsReceiver().onReceive(env.context, fake)
            runBlocking { assertTrue(env.db.dao().all().isEmpty()) }
        } finally {
            ForwarderGraph.install(null)
        }
    }

    private fun smsIntent(pdus: List<ByteArray>, subId: Int): Intent =
        Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .putExtra("pdus", pdus.toTypedArray<Any>() as Serializable)
            .putExtra("format", "3gpp")
            .putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId)

    /** Minimal SMS-DELIVER PDU encoder (no SMSC address, SCTS 2026-11-16 10:20:30 +09:00). */
    private object Pdu {
        private val scts = byteArrayOf(0x62, 0x11, 0x61, 0x01, 0x02, 0x03, 0x63)

        fun gsm7(from: String, text: String): ByteArray {
            require(text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " .,:" }) { "keep to the shared GSM/ASCII subset" }
            return header(from, udhi = false, dcs = 0x00) + byteArrayOf(text.length.toByte()) + pack7(text)
        }

        /** UCS-2 message, optionally one segment of a concatenated SMS (ref, total, seq). */
        fun ucs2(from: String, text: String, concat: Triple<Int, Int, Int>? = null): ByteArray {
            val data = text.toByteArray(Charsets.UTF_16BE)
            val udh = concat?.let { (ref, total, seq) -> byteArrayOf(0x05, 0x00, 0x03, ref.toByte(), total.toByte(), seq.toByte()) }
                ?: ByteArray(0)
            val ud = udh + data
            return header(from, udhi = concat != null, dcs = 0x08) + byteArrayOf(ud.size.toByte()) + ud
        }

        private fun header(from: String, udhi: Boolean, dcs: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x00) // no SMSC address
            out.write(if (udhi) 0x44 else 0x04) // SMS-DELIVER, no more messages, UDHI
            val digits = from.removePrefix("+")
            out.write(digits.length)
            out.write(if (from.startsWith("+")) 0x91 else 0x81)
            out.write(semiOctets(digits))
            out.write(0x00) // PID
            out.write(dcs)
            out.write(scts)
            return out.toByteArray()
        }

        private fun semiOctets(digits: String): ByteArray {
            val padded = if (digits.length % 2 == 1) digits + "F" else digits
            return ByteArray(padded.length / 2) { i ->
                val lo = padded[2 * i].digitToInt(16)
                val hi = padded[2 * i + 1].digitToInt(16)
                ((hi shl 4) or lo).toByte()
            }
        }

        private fun pack7(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            var acc = 0
            var bits = 0
            for (c in text) {
                acc = acc or ((c.code and 0x7F) shl bits)
                bits += 7
                while (bits >= 8) {
                    out.write(acc and 0xFF)
                    acc = acc ushr 8
                    bits -= 8
                }
            }
            if (bits > 0) out.write(acc and 0xFF)
            return out.toByteArray()
        }
    }
}
