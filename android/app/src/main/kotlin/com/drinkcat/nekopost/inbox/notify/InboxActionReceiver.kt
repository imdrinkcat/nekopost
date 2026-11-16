package com.drinkcat.nekopost.inbox.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.drinkcat.nekopost.NekopostApp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.inbox.ApiFailure
import com.drinkcat.nekopost.inbox.messagePattern
import com.drinkcat.nekopost.system.SensitiveClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Notification actions (non-exported; the PendingIntent carries only the
 * message ID). 复制验证码 needs the device to be unlocked
 * (setAuthenticationRequired) and reads the code from the encrypted cache,
 * without network; 标为已读 clears the notification at once and sends the
 * read state through WorkManager.
 */
class InboxActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf(messagePattern::matches) ?: return
        val app = context.applicationContext as? NekopostApp ?: return
        when (intent.action) {
            ACTION_READ -> {
                InboxNotifier.cancel(context, id)
                InboxReadWorker.enqueue(context, id)
            }
            ACTION_COPY -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val item = withTimeoutOrNull(8_000) { app.container.inbox.cachedDetail(id) }
                        val code = item?.otp
                        if (item != null && code != null) {
                            SensitiveClipboard.copy(context, context.getString(R.string.inbox_code_clip_label), code)
                            InboxNotifier.post(context, item, app.container.uiPrefs.state.value.notificationContent, copied = true)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_COPY = "com.drinkcat.nekopost.action.COPY_CODE"
        const val ACTION_READ = "com.drinkcat.nekopost.action.MARK_READ"
        const val EXTRA_MESSAGE_ID = "com.drinkcat.nekopost.MESSAGE_ID"
    }
}

/** PATCH read=true for a message marked read from its notification. */
class InboxReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id")?.takeIf(messagePattern::matches) ?: return Result.failure()
        val inbox = (applicationContext as NekopostApp).container.inbox
        if (!inbox.paired()) return Result.success()
        return try {
            inbox.manage("PATCH", listOf("v1", "inbox", id), JSONObject().put("read", true))
            inbox.readElsewhere(listOf(id))
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            if (e.status >= 500) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<InboxReadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("id" to id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("nekopost-read-$id", ExistingWorkPolicy.KEEP, request)
        }
    }
}
