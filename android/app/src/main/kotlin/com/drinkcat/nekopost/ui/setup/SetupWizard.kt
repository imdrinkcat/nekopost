package com.drinkcat.nekopost.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.motion.LocalReducedMotion
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.common.ToneIcon
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.paneModeFor

/**
 * The first-run wizard (design §8.6). Stateless: the current [step], the live
 * [snapshot] (re-read on every onResume) and the enrollment state come from
 * [SetupRoute]. Layout per posture:
 *  - Single (cover): progress bar, one scrolling step, back/next at the bottom.
 *  - TwoPane (inner display): step list with ✅/⚠️ on the left, the step on the right.
 *  - TopBottom (tabletop): the step's title and state above the hinge, its
 *    explanation and buttons below.
 */
@Composable
fun SetupWizard(
    snapshot: SetupSnapshot,
    step: SetupStep,
    enroll: EnrollUiState,
    fold: FoldState,
    actions: SetupActions,
    canClose: Boolean,
    modifier: Modifier = Modifier,
) {
    val previous = snapshot.previousStep(step)
    BackHandler(enabled = previous != null) { previous?.let(actions.onStepChange) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (paneModeFor(fold, maxWidth)) {
                PaneMode.Single -> Column(Modifier.fillMaxSize()) {
                    WizardTopBar(snapshot, step, canClose, actions)
                    StepPager(step, Modifier.weight(1f)) { s ->
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NekoTokens.Spacing.l),
                            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.l),
                        ) {
                            StepHero(s, snapshot, large = false)
                            StepDetails(s, snapshot, enroll, actions)
                        }
                    }
                    WizardNavBar(snapshot, step, actions, large = false)
                }

                PaneMode.TwoPane -> HingeAwareSplit(
                    fold = fold,
                    axis = SplitAxis.Horizontal,
                    fraction = 0.36f,
                    modifier = Modifier.fillMaxSize(),
                    first = { StepList(snapshot, step, canClose, actions) },
                    second = {
                        Column(Modifier.fillMaxSize()) {
                            StepPager(step, Modifier.weight(1f)) { s ->
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                        .padding(horizontal = NekoTokens.Spacing.xxl, vertical = NekoTokens.Spacing.xl),
                                    verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.l),
                                ) {
                                    StepHero(s, snapshot, large = true)
                                    StepDetails(s, snapshot, enroll, actions)
                                }
                            }
                            WizardNavBar(snapshot, step, actions, large = false)
                        }
                    },
                )

                PaneMode.TopBottom -> HingeAwareSplit(
                    fold = fold,
                    axis = SplitAxis.Vertical,
                    modifier = Modifier.fillMaxSize(),
                    first = { TabletopStepDisplay(snapshot, step) },
                    second = {
                        Column(Modifier.fillMaxSize()) {
                            StepPager(step, Modifier.weight(1f)) { s ->
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NekoTokens.Spacing.l),
                                    verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.l),
                                ) {
                                    StepDetails(s, snapshot, enroll, actions, showStamp = false)
                                }
                            }
                            WizardNavBar(snapshot, step, actions, large = true)
                        }
                    },
                )
            }
        }
    }
}

/** Cross-fades between steps (instantly with reduced motion). */
@Composable
private fun StepPager(step: SetupStep, modifier: Modifier, content: @Composable (SetupStep) -> Unit) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = step,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                fadeIn(tween(NekoTokens.Motion.REDUCED_MS)) togetherWith fadeOut(tween(NekoTokens.Motion.REDUCED_MS))
            } else {
                fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(120))
            }
        },
        label = "step",
    ) { s -> content(s) }
}

/** Icon, title and colour of each step. */
internal data class StepInfo(val icon: ImageVector, val title: Int, val summary: Int)

