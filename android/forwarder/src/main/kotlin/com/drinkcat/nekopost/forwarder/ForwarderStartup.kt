package com.drinkcat.nekopost.forwarder

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.drinkcat.nekopost.forwarder.store.ForwarderPrefs
import kotlinx.coroutines.runBlocking

/** Checks the app needs before its first frame, without the controller's flows. */
object ForwarderStartup {
    /**
     * Whether this phone holds a forwarder enrollment. Blocking (one small
     * DataStore read), so the app lock can be armed before anything is drawn;
     * cheap when nothing was ever stored (no file, no read).
     */
    fun hasEnrollment(context: Context): Boolean {
        val app = context.applicationContext
        if (!app.preferencesDataStoreFile(ForwarderPrefs.FILE_NAME).exists()) return false
        return runCatching { runBlocking { ForwarderGraph.get(app).prefs.current().enrollment != null } }.getOrDefault(false)
    }
}
