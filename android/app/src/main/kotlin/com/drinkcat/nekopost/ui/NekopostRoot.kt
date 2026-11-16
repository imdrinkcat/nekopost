package com.drinkcat.nekopost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drinkcat.nekopost.AppContainer
import com.drinkcat.nekopost.BuildConfig
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.inbox.AdminController
import com.drinkcat.nekopost.inbox.InboxRoute
import com.drinkcat.nekopost.inbox.PushRegistration
import com.drinkcat.nekopost.inbox.notify.InboxNotifier
import com.drinkcat.nekopost.inbox.ui.InboxSubScreen
import com.drinkcat.nekopost.lock.LocalAppLock
import com.drinkcat.nekopost.lock.LockState
import com.drinkcat.nekopost.system.AuthOutcome
import com.drinkcat.nekopost.system.DeviceAuth
import com.drinkcat.nekopost.system.InstalledApp
import com.drinkcat.nekopost.system.LocalExternalLauncher
import com.drinkcat.nekopost.system.ScanTarget
import com.drinkcat.nekopost.system.SensitiveClipboard
import com.drinkcat.nekopost.system.SystemIntents
import com.drinkcat.nekopost.system.loadLauncherApps
import com.drinkcat.nekopost.system.readDeviceTraits
import com.drinkcat.nekopost.system.readPermissionStatus
import com.drinkcat.nekopost.system.rememberFoldDebugInfo
import com.drinkcat.nekopost.ui.common.NoticeCard
import com.drinkcat.nekopost.ui.settings.AdminActions
import com.drinkcat.nekopost.ui.settings.LockSettingsUi
import com.drinkcat.nekopost.ui.settings.NotifySettingsUi
import com.drinkcat.nekopost.ui.settings.SettingsActions
import com.drinkcat.nekopost.ui.settings.SettingsItem
import com.drinkcat.nekopost.ui.settings.ThisDeviceUi
import com.drinkcat.nekopost.ui.setup.SetupRoute
import com.drinkcat.nekopost.ui.setup.SetupSnapshot
import com.drinkcat.nekopost.ui.setup.SetupStep
import com.drinkcat.nekopost.ui.setup.pendingRequired
import com.drinkcat.nekopost.ui.shell.Destination
import com.drinkcat.nekopost.ui.shell.NavRequest
import com.drinkcat.nekopost.ui.shell.NekopostShell
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellUiState
import com.drinkcat.nekopost.ui.shell.TestSendState
import com.drinkcat.nekopost.ui.shell.rememberShellMemory
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Stateful entry point. Collects the forwarder, the receiving inbox and the
 * preferences, re-checks permissions on every onResume (and reports them to
 * the forwarder), and shows either the setup wizard or the adaptive shell.
 * Everything below is stateless and takes the injected [fold]. The whole tree
 * sits inside the app lock's LockGate (MainActivity), which keeps it composed.
 */
