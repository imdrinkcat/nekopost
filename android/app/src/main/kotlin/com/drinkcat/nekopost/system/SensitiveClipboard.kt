package com.drinkcat.nekopost.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Copies verification codes and other secrets (design §8.9): the clip is marked
 * sensitive (hidden from the keyboard's clipboard preview) and cleared after
 * 60 seconds if it is still ours. Used by the inbox, the cover-screen code card
 * and the notification action.
 */
object SensitiveClipboard {
    const val CLEAR_AFTER_MS = 60_000L

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        val stamp = runCatching { clipboard.primaryClipDescription?.timestamp }.getOrNull() ?: return
        // In front: a Handler is exact. From a notification action the process may die,
        // so WorkManager runs the same check later.
        Handler(Looper.getMainLooper()).postDelayed({ clearIfOurs(context, stamp) }, CLEAR_AFTER_MS)
        runCatching {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ClipboardClearWorker>()
                    .setInitialDelay(CLEAR_AFTER_MS, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("stamp" to stamp))
                    .build(),
            )
        }
    }

    /** Clears only when the current clip is still the one we set (the system may hide it from background apps). */
    fun clearIfOurs(context: Context, stamp: Long) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val current = runCatching { clipboard.primaryClipDescription?.timestamp }.getOrNull() ?: return
        if (current == stamp) runCatching { clipboard.clearPrimaryClip() }
    }
}

class ClipboardClearWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val stamp = inputData.getLong("stamp", 0L)
        if (stamp > 0) SensitiveClipboard.clearIfOurs(applicationContext, stamp)
        return Result.success()
    }
}