internal fun stepInfo(step: SetupStep): StepInfo = when (step) {
    SetupStep.Welcome -> StepInfo(NekoIcons.PostBox, R.string.setup_welcome_title, R.string.setup_welcome_summary)
    SetupStep.Enroll -> StepInfo(NekoIcons.QrScan, R.string.setup_enroll_title, R.string.setup_enroll_summary)
    SetupStep.RestrictedSettings -> StepInfo(NekoIcons.Shield, R.string.setup_restricted_title, R.string.setup_restricted_summary)
    SetupStep.SmsPermission -> StepInfo(NekoIcons.Sms, R.string.setup_sms_title, R.string.setup_sms_summary)
    SetupStep.NotificationAccess -> StepInfo(Icons.Filled.Notifications, R.string.setup_listener_title, R.string.setup_listener_summary)
    SetupStep.PostNotifications -> StepInfo(Icons.Filled.Email, R.string.setup_post_title, R.string.setup_post_summary)
    SetupStep.Battery -> StepInfo(NekoIcons.Battery, R.string.setup_battery_title, R.string.setup_battery_summary)
    SetupStep.CoverScreen -> StepInfo(NekoIcons.CoverScreen, R.string.setup_cover_title, R.string.setup_cover_summary)
    SetupStep.OtpSelfTest -> StepInfo(Icons.Filled.Lock, R.string.setup_otp_title, R.string.setup_otp_summary)
    SetupStep.Finish -> StepInfo(NekoIcons.Paw, R.string.setup_finish_title, R.string.setup_finish_summary)
}

internal fun StepStatus.tone(): StatusTone = when (this) {
    StepStatus.Done -> StatusTone.Success
    StepStatus.NeedsAction -> StatusTone.Warning
    StepStatus.Optional, StepStatus.NotApplicable -> StatusTone.Neutral
}

@Composable
internal fun stepStatusText(status: StepStatus): String = stringResource(
    when (status) {
        StepStatus.Done -> R.string.step_done
        StepStatus.NeedsAction -> R.string.step_needs_action
        StepStatus.Optional -> R.string.step_optional
        StepStatus.NotApplicable -> R.string.step_not_applicable
    },
)

@Composable
private fun WizardTopBar(snapshot: SetupSnapshot, step: SetupStep, canClose: Boolean, actions: SetupActions) {
    val steps = snapshot.visibleSteps()
    val index = steps.indexOf(step).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(start = NekoTokens.Spacing.l, end = NekoTokens.Spacing.xs, top = NekoTokens.Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(NekoIcons.Paw, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Text(
                stringResource(R.string.setup_progress, index + 1, steps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (canClose) {
                IconButton(onClick = actions.onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.setup_close))
                }
            }
        }
        LinearProgressIndicator(
            progress = { (index + 1f) / steps.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(end = NekoTokens.Spacing.m),
        )
    }
}

/** Back / next. "Next" becomes "skip for now" while the step still needs the user. */
@Composable
private fun WizardNavBar(snapshot: SetupSnapshot, step: SetupStep, actions: SetupActions, large: Boolean) {
    val previous = snapshot.previousStep(step)
    val next = snapshot.nextStep(step)
    val status = snapshot.statusOf(step)
    val height = if (large) NekoTokens.Touch.tabletopTarget else NekoTokens.Touch.minTarget
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
        ) {
            if (previous != null) {
                TextButton(onClick = { actions.onStepChange(previous) }, modifier = Modifier.heightIn(min = height)) {
                    Text(stringResource(R.string.setup_back))
                }
            }
            Spacer(Modifier.weight(1f))
            when {
                step == SetupStep.Finish || next == null -> Button(
                    onClick = actions.onFinish,
                    modifier = Modifier.heightIn(min = height).widthIn(min = 120.dp).testTag("setup_finish"),
                ) { Text(stringResource(R.string.setup_start_using)) }

                status == StepStatus.NeedsAction -> OutlinedButton(
                    onClick = { actions.onStepChange(next) },
                    modifier = Modifier.heightIn(min = height).widthIn(min = 120.dp).testTag("setup_next"),
                ) { Text(stringResource(R.string.setup_skip)) }

                else -> Button(
                    onClick = {
                        if (step == SetupStep.Welcome) actions.onAcknowledge(step)
                        actions.onStepChange(next)
                    },
                    modifier = Modifier.heightIn(min = height).widthIn(min = 120.dp).testTag("setup_next"),
                ) {
                    Text(stringResource(if (step == SetupStep.Welcome) R.string.setup_begin else R.string.setup_next))
                }
            }
        }
    }
}

