package com.drinkcat.nekopost.ui.forward

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.ForwardLogItem
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.health
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.component.SegmentRing
import com.drinkcat.nekopost.core.ui.component.SleepingCat
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.component.labelRes
import com.drinkcat.nekopost.core.ui.component.tone
import com.drinkcat.nekopost.core.ui.motion.LocalReducedMotion
import com.drinkcat.nekopost.core.ui.motion.NekoMotion
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.formatRelative
import com.drinkcat.nekopost.ui.kindText
import com.drinkcat.nekopost.ui.log.KindBadge
import com.drinkcat.nekopost.ui.shell.ShellUiState

/**
 * Upper half in tabletop: the live forwarding monitor (design §10.4 ④). Read
 * only — nothing here is clickable, so the half above the hinge is purely for
 * looking. Left: the health ring with its legend. Right: the conveyor of
 * recently forwarded items, sender and category only (never any content),
 * newest nearest the hinge, where the "mail slot" is.
 */
@Composable
fun ForwardingMonitor(state: ShellUiState, modifier: Modifier = Modifier) {
    val f = state.forwarder
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = NekoTokens.Spacing.xl, vertical = NekoTokens.Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(0.44f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
        ) {
            HealthRing(f, state.otp, size = 168.dp, thickness = 14.dp)
            HealthLegend(f, state.otp, columns = 2)
        }
        Conveyor(state.log, Modifier.weight(0.56f).fillMaxHeight())
    }
}

/** Ring + centred health title. */
@Composable
fun HealthRing(f: ForwarderState, otp: OtpHealth, size: Dp, thickness: Dp) {
    val h = healthText(f.health())
    // Small rings sit next to the health pill, so they carry only the post box.
    val withTitle = size >= 140.dp
    SegmentRing(segments = healthSegments(f, otp), size = size, thickness = thickness) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                NekoIcons.PostBox,
                contentDescription = null,
                tint = NekopostTheme.colors.tone(h.tone).content,
                modifier = Modifier.size(size * if (withTitle) 0.2f else 0.36f),
            )
            if (withTitle) {
                Text(
                    stringResource(h.title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = thickness + 6.dp),
                )
            }
        }
    }
}

/** Dot + label + state for every ring segment: the ring never relies on colour alone. */
@Composable
fun HealthLegend(f: ForwarderState, otp: OtpHealth, columns: Int, modifier: Modifier = Modifier) {
    val parts = HealthPart.entries
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs)) {
        parts.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m)) {
                row.forEach { part ->
                    val tone = f.toneOf(part, otp)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Dot(tone)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            healthPartLabel(part),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Dot(tone: StatusTone, size: Dp = 8.dp) {
    val color = if (tone == StatusTone.Neutral) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        NekopostTheme.colors.tone(tone).content
    }
    Surface(shape = CircleShape, color = color, modifier = Modifier.size(size)) {}
}

/**
 * The conveyor: latest items stacked on the mail slot, newest at the bottom
 * right above the hinge. A newly arrived item slides up out of the slot
 * (instantly with reduced motion); the list only changes when the log does,
 * so nothing animates while idle.
 */
@Composable
private fun Conveyor(log: List<ForwardLogItem>, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    Column(
        modifier.clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs, Alignment.Bottom),
    ) {
        Text(
            stringResource(R.string.monitor_conveyor_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = NekoTokens.Spacing.xs).semantics { heading() },
        )
        if (log.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = NekoTokens.Spacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SleepingCat(size = 72.dp)
                Text(
                    stringResource(R.string.monitor_conveyor_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Oldest first so the newest lands next to the slot.
            log.take(CONVEYOR_ITEMS).asReversed().forEach { item ->
                key(item.id) {
                    val visible = remember { MutableTransitionState(reduced).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = visible,
                        enter = slideInVertically(NekoMotion.spatial(reduced)) { it } + fadeIn(),
                    ) {
                        ConveyorCard(item)
                    }
                }
            }
        }
        // The mail slot sits right above the hinge.
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth().height(6.dp),
        ) {}
    }
}

private const val CONVEYOR_ITEMS = 6

@Composable
private fun ConveyorCard(item: ForwardLogItem) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindBadge(item.kind, size = 32.dp)
            Spacer(Modifier.width(NekoTokens.Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${kindText(item.kind)} · ${formatRelative(item.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Dot(item.status.tone())
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(item.status.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Compact status pill used by the tabletop log/settings headers. */
@Composable
fun HealthPill(f: ForwarderState) {
    val h = healthText(f.health())
    StatusPill(text = stringResource(h.title), tone = h.tone)
}
