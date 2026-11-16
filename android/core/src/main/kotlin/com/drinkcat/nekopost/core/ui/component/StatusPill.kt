package com.drinkcat.nekopost.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.core.R
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone

/**
 * A status chip: icon + text + colour, never colour alone (design §9.7).
 * [large] is used on the tabletop display half and status heroes.
 */
@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = defaultIcon(tone),
    large: Boolean = false,
) {
    val colors = NekopostTheme.colors.tone(tone)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (large) 16.dp else 10.dp,
                vertical = if (large) 8.dp else 4.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (large) 8.dp else 6.dp),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(if (large) 20.dp else 16.dp))
            }
            Text(
                text = text,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/** Status pill for one forwarded item. */
@Composable
fun ForwardStatusPill(status: ForwardStatus, modifier: Modifier = Modifier, large: Boolean = false) {
    StatusPill(
        text = stringResource(status.labelRes()),
        tone = status.tone(),
        modifier = modifier,
        icon = status.icon(),
        large = large,
    )
}

fun ForwardStatus.tone(): StatusTone = when (this) {
    ForwardStatus.Queued, ForwardStatus.Sending -> StatusTone.Pending
    ForwardStatus.Retrying, ForwardStatus.Paused -> StatusTone.Warning
    ForwardStatus.Accepted -> StatusTone.Success
    ForwardStatus.Duplicate, ForwardStatus.Skipped -> StatusTone.Neutral
    ForwardStatus.Failed -> StatusTone.Error
}

fun ForwardStatus.labelRes(): Int = when (this) {
    ForwardStatus.Queued -> R.string.neko_status_queued
    ForwardStatus.Sending -> R.string.neko_status_sending
    ForwardStatus.Retrying -> R.string.neko_status_retrying
    ForwardStatus.Accepted -> R.string.neko_status_accepted
    ForwardStatus.Duplicate -> R.string.neko_status_duplicate
    ForwardStatus.Failed -> R.string.neko_status_failed
    ForwardStatus.Paused -> R.string.neko_status_paused
    ForwardStatus.Skipped -> R.string.neko_status_skipped
}

private fun ForwardStatus.icon(): ImageVector = when (this) {
    ForwardStatus.Queued, ForwardStatus.Sending -> Icons.Filled.Email
    ForwardStatus.Retrying, ForwardStatus.Paused -> Icons.Filled.Refresh
    ForwardStatus.Accepted -> Icons.Filled.CheckCircle
    ForwardStatus.Duplicate, ForwardStatus.Skipped -> Icons.Filled.Info
    ForwardStatus.Failed -> Icons.Filled.Close
}

private fun defaultIcon(tone: StatusTone): ImageVector? = when (tone) {
    StatusTone.Success -> Icons.Filled.CheckCircle
    StatusTone.Pending -> Icons.Filled.Email
    StatusTone.Warning -> Icons.Filled.Warning
    StatusTone.Uncertain -> Icons.Filled.Info
    StatusTone.Error -> Icons.Filled.Warning
    StatusTone.Neutral -> Icons.Filled.Info
    StatusTone.Brand -> null
}
