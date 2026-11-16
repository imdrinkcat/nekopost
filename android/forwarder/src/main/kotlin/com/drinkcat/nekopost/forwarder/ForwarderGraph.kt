package com.drinkcat.nekopost.forwarder

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.drinkcat.nekopost.forwarder.crypto.AndroidKeyManager
import com.drinkcat.nekopost.forwarder.crypto.KeyManager
import com.drinkcat.nekopost.forwarder.notify.AppLabeler
import com.drinkcat.nekopost.forwarder.notify.NotificationIngest
import com.drinkcat.nekopost.forwarder.notify.PackageManagerLabeler
import com.drinkcat.nekopost.forwarder.outbox.ForwarderDatabase
import com.drinkcat.nekopost.forwarder.outbox.Outbox
import com.drinkcat.nekopost.forwarder.sms.AndroidSimLabeler
import com.drinkcat.nekopost.forwarder.sms.ContentResolverInbox
import com.drinkcat.nekopost.forwarder.sms.SimLabeler
import com.drinkcat.nekopost.forwarder.sms.SmsInboxSource
import com.drinkcat.nekopost.forwarder.sms.SmsIngest
import com.drinkcat.nekopost.forwarder.sms.SmsReconciler
import com.drinkcat.nekopost.forwarder.store.ForwarderPrefs
import com.drinkcat.nekopost.forwarder.store.SecretStore
import com.drinkcat.nekopost.forwarder.upload.HttpClients
import com.drinkcat.nekopost.forwarder.upload.Uploader
import com.drinkcat.nekopost.forwarder.work.WorkManagerScheduler
import com.drinkcat.nekopost.forwarder.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import java.time.Clock

/**
 * Process-wide object graph of the forwarder. Receivers, the notification
 * listener and workers have no access to the app's AppContainer, so they and
 * [AndroidForwarderController] all share this one instance.
 */
internal class ForwarderGraph(
    val context: Context,
    /** Background scope (IO) for receiver / listener work. */
    val scope: CoroutineScope,
    val clock: Clock,
    val db: ForwarderDatabase,
    val prefs: ForwarderPrefs,
    val keys: KeyManager,
    val secrets: SecretStore,
    val scheduler: WorkScheduler,
    val inbox: SmsInboxSource,
    simLabeler: SimLabeler,
    appLabeler: AppLabeler,
    val http: HttpClients,
) {
    val outbox = Outbox(db, keys, clock)
    val smsIngest = SmsIngest(prefs, secrets, outbox, db.dao(), simLabeler, scheduler, clock)
    val reconciler = SmsReconciler(prefs, inbox, smsIngest, clock)
    val notifications = NotificationIngest(prefs, outbox, scheduler, clock, appLabeler, smsIngest, context.packageName)
    val uploader = Uploader(db, outbox, prefs, secrets, http, scheduler, clock)

    /** Serializes enroll / unenroll / setting changes. */
    val mutations = Mutex()

    /** Unenroll: stop all work and delete every trace (token, queue, log, settings, keys). */
    suspend fun wipe() {
        scheduler.cancelAll()
        uploader.exclusive {
            notifications.reset()
            db.clearAllTables()
            prefs.clear()
            secrets.clear()
            keys.destroy()
        }
    }

    companion object {
        @Volatile
        private var instance: ForwarderGraph? = null

        fun get(context: Context): ForwarderGraph =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        /** Tests only: replace (or with null, forget) the process-wide graph. */
        fun install(graph: ForwarderGraph?) {
            instance = graph
        }

        private fun create(app: Context): ForwarderGraph {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val clock = Clock.systemDefaultZone()
            val keys = AndroidKeyManager(app)
            return ForwarderGraph(
                context = app,
                scope = scope,
                clock = clock,
                db = ForwarderDatabase.build(app),
                prefs = ForwarderPrefs(
                    PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile(ForwarderPrefs.FILE_NAME) },
                ),
                keys = keys,
                secrets = SecretStore(app.getSharedPreferences(SecretStore.PREFS_NAME, Context.MODE_PRIVATE), keys),
                scheduler = WorkManagerScheduler(app),
                inbox = ContentResolverInbox(app),
                simLabeler = AndroidSimLabeler(app),
                appLabeler = PackageManagerLabeler(app),
                http = HttpClients(HttpClients.defaultBase(), clock),
            )
        }
    }
}
