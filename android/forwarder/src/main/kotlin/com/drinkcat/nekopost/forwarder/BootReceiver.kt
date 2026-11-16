package com.drinkcat.nekopost.forwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * After boot or an app update: one inbox scan (catches SMS received before the
 * first unlock) and a flush. Both actions are protected broadcasts that only
 * the system can send; anything else is ignored and no extras are read.
 * (ACTION_USER_UNLOCKED is only delivered to runtime receivers; BOOT_COMPLETED
 * arrives after the first unlock anyway.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val graph = ForwarderGraph.get(context)
        val pending: PendingResult? = goAsync()
        graph.scope.launch {
            try {
                if (graph.prefs.current().enrollment != null) {
                    // SMS received before this point could not reach SmsReceiver (OTP health).
                    graph.prefs.recordRestart(graph.clock.instant())
                    graph.scheduler.ensurePeriodic()
                    graph.scheduler.reconcileNow()
                    graph.scheduler.uploadNow()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("NekoBoot", "scheduling failed: ${e.javaClass.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }
}
