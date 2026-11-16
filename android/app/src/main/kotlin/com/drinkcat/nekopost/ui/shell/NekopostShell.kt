package com.drinkcat.nekopost.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.ForwardLogItem
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.SendTestResult
import com.drinkcat.nekopost.core.rules.SmsSenderRule
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.system.FoldDebugInfo
import com.drinkcat.nekopost.system.InstalledApp
import com.drinkcat.nekopost.ui.forward.ForwardScreen
import com.drinkcat.nekopost.ui.log.LogScreen
import com.drinkcat.nekopost.ui.settings.SettingsScreen

/** Top-level destinations. */
enum class Destination(@param:StringRes val label: Int, val icon: ImageVector) {
    Forward(R.string.nav_forward, Icons.AutoMirrored.Filled.Send),
    Log(R.string.nav_log, Icons.AutoMirrored.Filled.List),
    Settings(R.string.nav_settings, Icons.Filled.Settings),
}

/** How a screen arranges its content for the current posture and width. */
enum class PaneMode {
    /** One scrolling column (cover display, phones, narrow split screen). */
    Single,

    /** List + detail side by side (inner display, open). */
    TwoPane,

    /** Tabletop: display on the upper half, controls on the lower half. */
    TopBottom,
}

fun paneModeFor(fold: FoldState, contentWidth: Dp): PaneMode = when {
    fold.posture == Posture.Tabletop -> PaneMode.TopBottom
    fold.posture == Posture.Cover -> PaneMode.Single
    contentWidth >= NekoTokens.Breakpoint.twoPane -> PaneMode.TwoPane
    else -> PaneMode.Single
}

/**
 * Navigation component per posture: a bottom bar on the cover display and on
 * narrow windows, a bottom bar in tabletop as well (controls belong on the lower
 * half), and a collapsed rail on the open inner display.
 */
fun navigationTypeFor(fold: FoldState, windowWidth: Dp): NavigationSuiteType = when {
    fold.posture == Posture.Tabletop -> NavigationSuiteType.ShortNavigationBarMedium
    fold.posture == Posture.Cover -> NavigationSuiteType.ShortNavigationBarCompact
    windowWidth >= 600.dp -> NavigationSuiteType.WideNavigationRailCollapsed
    else -> NavigationSuiteType.ShortNavigationBarCompact
}

/** Progress of the "发送测试消息" button. */
sealed interface TestSendState {
    data object Idle : TestSendState
    data object Sending : TestSendState
    data class Done(val result: SendTestResult) : TestSendState
}

/** Everything the shell renders; screenshot tests build it from FakeForwarderData. */
@Immutable
data class ShellUiState(
    val forwarder: ForwarderState,
    val log: List<ForwardLogItem>,
    val otp: OtpHealth,
    val appVersion: String,
    val testSend: TestSendState = TestSendState.Idle,
    /** Required setup steps still open (shown as a banner on 转发). */
    val setupPending: Int = 0,
    /** Launcher apps for the notification picker; null while loading. */
    val apps: List<InstalledApp>? = null,
    val smsRules: List<SmsSenderRule> = emptyList(),
    /** The foldable debug panel may be unlocked (debug build or developer options on). */
    val debugPanelAvailable: Boolean = false,
    val foldDebug: FoldDebugInfo? = null,
)

/** UI events. Defaults are no-ops so previews and tests can omit them. */
@Immutable
data class ShellActions(
    val onSmsEnabledChange: (Boolean) -> Unit = {},
    val onNotificationsEnabledChange: (Boolean) -> Unit = {},
    val onNotificationAppsChange: (Set<String>) -> Unit = {},
    val onSmsRulesChange: (List<SmsSenderRule>) -> Unit = {},
    val onOpenSetup: () -> Unit = {},
    val onSendTest: () -> Unit = {},
    val onRetryNow: () -> Unit = {},
    val onClearFailed: () -> Unit = {},
    val onUnenroll: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
)

/** Stateless adaptive shell: navigation suite + the selected screen. */
@Composable
fun NekopostShell(
    state: ShellUiState,
    fold: FoldState,
    destination: Destination,
    onDestinationChange: (Destination) -> Unit,
    actions: ShellActions,
    modifier: Modifier = Modifier,
    memory: ShellMemory = rememberShellMemory(),
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val navType = navigationTypeFor(fold, maxWidth)
        NavigationSuiteScaffold(
            navigationItems = {
                Destination.entries.forEach { d ->
                    NavigationSuiteItem(
                        selected = d == destination,
                        onClick = { onDestinationChange(d) },
                        icon = { Icon(imageVector = d.icon, contentDescription = null) },
                        label = { Text(stringResource(d.label)) },
                        navigationSuiteType = navType,
                    )
                }
            },
            navigationSuiteType = navType,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)),
            ) {
                val mode = paneModeFor(fold, maxWidth)
                when (destination) {
                    Destination.Forward -> ForwardScreen(state, fold, mode, actions, memory, onShowLog = { onDestinationChange(Destination.Log) })
                    Destination.Log -> LogScreen(state, fold, mode, memory, onCopy = actions.onCopy)
                    Destination.Settings -> SettingsScreen(state, fold, mode, actions, memory)
                }
            }
        }
    }
}
