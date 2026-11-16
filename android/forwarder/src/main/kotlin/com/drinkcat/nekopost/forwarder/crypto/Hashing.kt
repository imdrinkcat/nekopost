package com.drinkcat.nekopost.forwarder.crypto

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object Hashing {
    private const val HEX = "0123456789abcdef"

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    fun sha256Hex(text: String): String = hex(sha256(text.toByteArray(Charsets.UTF_8)))
}

/**
 * Idempotency keys and local fingerprints (CONTRACT.md §1). Parts are joined
 * with U+0000 so no two inputs share an encoding. Never an unkeyed hash of
 * content: a leaked database must not allow brute-forcing 6-digit codes.
 */
internal object IdempotencyKeys {
    /** `"sms:" + hex(HMAC-SHA256(device_secret, subId‖addr‖tsMillis‖body))[:32]`. */
    fun sms(deviceSecret: ByteArray, subId: Int, address: String, timestampMillis: Long, body: String): String =
        "sms:" + Hashing.hex(
            Hashing.hmacSha256(deviceSecret, join(subId.toString(), address, timestampMillis.toString(), body)),
        ).take(32)

    /** Notification and test messages: a random UUID, stored with the outbox row and reused on retries. */
    fun random(): String = UUID.randomUUID().toString()

    /**
     * Keyed fingerprint of sender + body, used to recognize an SMS seen by the
     * broadcast when the inbox scan finds it again (timestamps and subscription
     * IDs can differ slightly between the two paths).
     */
    fun smsFingerprint(deviceSecret: ByteArray, address: String, body: String): String =
        Hashing.hex(Hashing.hmacSha256(deviceSecret, join("fp", normalizeAddress(address), body))).take(32)

    fun normalizeAddress(address: String): String = address.filter { it.isLetterOrDigit() }.lowercase()

    private fun join(vararg parts: String): ByteArray = parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)
}
