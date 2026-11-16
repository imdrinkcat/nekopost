package com.drinkcat.nekopost.forwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.drinkcat.nekopost.forwarder.ForwarderGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real-time SMS (design §8.4). Statically registered for SMS_RECEIVED and
 * protected by BROADCAST_SMS, so only the system can deliver to it. Parses and
 * merges the PDUs on the main thread, then persists to the outbox under
 * goAsync() and schedules an expedited upload; well under a second.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val graph = ForwarderGraph.get(context)
        val receivedAt = graph.clock.instant()
        val messages = parse(intent)
        if (messages.isEmpty()) return
        val pending: PendingResult? = goAsync()
        graph.scope.launch {
            try {
                withTimeoutOrNull(BUDGET_MILLIS) { graph.smsIngest.onBroadcast(messages, receivedAt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The inbox scan picks the message up later.
                Log.w(TAG, "SMS capture failed: ${e.javaClass.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }

    internal companion object {
        private const val TAG = "NekoSms"

        /** goAsync() allows about 10 s; stay well below. */
        private const val BUDGET_MILLIS = 8_000L

        /** PDUs of one broadcast → complete messages (multipart merged per sender and SIM). */
        fun parse(intent: Intent): List<IncomingSms> {
            val messages = try {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)
            } catch (e: RuntimeException) {
                null
            } ?: return emptyList()
            val subId = subscriptionId(intent)
            val parts = messages.filterNotNull().mapNotNull { sms ->
                val address = sms.displayOriginatingAddress ?: sms.originatingAddress ?: return@mapNotNull null
                SmsPart(
                    address = address,
                    subId = subId,
                    body = sms.displayMessageBody ?: sms.messageBody ?: "",
                    timestampMillis = sms.timestampMillis,
                )
            }
            return SmsMerger.merge(parts)
        }

        fun subscriptionId(intent: Intent): Int = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID),
        )
    }
}
