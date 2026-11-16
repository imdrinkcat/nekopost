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

/** Time zone used for all displayed times; tests pin it for stable screenshots. */
val LocalDisplayZone = staticCompositionLocalOf<ZoneId> { ZoneId.systemDefault() }

private val DateTimeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

@Composable
@ReadOnlyComposable
fun formatDateTime(instant: Instant): String = DateTimeFormat.format(instant.atZone(LocalDisplayZone.current))

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
