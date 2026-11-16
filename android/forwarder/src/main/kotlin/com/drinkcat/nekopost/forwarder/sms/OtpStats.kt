package com.drinkcat.nekopost.forwarder.sms

import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.forwarder.outbox.SmsSeenEntity
import java.time.Duration
import java.time.Instant

/**
 * OTP health (design §8.4, "OTP 短信是否被系统延迟？"): verification-code SMS
 * of the last [WINDOW_DAYS] days, split by how we first saw them. Delay =
 * first seen − SMSC timestamp; the median covers broadcast deliveries only
 * (the inbox scan adds its own latency), the maximum covers both.
 */
internal object OtpStats {
    const val WINDOW_DAYS = 7

    fun compute(events: List<SmsSeenEntity>, now: Instant): OtpHealth {
        val since = now.minus(Duration.ofDays(WINDOW_DAYS.toLong())).toEpochMilli()
        val codes = events.filter { it.isCode && it.seenAt >= since }
        if (codes.isEmpty()) return OtpHealth(windowDays = WINDOW_DAYS)
        val realtime = codes.filter { it.via == SmsSeenEntity.VIA_BROADCAST }
        val realtimeDelays = realtime.mapNotNull(::delayMillis).sorted()
        val allDelays = codes.mapNotNull(::delayMillis)
        return OtpHealth(
            windowDays = WINDOW_DAYS,
            realtimeCount = realtime.size,
            reconciledCount = codes.size - realtime.size,
            medianDelay = median(realtimeDelays)?.let(Duration::ofMillis),
            maxDelay = allDelays.maxOrNull()?.let(Duration::ofMillis),
            lastCodeAt = Instant.ofEpochMilli(codes.maxOf { it.seenAt }),
        )
    }

    private fun delayMillis(e: SmsSeenEntity): Long? =
        if (e.smscAt <= 0) null else (e.seenAt - e.smscAt).coerceAtLeast(0)

    private fun median(sorted: List<Long>): Long? = when {
        sorted.isEmpty() -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
