package com.drinkcat.nekopost.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Time zone used for all displayed times; tests pin it for stable screenshots. */
val LocalDisplayZone = staticCompositionLocalOf<ZoneId> { ZoneId.systemDefault() }

/** Clock for relative times ("3 分钟前"); tests pin it to FakeForwarderData.Now. */
val LocalNow = staticCompositionLocalOf<() -> Instant> { { Instant.now() } }

private val DateTimeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val DayFormat = DateTimeFormatter.ofPattern("M月d日")
private val FullFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@Composable
@ReadOnlyComposable
fun formatDateTime(instant: Instant): String = DateTimeFormat.format(instant.atZone(LocalDisplayZone.current))

@Composable
@ReadOnlyComposable
fun formatTime(instant: Instant): String = TimeFormat.format(instant.atZone(LocalDisplayZone.current))

@Composable
@ReadOnlyComposable
fun formatFullDateTime(instant: Instant): String = FullFormat.format(instant.atZone(LocalDisplayZone.current))

/** "刚刚", "3 分钟前", "2 小时前", then the date and time. */
@Composable
@ReadOnlyComposable
fun formatRelative(instant: Instant): String {
    val seconds = Duration.between(instant, LocalNow.current()).seconds
    return when {
        seconds < 45 -> stringResource(R.string.time_just_now)
        seconds < 3600 -> stringResource(R.string.time_minutes_ago, (seconds / 60).coerceAtLeast(1).toInt())
        seconds < 6 * 3600 -> stringResource(R.string.time_hours_ago, (seconds / 3600).toInt())
        else -> formatDateTime(instant)
    }
}

/** Section label for a day: "今天", "昨天" or "11月14日". */
@Composable
@ReadOnlyComposable
fun formatDay(instant: Instant): String {
    val zone = LocalDisplayZone.current
    val day = instant.atZone(zone).toLocalDate()
    val today = LocalNow.current().atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(day, today)) {
        0L -> stringResource(R.string.time_today)
        1L -> stringResource(R.string.time_yesterday)
        else -> DayFormat.format(day)
    }
}

@Composable
@ReadOnlyComposable
fun formatDuration(d: Duration): String {
    val seconds = d.seconds
    return when {
        seconds < 60 -> stringResource(R.string.duration_seconds, seconds.toInt())
        seconds < 3600 -> stringResource(R.string.duration_minutes, (seconds / 60).toInt())
        else -> stringResource(R.string.duration_hours_minutes, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
    }
}

@Composable
@ReadOnlyComposable
fun failureReasonText(reason: FailureReason): String = stringResource(
    when (reason) {
        FailureReason.Network -> R.string.failure_network
        FailureReason.CertificatePinning -> R.string.failure_pinning
        FailureReason.Unauthorized -> R.string.failure_unauthorized
        FailureReason.SourceDisabled -> R.string.failure_source_disabled
        FailureReason.Rejected -> R.string.failure_rejected
        FailureReason.RateLimited -> R.string.failure_rate_limited
        FailureReason.Server -> R.string.failure_server
    },
)

@Composable
@ReadOnlyComposable
fun kindText(kind: ForwardKind): String = stringResource(
    when (kind) {
        ForwardKind.Sms -> R.string.log_kind_sms
        ForwardKind.Notification -> R.string.log_kind_notification
        ForwardKind.Test -> R.string.log_kind_test
    },
)
