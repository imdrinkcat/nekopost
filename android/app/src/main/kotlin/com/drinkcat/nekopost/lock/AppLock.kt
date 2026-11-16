package com.drinkcat.nekopost.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.drinkcat.nekopost.data.UiPrefs
import com.drinkcat.nekopost.system.ExternalGuards
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the UI renders; [epoch] changes on every lock. */
data class LockUiState(val state: LockState, val epoch: Long)

/**
 * The app-level lock (requirement: 进入应用时开启密码). One instance per process,
 * in AppContainer. It observes the process lifecycle (an injected
 * LifecycleOwner; ProcessLifecycleOwner in the app), the screen turning off,
 * user interaction and fold changes, and holds [AppLockPolicy].
 *
 * Eligibility: the switch is on, something is enrolled or paired ([UiPrefs]
 * `lockArmed`, read synchronously so a cold start is locked before anything is
 * drawn), and the device has a secure lock screen ([secure]).
 */
class AppLock(
    private val prefs: UiPrefs,
    private val secure: () -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val policy = AppLockPolicy(clock, prefs.state.value.lockSettings, eligibility())
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<LockUiState> = _state.asStateFlow()
    private var ticker: Job? = null

    private fun eligibility(): Eligibility {
        val p = prefs.state.value
        return when {
            !p.lockArmed -> Eligibility.NothingToProtect
            !secure() -> Eligibility.NoSecureLock
            else -> Eligibility.Eligible
        }
    }

    private fun snapshot() = LockUiState(policy.state, policy.lockEpoch)

    @Synchronized
    private fun drive(action: AppLockPolicy.() -> Unit) {
        policy.action()
        val next = snapshot()
        if (next != _state.value) _state.value = next
    }

    /** Re-reads settings and eligibility (preference change, pairing, screen lock added or removed). */
    fun refresh() = drive { update(prefs.state.value.lockSettings, eligibility()) }

    /** Wires the lifecycle and the preference flow; [scope] runs on the main thread. */
    fun attach(owner: LifecycleOwner, scope: CoroutineScope) {
        ExternalGuards.sink = { kind ->
            val id = synchronized(this) { policy.expectExternal(kind) }
            val done: () -> Unit = { drive { release(id) } }
            done
        }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refresh()
                drive { onStart() }
                ticker?.cancel()
                ticker = scope.launch {
                    while (isActive) {
                        delay(1_000)
                        drive { tick() }
                    }
                }
            }

            override fun onResume(owner: LifecycleOwner) = activityResumed()

            override fun onStop(owner: LifecycleOwner) {
                ticker?.cancel()
                ticker = null
                drive { onStop() }
            }
        })
        scope.launch { prefs.state.drop(1).collect { refresh() } }
    }

    /** Context-registered: ACTION_SCREEN_OFF is not delivered to manifest receivers. */
    fun registerScreenOff(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) drive { onScreenOff() }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** One of our activities resumed: external activities we started have returned. */
    fun activityResumed() = drive { onResume() }

    fun touch() = synchronized(this) { policy.touch() }

    fun foldClosed() = drive { onFoldClosed() }

    /** The 值守台 is shown: pause the idle timeout (screen-off and leaving the app still lock). */
    fun suspendIdle(on: Boolean) = drive { idleSuspended = on }

    fun lockNow() = drive { lockNow(LockReason.Manual) }

    fun unlocked() = drive { unlocked() }

    /** Something to protect now exists (enrolled or paired): arm the lock without locking the present user. */
    fun arm() {
        prefs.arm()
        refresh()
    }
}
