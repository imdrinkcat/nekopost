package com.drinkcat.nekopost.forwarder.sms

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** One PDU's worth of an incoming SMS. */
internal data class SmsPart(
    val address: String,
    val subId: Int,
    val body: String,
    val timestampMillis: Long,
)

/** A complete SMS (multipart segments joined). */
internal data class IncomingSms(
    val address: String,
    val subId: Int,
    val body: String,
    /** SMSC timestamp of the first segment. */
    val timestampMillis: Long,
    val parts: Int = 1,
)

internal object SmsMerger {
    /**
     * Joins the segments of one SMS_RECEIVED broadcast by (originating
     * address, subscription), keeping the order the system delivered them in
     * (already sorted by sequence number), so each sender gets one message.
     */
    fun merge(parts: List<SmsPart>): List<IncomingSms> =
        parts.groupBy { it.address to it.subId }.map { (key, group) ->
            IncomingSms(
                address = key.first,
                subId = key.second,
                body = group.joinToString("") { it.body },
                timestampMillis = group.first().timestampMillis,
                parts = group.size,
            )
        }
}

/** "SIM 1" / "SIM 2" for dual-SIM phones; null on single-SIM phones or when unknown. */
internal fun interface SimLabeler {
    fun label(subId: Int): String?
}

/**
 * Slot-based label only: carrier names need READ_PHONE_STATE, which we do not
 * request (design §8.4 keeps it optional).
 */
internal class AndroidSimLabeler(context: Context) : SimLabeler {
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)

    override fun label(subId: Int): String? {
        if (subId < 0) return null
        val modems = try {
            telephony?.activeModemCount ?: 1
        } catch (e: RuntimeException) {
            1
        }
        if (modems < 2) return null
        val slot = SubscriptionManager.getSlotIndex(subId)
        return if (slot >= 0) "SIM ${slot + 1}" else null
    }
}

/** Heuristic for verification-code SMS (OTP health statistics only; never changes forwarding). */
internal object OtpDetector {
    private val keyword = Regex(
        "验证码|驗證碼|校验码|校驗碼|动态码|動態碼|确认码|確認碼|认证码|認證碼|安全码|安全碼|" +
            "認証|確認コード|ワンタイム|パスコード|暗証|인증|verification|verify|passcode|one[- ]time|\\botp\\b|\\bcode\\b",
        RegexOption.IGNORE_CASE,
    )
    private val digits = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")

    /** WebOTP / SMS Retriever format: last line "@example.com #123456". */
    private val webOtp = Regex("(?m)^@[A-Za-z0-9.-]+ #[A-Za-z0-9]{4,10}\\s*$")

    fun looksLikeCode(body: String): Boolean =
        webOtp.containsMatchIn(body) || (keyword.containsMatchIn(body) && digits.containsMatchIn(body))
}
