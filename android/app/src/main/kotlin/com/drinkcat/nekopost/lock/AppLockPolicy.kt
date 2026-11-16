package com.drinkcat.nekopost.lock

/** Why the app is locked; shown as a short line on the lock screen. */
enum class LockReason { ColdStart, Background, ScreenOff, Idle, Fold, Manual }

/** Whether the app lock can protect anything on this device right now. */
enum class Eligibility {
    /** Nothing enrolled or paired yet: there is nothing to protect, never lock. */
    NothingToProtect,

    /** No secure lock screen (PIN, pattern, password): a lock could never be opened. */
    NoSecureLock,

    Eligible,
}

/** What happens when the phone is folded shut while the inner display shows content (design §10.4 ⑥). */
enum class FoldCloseBehavior { Curtain, Lock, Off }

sealed interface LockState {
    /** Turned off in settings, or nothing to protect. */
    data object Disabled : LockState

    /** Wanted, but the device has no secure lock screen; the app explains this and stays usable. */
    data object Unavailable : LockState

    data object Unlocked : LockState

    data class Locked(val reason: LockReason) : LockState

    /** Folded shut from the inner display: content hidden behind the curtain until authenticated. */
    data object Curtain : LockState

    val hidesContent: Boolean get() = this is Locked || this == Curtain
}

data class LockSettings(
    val enabled: Boolean = true,
    /** How long the app may stay in the background before it locks; 0 = immediately. */
    val graceMillis: Long = 30_000,
    /** No-interaction timeout while the app is in front; null = never. */
    val idleMillis: Long? = 5 * 60_000L,
    val foldClose: FoldCloseBehavior = FoldCloseBehavior.Curtain,
)

/**
 * Activities the app starts itself. While one is in front the app goes to the
 * background, but that must neither lock it nor lose the result (the 0.2.1 bug:
 * the QR scanner's onStop locked the inbox and dropped the scan). Each kind has
 * a deadline, so a guard cannot become a bypass: someone who opens the system
 * settings from the app and walks away still gets a locked app.
 */
enum class ExternalKind(val deadlineMillis: Long) {
    QrScanner(5 * 60_000L),
    Biometric(2 * 60_000L),
    Permission(2 * 60_000L),
    SystemSettings(10 * 60_000L),
    Share(5 * 60_000L),

    /** Our own adjacent window: the process stays in front, so this only matters if both windows stop. */
    Adjacent(10 * 60_000L),
}

/**
 * The app lock as a pure state machine (no Android types), driven by the
 * process lifecycle, screen-off, interaction and fold signals. [clock] is a
 * monotonic millisecond clock (SystemClock.elapsedRealtime on the device).
 */
class AppLockPolicy(
    private val clock: () -> Long,
    settings: LockSettings = LockSettings(),
    eligibility: Eligibility = Eligibility.NothingToProtect,
) {
    var settings: LockSettings = settings
        private set
    var eligibility: Eligibility = eligibility
        private set
    var state: LockState
        private set

    /** Incremented on every lock; screens cancel pending dangerous confirmations when it changes. */
    var lockEpoch: Long = 0
        private set

    private var started = false
    private var stoppedAt: Long? = null
    private var stoppedGuards: List<Guard> = emptyList()
    private var lastInteraction: Long = clock()
    private val guards = mutableListOf<Guard>()
    private var nextGuardId = 1L

    private data class Guard(val id: Long, val kind: ExternalKind, val since: Long)

    init {
        // Cold start: the process starts locked whenever the lock applies.
        state = if (active) LockState.Locked(LockReason.ColdStart) else baseline()
    }

    private val active: Boolean get() = settings.enabled && eligibility == Eligibility.Eligible

    private fun baseline(): LockState = when {
        eligibility == Eligibility.NothingToProtect -> LockState.Disabled
        !settings.enabled -> LockState.Disabled
        eligibility == Eligibility.NoSecureLock -> LockState.Unavailable
        else -> LockState.Unlocked
    }

    /**
     * New settings or eligibility (screen lock added or removed, first pairing,
     * switch toggled). Becoming active while the user is using the app does not
     * lock it; losing the secure lock screen never traps the user.
     */
    fun update(settings: LockSettings, eligibility: Eligibility) {
        val wasActive = active
        this.settings = settings
        this.eligibility = eligibility
        if (!active) {
            state = baseline()
        } else if (!wasActive) {
            state = LockState.Unlocked
            lastInteraction = clock()
        }
    }

    fun onStart() {
        started = true
        val since = stoppedAt
        val guarded = stoppedGuards
        stoppedAt = null
        stoppedGuards = emptyList()
        if (since == null || !active || state.hidesContent) return
        val away = clock() - since
        val allowed = if (guarded.isNotEmpty()) {
            // An activity we started was in front: its own deadline replaces the grace period.
            guarded.maxOf { it.since + it.kind.deadlineMillis } - since
        } else {
            settings.graceMillis
        }
        val expired = if (guarded.isEmpty() && settings.graceMillis == 0L) true else away > allowed
        if (expired) lock(LockReason.Background) else lastInteraction = clock()
    }

    fun onStop() {
        started = false
        val now = clock()
        stoppedAt = now
        stoppedGuards = guards.filter { now - it.since <= it.kind.deadlineMillis }
    }

    /** One of our activities resumed: whatever external activity we started has returned. */
    fun onResume() {
        guards.clear()
    }

    /** The screen went off: lock at once, even during an external flow (its result is kept, not dropped). */
    fun onScreenOff() {
        if (active && !state.hidesContent) lock(LockReason.ScreenOff)
    }

    fun touch() {
        lastInteraction = clock()
    }

    /**
     * The 值守台 (§10.4 ④) is meant to sit unattended on a table: while it is
     * shown the idle timeout is paused (it never reveals a code without
     * authentication). Leaving it restarts the idle clock.
     */
    var idleSuspended: Boolean = false
        set(value) {
            field = value
            lastInteraction = clock()
        }

    /** Called every second while the app is in front. */
    fun tick() {
        if (!started || !active || state != LockState.Unlocked || idleSuspended) return
        val idle = settings.idleMillis ?: return
        val now = clock()
        if (guards.any { now - it.since <= it.kind.deadlineMillis }) return
        if (now - lastInteraction >= idle) lock(LockReason.Idle)
    }

    /** Folded from the inner display to the cover display. */
    fun onFoldClosed() {
        if (!active || state != LockState.Unlocked) return
        when (settings.foldClose) {
            FoldCloseBehavior.Curtain -> {
                state = LockState.Curtain
                lockEpoch++
            }
            FoldCloseBehavior.Lock -> lock(LockReason.Fold)
            FoldCloseBehavior.Off -> Unit
        }
    }

    fun lockNow(reason: LockReason = LockReason.Manual) {
        if (active && !state.hidesContent) lock(reason)
    }

    fun unlocked() {
        if (!active) return
        state = LockState.Unlocked
        lastInteraction = clock()
    }

    /** Registers an activity we are about to start; returns a guard ID for [release]. */
    fun expectExternal(kind: ExternalKind): Long {
        val id = nextGuardId++
        guards += Guard(id, kind, clock())
        return id
    }

    /** The external activity delivered its result (or failed to start). */
    fun release(id: Long) {
        guards.removeAll { it.id == id }
    }

    val hasActiveGuard: Boolean get() = guards.any { clock() - it.since <= it.kind.deadlineMillis }

    private fun lock(reason: LockReason) {
        state = LockState.Locked(reason)
        lockEpoch++
    }
}