/** Icon, title, status pill and one-line summary of a step. */
@Composable
private fun StepHero(step: SetupStep, snapshot: SetupSnapshot, large: Boolean) {
    val info = stepInfo(step)
    val status = snapshot.statusOf(step)
    if (step == SetupStep.Finish) return // The finish step draws its own stamp.
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToneIcon(info.icon, tone = if (status == StepStatus.Done) StatusTone.Success else StatusTone.Brand, size = if (large) 64.dp else 52.dp)
        Spacer(Modifier.width(NekoTokens.Spacing.l))
        Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs)) {
            Text(
                stringResource(info.title),
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (step != SetupStep.Welcome) {
                StatusPill(text = stepStatusText(status), tone = status.tone())
            }
        }
    }
}

/** Left pane on the inner display: brand header and the full step list. */
@Composable
private fun StepList(snapshot: SetupSnapshot, current: SetupStep, canClose: Boolean, actions: SetupActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NekoTokens.Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(NekoIcons.PostBox, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(NekoTokens.Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.setup_wizard_name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canClose) {
                    IconButton(onClick = actions.onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.setup_close))
                    }
                }
            }
            Spacer(Modifier.size(NekoTokens.Spacing.l))
            snapshot.visibleSteps().forEachIndexed { i, s ->
                StepListRow(i + 1, s, snapshot.statusOf(s), selected = s == current, onClick = { actions.onStepChange(s) })
            }
        }
    }
}

@Composable
private fun StepListRow(number: Int, step: SetupStep, status: StepStatus, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
    ) {
        Row(
            Modifier.heightIn(min = 52.dp).padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepBadge(number, status)
            Spacer(Modifier.width(NekoTokens.Spacing.m))
            Text(stringResource(stepInfo(step).title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                stepStatusText(status),
                style = MaterialTheme.typography.labelMedium,
                color = NekopostTheme.colors.tone(status.tone()).content,
            )
        }
    }
}

/** ✅ / ⚠️ / step number in a small circle. */
@Composable
internal fun StepBadge(number: Int, status: StepStatus) {
    val tone = NekopostTheme.colors.tone(status.tone())
    Surface(shape = CircleShape, color = tone.container, contentColor = tone.content, modifier = Modifier.size(28.dp)) {
        Box(contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.Done -> Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                StepStatus.NeedsAction -> Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                else -> Text(number.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Upper half in tabletop: large and glanceable, nothing to tap. */
@Composable
private fun TabletopStepDisplay(snapshot: SetupSnapshot, step: SetupStep) {
    val info = stepInfo(step)
    val status = snapshot.statusOf(step)
    val steps = snapshot.visibleSteps()
    Box(Modifier.fillMaxSize().padding(NekoTokens.Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.l),
        ) {
            if (step == SetupStep.Finish) {
                FinishStamp(snapshot)
            } else {
                ToneIcon(info.icon, tone = if (status == StepStatus.Done) StatusTone.Success else StatusTone.Brand, size = 96.dp)
                Text(
                    stringResource(info.title),
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                if (step != SetupStep.Welcome) StatusPill(text = stepStatusText(status), tone = status.tone(), large = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { s ->
                    val st = snapshot.statusOf(s)
                    val color = when {
                        s == step -> MaterialTheme.colorScheme.primary
                        st == StepStatus.Done -> NekopostTheme.colors.success.content
                        st == StepStatus.NeedsAction -> NekopostTheme.colors.warning.content
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Surface(shape = CircleShape, color = color, modifier = Modifier.size(if (s == step) 12.dp else 8.dp)) {}
                }
            }
            Text(
                stringResource(R.string.setup_progress, steps.indexOf(step).coerceAtLeast(0) + 1, steps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
