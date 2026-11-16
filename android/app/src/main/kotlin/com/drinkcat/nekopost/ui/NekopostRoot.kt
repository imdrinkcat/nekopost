package com.drinkcat.nekopost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drinkcat.nekopost.AppContainer
import com.drinkcat.nekopost.BuildConfig
import com.drinkcat.nekopost.inbox.InboxScreen
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.system.InstalledApp
import com.drinkcat.nekopost.system.loadLauncherApps
import com.drinkcat.nekopost.system.readDeviceTraits
import com.drinkcat.nekopost.system.readPermissionStatus
import com.drinkcat.nekopost.system.rememberFoldDebugInfo
import com.drinkcat.nekopost.ui.setup.SetupRoute
import com.drinkcat.nekopost.ui.setup.SetupSnapshot
import com.drinkcat.nekopost.ui.setup.SetupStep
import com.drinkcat.nekopost.ui.setup.pendingRequired
import com.drinkcat.nekopost.ui.shell.Destination
import com.drinkcat.nekopost.ui.shell.NekopostShell
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellUiState
import com.drinkcat.nekopost.ui.shell.TestSendState
import com.drinkcat.nekopost.ui.shell.rememberShellMemory
import kotlinx.coroutines.launch

/**
 * Stateful entry point. Collects the forwarder, re-checks permissions on every
 * onResume (and reports them to the forwarder), and shows either the setup
 * wizard or the adaptive shell. Everything below is stateless and takes the
 * injected [fold].
 */
@Composable
fun NekopostRoot(container: AppContainer, fold: FoldState) {
    val controller = container.forwarder
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val forwarderState by controller.state.collectAsStateWithLifecycle()
    val logFlow = remember(controller) { controller.observeLog() }
    val otpFlow = remember(controller) { controller.otpHealth() }
    val log by logFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val otp by otpFlow.collectAsStateWithLifecycle(initialValue = OtpHealth.Empty)
    val prefs by container.uiPrefs.state.collectAsStateWithLifecycle()

    var permissions by remember { mutableStateOf(context.readPermissionStatus()) }
    val device = remember(context) { context.readDeviceTraits() }
    var smsBlocked by rememberSaveable { mutableStateOf(false) }
    val refresh: () -> Unit = { permissions = context.readPermissionStatus() }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(permissions) { controller.reportPermissions(permissions) }

    val snapshot = SetupSnapshot(
        enrollment = forwarderState.enrollment,
        permissions = permissions,
        otp = otp,
        device = device,
        acknowledged = prefs.acknowledgedSteps.mapNotNull { name -> SetupStep.entries.firstOrNull { it.name == name } }.toSet(),
        smsBlocked = smsBlocked,
    )

    var wizardOpen by rememberSaveable { mutableStateOf(!prefs.setupCompleted) }
    var wizardStep by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    var destination by rememberSaveable { mutableStateOf(Destination.Forward) }
    val memory = rememberShellMemory()
    var testSend by remember { mutableStateOf<TestSendState>(TestSendState.Idle) }
    val apps by produceState<List<InstalledApp>?>(initialValue = null, context) { value = context.loadLauncherApps() }
    val debugAvailable = remember(context) {
        BuildConfig.DEBUG ||
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
    }

    val paired by produceState(false, container) { value = container.inbox.paired() }
    LaunchedEffect(paired) {
        if (paired && !prefs.setupCompleted) {
            wizardOpen = false
            destination = Destination.Inbox
        }
    }

    if (wizardOpen) {
        Column(Modifier.fillMaxSize()) {
            TextButton(
                onClick = { wizardOpen = false; destination = Destination.Inbox },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            ) { Text("已有设备配对码？打开收件箱") }
            Box(Modifier.weight(1f)) {
                SetupRoute(
                    controller = controller,
                    snapshot = snapshot,
                    step = wizardStep,
                    fold = fold,
                    canClose = prefs.setupCompleted,
                    allowInsecureLocalhost = container.allowInsecureLocalhost,
                    onStepChange = { wizardStep = it },
                    onAcknowledge = { container.uiPrefs.acknowledge(it.name) },
                    onSmsBlockedChange = { smsBlocked = it },
                    onRefreshChecks = refresh,
                    onFinish = {
                        container.uiPrefs.setSetupCompleted(true)
                        wizardOpen = false
                        destination = Destination.Forward
                    },
                    onClose = { wizardOpen = false },
                )
            }
        }
        return
    }

    val foldDebug = if (memory.debugUnlocked) rememberFoldDebugInfo(fold) else null
    NekopostShell(
        inboxContent = { mode -> InboxScreen(container.inbox, fold, mode) },
        state = ShellUiState(
            forwarder = forwarderState,
            log = log,
            otp = otp,
            appVersion = container.appVersion,
            testSend = testSend,
            setupPending = snapshot.pendingRequired().size,
            apps = apps,
            smsRules = forwarderState.smsSenderRules,
            debugPanelAvailable = debugAvailable,
            foldDebug = foldDebug,
        ),
        fold = fold,
        destination = destination,
        onDestinationChange = { destination = it },
        memory = memory,
        actions = ShellActions(
            onSmsEnabledChange = controller::setSmsEnabled,
            onNotificationsEnabledChange = controller::setNotificationsEnabled,
            onNotificationAppsChange = controller::setNotificationApps,
            onSmsRulesChange = controller::setSmsSenderRules,
            onOpenSetup = {
                wizardStep = if (snapshot.enrollment == null) {
                    SetupStep.Enroll
                } else {
                    snapshot.pendingRequired().firstOrNull() ?: SetupStep.Welcome
                }
                wizardOpen = true
            },
            onSendTest = {
                testSend = TestSendState.Sending
                scope.launch { testSend = TestSendState.Done(controller.sendTest()) }
            },
            onRetryNow = controller::retryNow,
            onResolveSmsBacklog = controller::resolveSmsBacklog,
            onClearFailed = controller::clearFailed,
            onUnenroll = {
                controller.unenroll()
                container.uiPrefs.resetSetup()
                memory.selectedLogId = null
                memory.settingsItem = null
                testSend = TestSendState.Idle
            },
            onCopy = { text ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Nekopost", text))
            },
        ),
    )
}
