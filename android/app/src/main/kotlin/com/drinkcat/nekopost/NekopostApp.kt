package com.drinkcat.nekopost

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.drinkcat.nekopost.forwarder.ForwarderStartup
import com.drinkcat.nekopost.forwarder.upload.ForwarderDeviceAuth
import com.drinkcat.nekopost.inbox.PushRegistration
import com.drinkcat.nekopost.inbox.notify.InboxNotifier
import kotlinx.coroutines.launch

class NekopostApp : Application() {
    /** Manual dependency container; created once per process. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ForwarderDeviceAuth.signer = { server, source, target, at, nonce ->
            container.inbox.signForwarder(server, source, target, at, nonce)
        }
        // A paired inbox is something to protect, even before the UI has looked at it.
        // So is a forwarder enrollment (an upgrade with an unfinished wizard had no armed flag yet).
        // Both are checked before container.lock exists, so the first frame is already locked.
        if (!container.uiPrefs.state.value.lockArmed &&
            (container.inbox.hasStoredSession() || ForwarderStartup.hasEnrollment(this))
        ) {
            container.uiPrefs.arm()
        }
        container.lock.attach(ProcessLifecycleOwner.get(), container.mainScope)
        container.lock.registerScreenOff(this)
        InboxNotifier.ensureChannels(this)
        // Notifications posted while locked show only 「1 条新消息」; the unlock brings their content back.
        // ACTION_USER_PRESENT is not delivered to manifest receivers.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    container.appScope.launch {
                        InboxNotifier.revealAfterUnlock(context, container.uiPrefs.state.value.notificationContent) { id ->
                            container.inbox.cachedDetail(id)
                        }
                    }
                }
            },
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        container.appScope.launch {
            if (container.inbox.canReceive()) PushRegistration.enable(this@NekopostApp)
        }
    }
}
