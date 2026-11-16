package com.drinkcat.nekopost

import android.app.Application
import com.drinkcat.nekopost.inbox.PushRegistration
import com.drinkcat.nekopost.forwarder.upload.ForwarderDeviceAuth
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
        container.appScope.launch {
            if (container.inbox.canReceive()) PushRegistration.enable(this@NekopostApp)
        }
    }
}