@Composable
fun NekopostRoot(container: AppContainer, fold: FoldState) {
    val controller = container.forwarder
    val inbox = container.inbox
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = LocalExternalLauncher.current
    val lockUi = LocalAppLock.current

    val forwarderState by controller.state.collectAsStateWithLifecycle()
    val logFlow = remember(controller) { controller.observeLog() }
    val otpFlow = remember(controller) { controller.otpHealth() }
    val log by logFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val otp by otpFlow.collectAsStateWithLifecycle(initialValue = OtpHealth.Empty)
    val prefs by container.uiPrefs.state.collectAsStateWithLifecycle()
    val inboxState by inbox.state.collectAsStateWithLifecycle()

    var permissions by remember { mutableStateOf(context.readPermissionStatus()) }
    var secure by remember { mutableStateOf(DeviceAuth.canUnlock(context)) }
    var canNotify by remember { mutableStateOf(InboxNotifier.canPost(context)) }
    val device = remember(context) { context.readDeviceTraits() }
    var smsBlocked by rememberSaveable { mutableStateOf(false) }
    val refresh: () -> Unit = {
        permissions = context.readPermissionStatus()
        secure = DeviceAuth.canUnlock(context)
        canNotify = InboxNotifier.canPost(context)
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(permissions) { controller.reportPermissions(permissions) }

    // Something to protect exists: arm the app lock (without locking the present user).
    LaunchedEffect(inbox) { if (inbox.state.value.paired == null) inbox.load() }
    LaunchedEffect(forwarderState.isEnrolled) { if (forwarderState.isEnrolled) container.lock.arm() }
    LaunchedEffect(inboxState.paired, inboxState.canRead) {
        if (inboxState.paired == true) {
            container.lock.arm()
            inbox.loadSelf()
            if (inboxState.canRead) PushRegistration.enable(context)
        }
    }

    val snapshot = SetupSnapshot(
        enrollment = forwarderState.enrollment,
        permissions = permissions,
        otp = otp,
        device = device,
        acknowledged = prefs.acknowledgedSteps.mapNotNull { name -> SetupStep.entries.firstOrNull { it.name == name } }.toSet(),
        smsBlocked = smsBlocked,
        appLock = if (!prefs.appLockEnabled) null else secure,
    )

    var wizardOpen by rememberSaveable { mutableStateOf(!prefs.setupCompleted) }
    var wizardStep by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    var chosenDestination by rememberSaveable { mutableStateOf<Destination?>(null) }
    val memory = rememberShellMemory()
    var testSend by remember { mutableStateOf<TestSendState>(TestSendState.Idle) }
    var pushTesting by remember { mutableStateOf(false) }
    var pushTestResult by remember { mutableStateOf<String?>(null) }
    val apps by produceState<List<InstalledApp>?>(initialValue = null, context) { value = context.loadLauncherApps() }
    val debugAvailable = remember(context) {
        BuildConfig.DEBUG ||
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
    }

    // 消息 is there when this phone can read messages, or to offer pairing.
    val inboxTab = inboxState.canRead || inboxState.paired == false
    val destination = chosenDestination?.takeIf { it != Destination.Inbox || inboxTab }
        ?: if (inboxState.canRead) Destination.Inbox else Destination.Forward

    // A phone paired only to receive never needs the forwarding wizard.
    LaunchedEffect(inboxState.paired) {
        if (inboxState.paired == true && !prefs.setupCompleted) wizardOpen = false
    }

    fun openPairing(link: String?) {
        wizardOpen = false
        chosenDestination = Destination.Inbox
        memory.inbox.subScreen = InboxSubScreen.Pair
        if (link != null) launcher.deliver(ScanTarget.Pair, link)
    }

    // Notification taps: open the message once the lock is open.
    val nav by container.navigation.collectAsStateWithLifecycle()
    LaunchedEffect(nav, lockUi.state.hidesContent) {
        val request = nav ?: return@LaunchedEffect
        if (lockUi.state.hidesContent) return@LaunchedEffect
        when (request) {
            is NavRequest.OpenMessage -> {
                wizardOpen = false
                chosenDestination = Destination.Inbox
                memory.inbox.subScreen = InboxSubScreen.None
                memory.inbox.selectedId = request.id
            }
        }
        container.navigation.value = null
    }

    if (wizardOpen) {
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
                chosenDestination = null
            },
            onClose = { wizardOpen = false },
            onPairDevice = ::openPairing,
        )
        return
    }

    val admin = remember(inbox) { AdminController(inbox, container.appScope, context.applicationContext) }
    admin.authorize = { signature -> DeviceAuth.authorize(context, signature) }
    val adminState by admin.state.collectAsStateWithLifecycle()
    val self = inboxState.self
    val deviceUi = if (inboxState.paired == true) {
        ThisDeviceUi(
            name = self?.name.orEmpty(),
            serverHost = inboxState.server.orEmpty(),
            deviceId = inboxState.deviceId.orEmpty(),
            canRead = inboxState.canRead,
            canSend = inboxState.canSend,
            canAdmin = inboxState.canAdmin,
            pushState = self?.pushState,
            telegramTypes = self?.telegramTypes,
            serverKnown = self != null,
            pushEnabled = self?.pushEnabled ?: true,
        )
    } else {
        null
    }
    val copyLabel = stringResource(R.string.inbox_code_clip_label)
    val pushSent = stringResource(R.string.admin_test_push_sent)
    val lockOffTitle = stringResource(R.string.lock_off_prompt)

    val foldDebug = if (memory.debugUnlocked) rememberFoldDebugInfo(fold) else null
    val insecureBanner = prefs.appLockEnabled && prefs.lockArmed && !secure && !prefs.insecureBannerDismissed &&
        lockUi.state == LockState.Unavailable
    Column(Modifier.fillMaxSize()) {
        if (insecureBanner) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                NoticeCard(
                    text = stringResource(R.string.lock_banner_insecure),
                    tone = StatusTone.Warning,
                    action = stringResource(R.string.lock_settings_open_security),
                    onAction = {
                        launcher.startSettings(context, Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                        container.uiPrefs.dismissInsecureBanner()
                    },
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(NekoTokens.Spacing.s),
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .then(if (insecureBanner) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) else Modifier),
        ) {
            NekopostShell(
                inboxContent = if (inboxTab) {
                    { mode ->
                        InboxRoute(
                            inbox = inbox,
                            fold = fold,
                            mode = mode,
                            memory = memory.inbox,
                            appScope = container.appScope,
                            envelopeEnabled = prefs.envelope,
                            serverHint = forwarderState.enrollment?.serverHost?.let { "https://$it/" },
                            onOpenSettings = { chosenDestination = Destination.Settings },
                        )
                    }
                } else {
                    null
                },
                state = ShellUiState(
                    forwarder = forwarderState,
                    log = log,
                    otp = otp,
                    appVersion = container.appVersion,
                    testSend = testSend,
                    setupPending = if (forwarderState.isEnrolled) snapshot.pendingRequired().size else 0,
                    apps = apps,
                    smsRules = forwarderState.smsSenderRules,
                    debugPanelAvailable = debugAvailable,
                    foldDebug = foldDebug,
                    inboxUnread = if (inboxState.canRead) inboxState.unreadCount ?: inboxState.rows.count { !it.read } else 0,
                    device = deviceUi,
                    lock = LockSettingsUi(
                        enabled = prefs.appLockEnabled,
                        secure = secure,
                        graceSeconds = prefs.lockGraceSeconds,
                        idleMinutes = prefs.idleLockMinutes,
                        foldClose = prefs.foldClose,
                        foldable = device.isFoldable,
                        armed = prefs.lockArmed,
                        envelope = prefs.envelope,
                    ),
                    notify = NotifySettingsUi(
                        permission = canNotify,
                        showContent = prefs.notificationContent,
                        testing = pushTesting,
                        testResult = pushTestResult,
                    ),
                    admin = if (inboxState.canAdmin) adminState else null,
                ),
                fold = fold,
                destination = destination,
                onDestinationChange = { chosenDestination = it },
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
                        // Nothing left to protect: forget the lock and display choices too (§8.9).
                        if (inbox.state.value.paired == true) container.uiPrefs.resetSetup() else container.uiPrefs.clearAll()
                        memory.selectedLogId = null
                        memory.settingsItem = null
                        testSend = TestSendState.Idle
                    },
                    onCopy = { text ->
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Nekopost", text))
                    },
                    settings = SettingsActions(
                        onLockEnabled = { on ->
                            if (on) {
                                container.uiPrefs.setAppLockEnabled(true)
                            } else {
                                // Turning the lock off needs the owner (a passer-by must not disable it).
                                DeviceAuth.unlock(context, lockOffTitle, null) { outcome ->
                                    if (outcome is AuthOutcome.Success) container.uiPrefs.setAppLockEnabled(false)
                                }
                            }
                        },
                        onLockGrace = container.uiPrefs::setLockGraceSeconds,
                        onIdleLock = container.uiPrefs::setIdleLockMinutes,
                        onFoldClose = container.uiPrefs::setFoldClose,
                        onOpenSecuritySettings = {
                            launcher.startSettings(context, Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                        },
                        onNotificationContent = container.uiPrefs::setNotificationContent,
                        onEnvelope = container.uiPrefs::setEnvelope,
                        onTestPush = {
                            val id = inboxState.deviceId
                            if (id != null && !pushTesting) {
                                scope.launch {
                                    pushTesting = true
                                    pushTestResult = try {
                                        inbox.manage("POST", listOf("v1", "admin", "devices", id, "test-push"), JSONObject())
                                        pushSent
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        inbox.failureText(e)
                                    } finally {
                                        pushTesting = false
                                    }
                                }
                            }
                        },
                        onOpenNotificationSettings = {
                            launcher.startSettings(context, SystemIntents.appNotificationSettings(context), SystemIntents.appDetails(context))
                        },
                        onPairThisPhone = { openPairing(null) },
                        onUnpairDevice = {
                            container.appScope.launch {
                                if (inbox.unpair()) {
                                    PushRegistration.disable(context.applicationContext)
                                    InboxNotifier.cancelAll(context.applicationContext)
                                    if (!controller.state.value.isEnrolled) container.uiPrefs.clearAll()
                                }
                            }
                            memory.inbox.selectedId = null
                            memory.settingsItem = null
                        },
                        admin = AdminActions(
                            onLoad = admin::load,
                            onAddDevice = admin::addDevice,
                            onTestPush = admin::testPush,
                            onRevokeDevice = admin::revokeDevice,
                            onCreateSource = admin::createSource,
                            onSetSourceEnabled = admin::setSourceEnabled,
                            onRotateSource = admin::rotateSource,
                            onDeleteSource = admin::deleteSource,
                            onCreateKey = admin::createKey,
                            onRevokeKey = admin::revokeKey,
                            onChoosePairing = admin::choose,
                            onClosePairing = admin::closePairing,
                            onDismissSecret = admin::dismissSecret,
                            onCopySecret = { SensitiveClipboard.copy(context, copyLabel, it) },
                        ),
                    ),
                ),
            )
        }
    }
    // Settings › 账户与服务器 after unpairing everything has nothing to show; return to the list.
    LaunchedEffect(memory.settingsItem, deviceUi == null, forwarderState.isEnrolled) {
        if (memory.settingsItem == SettingsItem.Admin && deviceUi?.canAdmin != true) memory.settingsItem = null
    }
}
