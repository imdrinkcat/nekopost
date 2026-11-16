package com.drinkcat.nekopost.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app lock state machine on a fake clock (no Android). */
class AppLockPolicyTest {
    private var now = 1_000_000L
    private val clock = { now }

    private fun advance(ms: Long) {
        now += ms
    }

    private fun policy(settings: LockSettings = LockSettings(), eligibility: Eligibility = Eligibility.Eligible) =
        AppLockPolicy(clock, settings, eligibility)

    /** Cold start, then the user unlocks and the app is in front. */
    private fun unlockedInFront(settings: LockSettings = LockSettings()): AppLockPolicy {
        val p = policy(settings)
        p.onStart()
        p.onResume()
        p.unlocked()
        assertEquals(LockState.Unlocked, p.state)
        return p
    }

    private fun goBackground(p: AppLockPolicy, away: Long) {
        p.onStop()
        advance(away)
        p.onStart()
    }

    @Test
    fun coldStartLocksOnlyWhenThereIsSomethingToProtect() {
        assertEquals(LockState.Locked(LockReason.ColdStart), policy().state)
        assertEquals(LockState.Disabled, policy(eligibility = Eligibility.NothingToProtect).state)
        assertEquals(LockState.Disabled, policy(LockSettings(enabled = false)).state)
    }

    @Test
    fun noSecureLockScreenIsUnavailableAndNeverLocks() {
        val p = policy(eligibility = Eligibility.NoSecureLock)
        assertEquals(LockState.Unavailable, p.state)
        p.onStart()
        p.onScreenOff()
        goBackground(p, 10 * 60_000L)
        p.tick()
        p.lockNow()
        assertEquals(LockState.Unavailable, p.state)
        assertFalse(p.state.hidesContent)
    }

    @Test
    fun shortTripStaysUnlockedLongTripLocks() {
        val p = unlockedInFront()
        goBackground(p, 29_000)
        assertEquals(LockState.Unlocked, p.state)
        goBackground(p, 31_000)
        assertEquals(LockState.Locked(LockReason.Background), p.state)
    }

    @Test
    fun immediateGraceLocksOnAnyStop() {
        val p = unlockedInFront(LockSettings(graceMillis = 0))
        goBackground(p, 100)
        assertEquals(LockState.Locked(LockReason.Background), p.state)
    }

    @Test
    fun screenOffLocksAtOnceEvenDuringAnExternalFlow() {
        val p = unlockedInFront()
        p.expectExternal(ExternalKind.QrScanner)
        p.onStop()
        p.onScreenOff()
        assertEquals(LockState.Locked(LockReason.ScreenOff), p.state)
    }

    @Test
    fun idleTimeoutLocksAndInteractionResetsIt() {
        val p = unlockedInFront(LockSettings(idleMillis = 60_000))
        advance(50_000)
        p.touch()
        advance(50_000)
        p.tick()
        assertEquals(LockState.Unlocked, p.state)
        advance(11_000)
        p.tick()
        assertEquals(LockState.Locked(LockReason.Idle), p.state)
    }

    @Test
    fun idleNeverAndWatchDeskSuspendIdle() {
        val never = unlockedInFront(LockSettings(idleMillis = null))
        advance(60 * 60_000L)
        never.tick()
        assertEquals(LockState.Unlocked, never.state)

        val watch = unlockedInFront(LockSettings(idleMillis = 60_000))
        watch.idleSuspended = true
        advance(10 * 60_000L)
        watch.tick()
        assertEquals(LockState.Unlocked, watch.state)
        // Leaving the watch desk restarts the idle clock rather than locking at once.
        watch.idleSuspended = false
        watch.tick()
        assertEquals(LockState.Unlocked, watch.state)
        advance(61_000)
        watch.tick()
        assertEquals(LockState.Locked(LockReason.Idle), watch.state)
    }

    // ------------------------------------------------ 扫码的时候不能被打断

    @Test
    fun scannerRoundTripDoesNotLock() {
        val p = unlockedInFront()
        val guard = p.expectExternal(ExternalKind.QrScanner)
        p.onStop()
        advance(3 * 60_000L)
        p.onStart()
        assertEquals(LockState.Unlocked, p.state)
        p.release(guard)
        p.onResume()
        // The guard is spent: the next ordinary trip counts again.
        goBackground(p, 60_000)
        assertEquals(LockState.Locked(LockReason.Background), p.state)
    }

    @Test
    fun scannerLeftOpenPastItsDeadlineLocks() {
        val p = unlockedInFront()
        p.expectExternal(ExternalKind.QrScanner)
        p.onStop()
        advance(5 * 60_000L + 1)
        p.onStart()
        assertEquals(LockState.Locked(LockReason.Background), p.state)
    }

