package com.drinkcat.nekopost

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
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
        if (container.inbox.hasStoredSession()) container.uiPrefs.arm()
        container.lock.attach(ProcessLifecycleOwner.get(), container.mainScope)
        container.lock.registerScreenOff(this)
        InboxNotifier.ensureChannels(this)
        container.appScope.launch {
            if (container.inbox.canReceive()) PushRegistration.enable(this@NekopostApp)
        }
    }
}
