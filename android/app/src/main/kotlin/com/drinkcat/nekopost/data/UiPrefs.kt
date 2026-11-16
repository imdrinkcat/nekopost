package com.drinkcat.nekopost.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-only preferences. Nothing secret lives here (no tokens, no message content). */
data class UiPrefsState(
    /** The setup wizard was finished at least once. */
    val setupCompleted: Boolean = false,
    /** Wizard steps the user confirmed by hand because the system cannot report them (names of SetupStep). */
    val acknowledgedSteps: Set<String> = emptySet(),
)

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
    )

    fun setSetupCompleted(done: Boolean) {
        prefs.edit { putBoolean(KEY_SETUP_COMPLETED, done) }
        _state.value = _state.value.copy(setupCompleted = done)
    }

    fun acknowledge(step: String) {
        val steps = _state.value.acknowledgedSteps + step
        prefs.edit { putStringSet(KEY_ACK, steps) }
        _state.value = _state.value.copy(acknowledgedSteps = steps)
    }

    /** Called after unenrolling: setup starts over. */
    fun resetSetup() {
        prefs.edit {
            putBoolean(KEY_SETUP_COMPLETED, false)
            remove(KEY_ACK)
        }
        _state.value = _state.value.copy(setupCompleted = false, acknowledgedSteps = emptySet())
    }

    private companion object {
        const val KEY_SETUP_COMPLETED = "setup_completed"
        const val KEY_ACK = "setup_acknowledged"
    }
}
