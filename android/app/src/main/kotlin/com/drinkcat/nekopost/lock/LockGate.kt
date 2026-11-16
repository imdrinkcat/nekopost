package com.drinkcat.nekopost.lock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.SleepingCat
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.token.NekoTokens

/** Something the lock screen should say after a failed attempt; a plain cancel says nothing. */
sealed interface LockIssue {
    data object Lockout : LockIssue
    data class Failed(val message: String) : LockIssue
}

/**
 * Wraps the whole app. While locked the content stays composed (so
 * ActivityResult registrations, half-typed pairing codes, wizard steps and
 * scroll positions survive) but is not drawn, has no semantics, receives no
 * keys and loses focus; an opaque [LockScreen] sits on top and takes all touches.
 */
@Composable
fun LockGate(
    lock: LockState,
    fold: FoldState,
    issue: LockIssue?,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hidden = lock.hidesContent
    val focus = LocalFocusManager.current
    LaunchedEffect(hidden) { if (hidden) focus.clearFocus(force = true) }
    Box(modifier.fillMaxSize().onPreviewKeyEvent { hidden }) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (hidden) {
                        Modifier
                            .drawWithContent { }
                            .clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) { content() }
        if (hidden) LockScreen(lock, fold, issue, onUnlock, Modifier.fillMaxSize())
    }
}

/** The lock overlay (stateless; screenshot-tested). The curtain is tap-anywhere. */
@Composable
fun LockScreen(lock: LockState, fold: FoldState, issue: LockIssue?, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val curtain = lock == LockState.Curtain
    Surface(
        color = if (curtain) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
        modifier = modifier
            .testTag(if (curtain) "lock_curtain" else "lock_screen")
            .then(if (curtain) Modifier.clickable(onClick = onUnlock) else Modifier),
    ) {
        val title: @Composable () -> Unit = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
                modifier = Modifier.fillMaxWidth().padding(NekoTokens.Spacing.xl),
            ) {
                Box {
                    SleepingCat(size = 96.dp)
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(36.dp).align(Alignment.BottomEnd),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Text(
                    stringResource(if (curtain) R.string.lock_curtain_title else R.string.lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(reasonText(lock)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        val controls: @Composable () -> Unit = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
                modifier = Modifier.fillMaxWidth().padding(NekoTokens.Spacing.xl),
            ) {
                when (issue) {
                    LockIssue.Lockout -> IssueText(stringResource(R.string.lock_issue_lockout))
                    is LockIssue.Failed -> IssueText(issue.message)
                    null -> Unit
                }
                if (curtain) {
                    Text(
                        stringResource(R.string.lock_curtain_tap),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Button(
                        onClick = onUnlock,
                        modifier = Modifier
                            .widthIn(min = 220.dp)
                            .heightIn(min = NekoTokens.Touch.coverPrimaryTarget)
                            .testTag("lock_unlock"),
                    ) {
                        Text(stringResource(R.string.lock_unlock), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (fold.posture == Posture.Tabletop) {
                HingeAwareSplit(
                    fold = fold,
                    axis = SplitAxis.Vertical,
                    modifier = Modifier.fillMaxSize(),
                    first = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { title() } },
                    second = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { controls() } },
                )
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    title()
                    Spacer(Modifier.height(NekoTokens.Spacing.s))
                    controls()
                }
            }
        }
    }
}

@Composable
private fun IssueText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}

private fun reasonText(lock: LockState): Int = when (lock) {
    LockState.Curtain -> R.string.lock_reason_fold_curtain
    is LockState.Locked -> when (lock.reason) {
        LockReason.ColdStart -> R.string.lock_reason_cold
        LockReason.Background -> R.string.lock_reason_background
        LockReason.ScreenOff -> R.string.lock_reason_screen_off
        LockReason.Idle -> R.string.lock_reason_idle
        LockReason.Fold -> R.string.lock_reason_fold
        LockReason.Manual -> R.string.lock_reason_manual
    }
    else -> R.string.lock_reason_cold
}
