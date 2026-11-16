package com.drinkcat.nekopost.forwarder.work

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/** Background scheduling; faked in tests. */
internal interface WorkScheduler {
    /** Upload as soon as there is network (expedited where available). */
    fun uploadNow()

    /** Upload after [delay] (retry backoff / Retry-After). */
    fun uploadAfter(delay: Duration)

    /** One inbox scan now (boot, app start). */
    fun reconcileNow()

    /** Hourly inbox scan + flush while enrolled (design §8.9). */
    fun ensurePeriodic()

    fun cancelAll()
}

/**
 * WorkManager implementation. No foreground service anywhere: SMS wake us via
 * the broadcast, uploads and scans run as (expedited) work with a network
 * constraint, so nothing polls.
 */
internal class WorkManagerScheduler(private val context: Context) : WorkScheduler {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun uploadNow() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(network)
            .apply {
                // Below Android 12 expedited work runs as a foreground service, which we do not have.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        workManager.enqueueUniqueWork(UPLOAD, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun uploadAfter(delay: Duration) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(network)
            .setInitialDelay(delay)
            .build()
        workManager.enqueueUniqueWork(UPLOAD_LATER, ExistingWorkPolicy.REPLACE, request)
    }

    override fun reconcileNow() {
        val request = OneTimeWorkRequestBuilder<ReconcileWorker>().build()
        workManager.enqueueUniqueWork(RECONCILE, ExistingWorkPolicy.KEEP, request)
    }

    override fun ensurePeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<ReconcileWorker>(Duration.ofHours(1))
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancelAll() {
        for (name in listOf(UPLOAD, UPLOAD_LATER, RECONCILE, PERIODIC)) workManager.cancelUniqueWork(name)
    }

    private companion object {
        const val UPLOAD = "nekopost-upload"
        const val UPLOAD_LATER = "nekopost-upload-later"
        const val RECONCILE = "nekopost-reconcile"
        const val PERIODIC = "nekopost-reconcile-hourly"
    }
}
