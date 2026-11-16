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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.ForwardLogItem
import com.drinkcat.nekopost.core.forwarder.ForwarderController
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.forward.ForwardScreen
import com.drinkcat.nekopost.ui.log.LogScreen
import com.drinkcat.nekopost.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

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

/** Everything the shell renders; screenshot tests build it from FakeForwarderData. */
@Immutable
data class ShellUiState(
    val forwarder: ForwarderState,
    val log: List<ForwardLogItem>,
    val otp: OtpHealth,
    val appVersion: String,
)

/** UI events. Defaults are no-ops so previews and tests can omit them. */
@Immutable
data class ShellActions(
    val onSmsEnabledChange: (Boolean) -> Unit = {},
    val onNotificationsEnabledChange: (Boolean) -> Unit = {},
    val onScanEnrollment: () -> Unit = {},
    val onSendTest: () -> Unit = {},
    val onRetryNow: () -> Unit = {},
    val onUnenroll: () -> Unit = {},
)

/** Stateful entry point: collects the controller and keeps the selected destination. */
@Composable
fun NekopostRoot(controller: ForwarderController, fold: FoldState, appVersion: String) {
    val forwarderState by controller.state.collectAsStateWithLifecycle()
    val logFlow = remember(controller) { controller.observeLog() }
    val otpFlow = remember(controller) { controller.otpHealth() }
    val log by logFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val otp by otpFlow.collectAsStateWithLifecycle(initialValue = OtpHealth.Empty)
    var destination by rememberSaveable { mutableStateOf(Destination.Forward) }
    val scope = rememberCoroutineScope()
    val actions = ShellActions(
        onSmsEnabledChange = controller::setSmsEnabled,
        onNotificationsEnabledChange = controller::setNotificationsEnabled,
        onSendTest = { scope.launch { controller.sendTest() } },
        onRetryNow = controller::retryNow,
        onUnenroll = controller::unenroll,
    )
    NekopostShell(
        state = ShellUiState(forwarderState, log, otp, appVersion),
        fold = fold,
        destination = destination,
        onDestinationChange = { destination = it },
        actions = actions,
    )
}

/** Stateless adaptive shell: navigation suite + the selected screen. */
@Composable
fun NekopostShell(
    state: ShellUiState,
    fold: FoldState,
    destination: Destination,
    onDestinationChange: (Destination) -> Unit,
    actions: ShellActions,
    modifier: Modifier = Modifier,
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
                    Destination.Forward -> ForwardScreen(state, fold, mode, actions)
                    Destination.Log -> LogScreen(state, fold, mode)
                    Destination.Settings -> SettingsScreen(state, fold, mode, actions)
                }
            }
        }
    }
}
