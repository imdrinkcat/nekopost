package com.drinkcat.nekopost.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.drinkcat.nekopost.lock.FoldCloseBehavior
import com.drinkcat.nekopost.lock.LockSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-only preferences. Nothing secret lives here (no tokens, no message content). */
data class UiPrefsState(
    /** The setup wizard was finished at least once. */
    val setupCompleted: Boolean = false,
    /** Wizard steps the user confirmed by hand because the system cannot report them (names of SetupStep). */
    val acknowledgedSteps: Set<String> = emptySet(),
    /** 进入应用时验证身份. */
    val appLockEnabled: Boolean = true,
    /** 离开应用后锁定: seconds in the background before locking (0 = immediately). */
    val lockGraceSeconds: Int = 30,
    /** 无操作自动锁定: minutes without interaction (0 = never). */
    val idleLockMinutes: Int = 5,
    val foldClose: FoldCloseBehavior = FoldCloseBehavior.Curtain,
    /** Something worth protecting has existed on this phone (enrolled or paired); survives restarts. */
    val lockArmed: Boolean = false,
    /** 通知显示消息内容 (sender, preview and verification code); off = 「来源 · 类别」 only. */
    val notificationContent: Boolean = true,
    /** 展开时开信封 (design §10.4 ⑫). */
    val envelope: Boolean = true,
    /** The "no screen lock" banner was dismissed. */
    val insecureBannerDismissed: Boolean = false,
) {
    val lockSettings: LockSettings
        get() = LockSettings(
            enabled = appLockEnabled,
            graceMillis = lockGraceSeconds * 1000L,
            idleMillis = idleLockMinutes.takeIf { it > 0 }?.let { it * 60_000L },
            foldClose = foldClose,
        )

    companion object {
        val GraceChoices = listOf(0, 30, 60, 300)
        val IdleChoices = listOf(1, 5, 15, 0)
    }
}

/**
 * Small SharedPreferences-backed store, exposed as a StateFlow. SMS sender
 * rules live in the forwarder (ForwarderController.setSmsSenderRules).
 */
class UiPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<UiPrefsState> = _state.asStateFlow()

    private fun read() = UiPrefsState(
        setupCompleted = prefs.getBoolean(KEY_SETUP_COMPLETED, false),
        acknowledgedSteps = prefs.getStringSet(KEY_ACK, emptySet()).orEmpty().toSet(),
        appLockEnabled = prefs.getBoolean(KEY_LOCK, true),
        lockGraceSeconds = prefs.getInt(KEY_GRACE, 30).takeIf { it in UiPrefsState.GraceChoices } ?: 30,
        idleLockMinutes = prefs.getInt(KEY_IDLE, 5).takeIf { it in UiPrefsState.IdleChoices } ?: 5,
        foldClose = FoldCloseBehavior.entries.firstOrNull { it.name == prefs.getString(KEY_FOLD, null) } ?: FoldCloseBehavior.Curtain,
        // Upgrades from 0.2.x had no flag: a finished wizard means a forwarder was set up.
        lockArmed = prefs.getBoolean(KEY_ARMED, false) || prefs.getBoolean(KEY_SETUP_COMPLETED, false),
        notificationContent = prefs.getBoolean(KEY_NOTIFY_CONTENT, true),
        envelope = prefs.getBoolean(KEY_ENVELOPE, true),
        insecureBannerDismissed = prefs.getBoolean(KEY_BANNER, false),
    )

    private fun update(edit: SharedPreferences.Editor.() -> Unit, change: (UiPrefsState) -> UiPrefsState) {
        prefs.edit(action = edit)
        _state.value = change(_state.value)
    }

    fun setSetupCompleted(done: Boolean) = update({ putBoolean(KEY_SETUP_COMPLETED, done) }) {
        it.copy(setupCompleted = done, lockArmed = it.lockArmed || done)
    }

    fun acknowledge(step: String) {
        val steps = _state.value.acknowledgedSteps + step
        update({ putStringSet(KEY_ACK, steps) }) { it.copy(acknowledgedSteps = steps) }
    }

    fun setAppLockEnabled(on: Boolean) = update({ putBoolean(KEY_LOCK, on) }) { it.copy(appLockEnabled = on) }

    fun setLockGraceSeconds(seconds: Int) = update({ putInt(KEY_GRACE, seconds) }) { it.copy(lockGraceSeconds = seconds) }

    fun setIdleLockMinutes(minutes: Int) = update({ putInt(KEY_IDLE, minutes) }) { it.copy(idleLockMinutes = minutes) }

    fun setFoldClose(behavior: FoldCloseBehavior) = update({ putString(KEY_FOLD, behavior.name) }) { it.copy(foldClose = behavior) }

    fun setNotificationContent(on: Boolean) = update({ putBoolean(KEY_NOTIFY_CONTENT, on) }) { it.copy(notificationContent = on) }

    fun setEnvelope(on: Boolean) = update({ putBoolean(KEY_ENVELOPE, on) }) { it.copy(envelope = on) }

    fun dismissInsecureBanner() = update({ putBoolean(KEY_BANNER, true) }) { it.copy(insecureBannerDismissed = true) }

    /** Something to protect now exists on this phone. */
    fun arm() {
        if (!_state.value.lockArmed) update({ putBoolean(KEY_ARMED, true) }) { it.copy(lockArmed = true) }
    }

    /** Called after unenrolling: setup starts over. */
    fun resetSetup() = update({
        putBoolean(KEY_SETUP_COMPLETED, false)
        remove(KEY_ACK)
    }) { it.copy(setupCompleted = false, acknowledgedSteps = emptySet()) }

    /** Nothing left to protect (neither enrolled nor paired): forget the lock and display choices too (§8.9). */
    fun clearAll() {
        prefs.edit { clear() }
        _state.value = UiPrefsState()
    }

    private companion object {
        const val KEY_SETUP_COMPLETED = "setup_completed"
        const val KEY_ACK = "setup_acknowledged"
        const val KEY_LOCK = "app_lock"
        const val KEY_GRACE = "lock_grace_s"
        const val KEY_IDLE = "lock_idle_min"
        const val KEY_FOLD = "lock_fold"
        const val KEY_ARMED = "lock_armed"
        const val KEY_NOTIFY_CONTENT = "notify_content"
        const val KEY_ENVELOPE = "envelope"
        const val KEY_BANNER = "insecure_banner_dismissed"
    }
}
