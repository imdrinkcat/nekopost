package com.drinkcat.nekopost

import android.content.Context
import com.drinkcat.nekopost.core.forwarder.ForwarderController
import com.drinkcat.nekopost.forwarder.AndroidForwarderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual DI (no Hilt): every long-lived object is created here exactly once.
 * Screens never construct dependencies themselves; they receive them from
 * MainActivity, which reads them from this container.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /** Process-lifetime scope for work that must outlive a screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val forwarder: ForwarderController by lazy { AndroidForwarderController(appContext, appScope) }

    /** Plain http to 10.0.2.2 / localhost in enrollment links (debug builds only). */
    val allowInsecureLocalhost: Boolean = BuildConfig.DEBUG

    val appVersion: String = BuildConfig.VERSION_NAME
}
