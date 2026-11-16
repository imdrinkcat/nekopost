package com.drinkcat.nekopost

import android.content.Context
import com.drinkcat.nekopost.core.forwarder.ForwarderController
import com.drinkcat.nekopost.data.UiPrefs
import com.drinkcat.nekopost.forwarder.AndroidForwarderController
import com.drinkcat.nekopost.inbox.DeviceInbox
import com.drinkcat.nekopost.lock.AppLock
import com.drinkcat.nekopost.system.AppIconLoader
import com.drinkcat.nekopost.system.DeviceAuth
import com.drinkcat.nekopost.system.ExternalLauncher
import com.drinkcat.nekopost.system.PackageIconLoader
import com.drinkcat.nekopost.ui.shell.NavRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual DI (no Hilt): every long-lived object is created here exactly once.
 * Screens never construct dependencies themselves; they receive them from
 * MainActivity, which reads them from this container.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /** Process-lifetime scope for work that must outlive a screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Main-thread scope for lifecycle-driven work (the app lock ticker). */
    val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal val inbox by lazy { DeviceInbox(appContext) }
    internal fun newInboxWindow() = inbox.newWindow()

    val forwarder: ForwarderController by lazy { AndroidForwarderController(appContext, appScope) }

    /** UI preferences (wizard progress, app lock, notification display). */
    val uiPrefs: UiPrefs by lazy { UiPrefs(appContext) }

    /** The app-level lock (process singleton). */
    val lock: AppLock by lazy { AppLock(uiPrefs, secure = { DeviceAuth.canUnlock(appContext) }) }

    /** Starts the scanner, settings pages and permission dialogs; holds their results. */
    val launcher: ExternalLauncher by lazy { ExternalLauncher() }

    /** Notification taps and other requests to show something, consumed by the root after unlock. */
    val navigation = MutableStateFlow<NavRequest?>(null)

    /** Launcher icons for the notification app picker, cached for the process lifetime. */
    val iconLoader: AppIconLoader by lazy { PackageIconLoader(appContext) }

    /** Plain http to 10.0.2.2 / localhost in enrollment links (debug builds only). */
    val allowInsecureLocalhost: Boolean = BuildConfig.DEBUG

    val appVersion: String = BuildConfig.VERSION_NAME
}
