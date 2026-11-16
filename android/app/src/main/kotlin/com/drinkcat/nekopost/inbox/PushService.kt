package com.drinkcat.nekopost.inbox

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.drinkcat.nekopost.BuildConfig
import com.drinkcat.nekopost.NekopostApp
import com.drinkcat.nekopost.inbox.notify.InboxNotifier
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal object PushRegistration {
    @Synchronized
    fun enable(context: Context) {
        if (BuildConfig.FIREBASE_APP_ID.isBlank() || BuildConfig.FIREBASE_PROJECT_ID.isBlank() ||
            BuildConfig.FIREBASE_API_KEY.isBlank() || BuildConfig.FIREBASE_SENDER_ID.isBlank()
        ) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build())
        }
        FirebaseMessaging.getInstance().apply {
            isAutoInitEnabled = true
            token.addOnSuccessListener { enqueue(context, "registration") }
        }
        val refresh = PeriodicWorkRequestBuilder<DevicePushWorker>(30, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("kind" to "registration")).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("nekopost-fcm-refresh",
            ExistingPeriodicWorkPolicy.KEEP, refresh)
    }

    @Synchronized
    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("nekopost-fcm-refresh")
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().apply {
            isAutoInitEnabled = false
            deleteToken()
        }
    }

    fun enqueue(context: Context, kind: String, expedited: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<DevicePushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("kind" to kind))
        // Expedited work before Android 12 runs as a foreground service (design: none), so only from 12 on.
        if (expedited && Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork("nekopost-push-$kind", ExistingWorkPolicy.KEEP, builder.build())
    }
}

/**
 * FCM data messages (IDs only, design §6). A new message is fetched right here
 * (onMessageReceived may run for about 20 s) so its notification appears at
 * once on the right channel; only if that takes longer than 8 s does a quiet
 * placeholder appear, replaced by expedited work. `read` / `gone` clear the
 * notifications of messages read or deleted elsewhere.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRegistration.enqueue(this, "registration")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["v"] != "1") return
        val app = application as? NekopostApp ?: return
        when (data["t"]) {
            "ping" -> InboxNotifier.postTest(this)
            "revoked" -> PushRegistration.enqueue(this, "revoked")
            "read", "gone" -> {
                val ids = data["ids"].orEmpty().split(",").map(String::trim).filter(messagePattern::matches).take(50)
                ids.forEach { InboxNotifier.cancel(this, it) }
                if (data["t"] == "read") app.container.inbox.readElsewhere(ids)
            }
            "msg" -> {
                val id = data["id"]?.takeIf(messagePattern::matches) ?: return
                val fetched = runBlocking {
                    withTimeoutOrNull(8_000) {
                        try {
                            Fetch.Done(app.container.inbox.messageForPush(id))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Fetch.Failed
                        }
                    }
                }
                when (fetched) {
                    is Fetch.Done -> show(this, id, fetched.item)
                    else -> {
                        InboxNotifier.postPlaceholder(this, id)
                        PushRegistration.enqueue(this, id, expedited = true)
                    }
                }
            }
        }
    }

    private sealed interface Fetch {
        data class Done(val item: InboxMessage?) : Fetch
        data object Failed : Fetch
    }
}

/** Posts or clears the notification of a fetched message (null: gone or revoked). */
private fun show(context: Context, id: String, item: InboxMessage?) {
    if (item == null || item.read) {
        InboxNotifier.cancel(context, id)
        return
    }
    val prefs = (context.applicationContext as NekopostApp).container.uiPrefs.state.value
    InboxNotifier.post(context, item, prefs.notificationContent)
}

class DevicePushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.failure()
        val inbox = (applicationContext as NekopostApp).container.inbox
        if (!inbox.paired()) {
            if (messagePattern.matches(kind)) InboxNotifier.cancel(applicationContext, kind)
            return Result.success()
        }
        if (kind == "revoked") {
            inbox.verifySelf()
            if (!inbox.paired()) {
                PushRegistration.disable(applicationContext)
                NotificationManagerCompat.from(applicationContext).cancelAll()
            }
            return Result.success()
        }
        if (kind == "registration") {
            if (FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()
            val token = try {
                kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (continuation.isActive) {
                            val value = if (task.isSuccessful) task.result else null
                            continuation.resumeWith(if (!value.isNullOrBlank()) kotlin.Result.success(value)
                                else kotlin.Result.failure(task.exception ?: IllegalStateException()))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return Result.retry()
            }
            return try {
                if (inbox.registerPush(token)) Result.success() else Result.retry()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.retry()
            }
        }
        if (!messagePattern.matches(kind)) return Result.failure()
        val item = try {
            inbox.messageForPush(kind)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
        show(applicationContext, kind, item)
        return Result.success()
    }
}