    @Test
    fun eachKindHasItsOwnDeadline() {
        for (kind in listOf(ExternalKind.Biometric, ExternalKind.Permission, ExternalKind.SystemSettings, ExternalKind.Share)) {
            val inside = unlockedInFront()
            inside.expectExternal(kind)
            goBackground(inside, kind.deadlineMillis - 1_000)
            assertEquals("$kind inside its deadline", LockState.Unlocked, inside.state)

            val past = unlockedInFront()
            past.expectExternal(kind)
            goBackground(past, kind.deadlineMillis + 1_000)
            assertEquals("$kind past its deadline", LockState.Locked(LockReason.Background), past.state)
        }
        assertTrue(ExternalKind.SystemSettings.deadlineMillis > ExternalKind.Permission.deadlineMillis)
    }

    @Test
    fun settingsPageAndPermissionDialogRoundTripsKeepTheWizardUnlocked() {
        // The setup wizard: a permission dialog, then the battery settings page, then back.
        val p = unlockedInFront(LockSettings(graceMillis = 0))
        val dialog = p.expectExternal(ExternalKind.Permission)
        goBackground(p, 20_000)
        p.release(dialog)
        p.onResume()
        assertEquals(LockState.Unlocked, p.state)
        p.expectExternal(ExternalKind.SystemSettings)
        goBackground(p, 4 * 60_000L)
        p.onResume()
        assertEquals(LockState.Unlocked, p.state)
    }

    @Test
    fun guardAlsoPausesTheIdleTimeout() {
        val p = unlockedInFront(LockSettings(idleMillis = 60_000))
        val guard = p.expectExternal(ExternalKind.Biometric)
        advance(90_000)
        p.tick()
        assertEquals(LockState.Unlocked, p.state)
        p.release(guard)
        p.tick()
        assertEquals(LockState.Locked(LockReason.Idle), p.state)
    }

    @Test
    fun guardRegisteredAfterStopDoesNotExcuseTheTrip() {
        val p = unlockedInFront()
        p.onStop()
        p.expectExternal(ExternalKind.SystemSettings)
        advance(60_000)
        p.onStart()
        assertEquals(LockState.Locked(LockReason.Background), p.state)
    }

    // ------------------------------------------------ fold

    @Test
    fun foldingShutCurtainsLocksOrDoesNothing() {
        val curtain = unlockedInFront()
        curtain.onFoldClosed()
        assertEquals(LockState.Curtain, curtain.state)
        assertTrue(curtain.state.hidesContent)

        val lock = unlockedInFront(LockSettings(foldClose = FoldCloseBehavior.Lock))
        lock.onFoldClosed()
        assertEquals(LockState.Locked(LockReason.Fold), lock.state)

        val off = unlockedInFront(LockSettings(foldClose = FoldCloseBehavior.Off))
        off.onFoldClosed()
        assertEquals(LockState.Unlocked, off.state)
    }

    @Test
    fun unlockAndEpoch() {
        val p = unlockedInFront()
        val before = p.lockEpoch
        p.onScreenOff()
        assertEquals(before + 1, p.lockEpoch)
        p.unlocked()
        assertEquals(LockState.Unlocked, p.state)
        p.onFoldClosed()
        assertEquals(before + 2, p.lockEpoch)
    }

    // ------------------------------------------------ settings and eligibility

    @Test
    fun firstPairingArmsWithoutLockingThePresentUser() {
        val p = policy(eligibility = Eligibility.NothingToProtect)
        p.onStart()
        assertEquals(LockState.Disabled, p.state)
        p.update(LockSettings(), Eligibility.Eligible)
        assertEquals(LockState.Unlocked, p.state)
        goBackground(p, 60_000)
        assertEquals(LockState.Locked(LockReason.Background), p.state)
    }

    @Test
    fun removingTheScreenLockNeverTraps() {
        val p = policy()
        assertTrue(p.state.hidesContent)
        p.update(LockSettings(), Eligibility.NoSecureLock)
        assertEquals(LockState.Unavailable, p.state)
        assertFalse(p.state.hidesContent)
    }

    @Test
    fun turningTheSwitchOffOpensAndOnDoesNotLock() {
        val p = unlockedInFront()
        p.update(LockSettings(enabled = false), Eligibility.Eligible)
        assertEquals(LockState.Disabled, p.state)
        goBackground(p, 10 * 60_000L)
        assertEquals(LockState.Disabled, p.state)
        p.update(LockSettings(enabled = true), Eligibility.Eligible)
        assertEquals(LockState.Unlocked, p.state)
    }
}
