package com.drinkcat.nekopost.forwarder.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.forwarder.ForwarderGraph
import kotlinx.coroutines.CancellationException

/** Drains the outbox. Retries are scheduled by the uploader itself (honouring Retry-After). */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ForwarderGraph.get(applicationContext).uploader.drain()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "upload run failed: ${e.javaClass.simpleName}")
        Result.retry()
    }

    private companion object {
        const val TAG = "NekoUpload"
    }
}

/**
 * Inbox scan + retention + flush. Runs once at boot / app start and hourly
 * with network (design §8.4, §8.9). Also the only place that re-tries a
 * source the server disabled (one request per hour).
 */
class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val graph = ForwarderGraph.get(applicationContext)
        graph.reconciler.run()
        graph.outbox.prune()
        val paused = graph.prefs.current().paused
        graph.uploader.drain(ignorePause = paused == PauseReason.SourceDisabled)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "reconcile run failed: ${e.javaClass.simpleName}")
        Result.retry()
    }

    private companion object {
        const val TAG = "NekoReconcile"
    }
}
