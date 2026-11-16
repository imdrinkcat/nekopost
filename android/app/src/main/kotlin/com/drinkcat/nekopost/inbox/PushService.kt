package com.drinkcat.nekopost.inbox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.drinkcat.nekopost.BuildConfig
import com.drinkcat.nekopost.MainActivity
import com.drinkcat.nekopost.NekopostApp
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
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

    fun enqueue(context: Context, kind: String) {
        val request = OneTimeWorkRequestBuilder<DevicePushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("kind" to kind)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("nekopost-push-$kind", ExistingWorkPolicy.KEEP, request)
    }
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRegistration.enqueue(this, "registration")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["v"] != "1") return
        if (message.data["t"] == "ping") {
            showInboxNotice(this, "nekopost-test-push", null)
            return
        }
        if (message.data["t"] == "revoked") {
            PushRegistration.enqueue(this, "revoked")
            return
        }
        if (message.data["t"] != "msg") return
        val id = message.data["id"] ?: return
        if (!messagePattern.matches(id)) return
        // A generic notification lets the user see the push even without a data connection.
        showInboxNotice(this, id, null)
        PushRegistration.enqueue(this, id)
    }
}

class DevicePushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.failure()
        val inbox = (applicationContext as NekopostApp).container.inbox
        if (!inbox.paired()) return Result.success()
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
        if (item?.read == true || !inbox.paired()) {
            NotificationManagerCompat.from(applicationContext).cancel(kind, 1)
        } else if (item != null) showInboxNotice(applicationContext, kind, item)
        return Result.success()
    }
}

private fun showInboxNotice(context: Context, id: String, item: InboxMessage?) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val channel = if (item?.hasCode == true) "inbox_codes" else "inbox_messages"
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(channel,
        if (item?.hasCode == true) "验证码" else "消息",
        if (item?.hasCode == true) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val publicVersion = NotificationCompat.Builder(context, channel)
        .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("猫邮局")
        .setContentText(if (item?.hasCode == true) "收到验证码" else "收到新消息").build()
    val text = item?.let { listOf(it.source, it.category).filter(String::isNotBlank).joinToString(" · ") }
        ?.takeIf(String::isNotBlank) ?: "收到新消息"
    val note = NotificationCompat.Builder(context, channel)
        .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("猫邮局").setContentText(text)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
        .setContentIntent(open).setAutoCancel(true).setLocalOnly(true).setOnlyAlertOnce(true).build()
    try {
        NotificationManagerCompat.from(context).notify(id, 1, note)
    } catch (_: SecurityException) {
        // Permission can be revoked between the check and post.
    }
}
