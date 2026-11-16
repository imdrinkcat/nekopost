package com.drinkcat.nekopost.lock

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.data.UiPrefs
import com.drinkcat.nekopost.system.ExternalGuards
import com.drinkcat.nekopost.system.ExternalLauncher
import com.drinkcat.nekopost.system.QrScanner
import com.drinkcat.nekopost.system.ScanTarget
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 扫码的时候不能被打断 (the 0.2.1 bug): the app lock wired to a fake process
 * lifecycle and a fake scanner. The scanner, a permission dialog and a
 * settings page each stop the app; none of them may lock it or lose the result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExternalFlowTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private var now = 5_000_000L
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Holds the scan until the test delivers it, like the Play services activity. */
    private class FakeScanner : QrScanner {
        var pending: ((String) -> Unit)? = null
        var cancel: (() -> Unit)? = null
        override fun scan(context: Context, onResult: (String) -> Unit, onUnavailable: () -> Unit, onCancel: () -> Unit) {
            pending = onResult
            cancel = onCancel
        }
    }

    private lateinit var prefs: UiPrefs
    private lateinit var lock: AppLock
    private val scanner = FakeScanner()
    private val launcher = ExternalLauncher(scanner)

    @Before
    fun setUp() {
        prefs = UiPrefs(app)
        prefs.clearAll()
        prefs.arm()
        lock = AppLock(prefs, secure = { true }, clock = { now })
        lock.attach(owner, TestScope())
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(LockState.Locked(LockReason.ColdStart), lock.state.value.state)
        lock.unlocked()
    }

    @After
    fun tearDown() {
        ExternalGuards.sink = null
    }

    private fun background(millis: Long) {
        owner.registry.currentState = Lifecycle.State.CREATED
        now += millis
    }

    private fun foreground() {
        owner.registry.currentState = Lifecycle.State.RESUMED
    }

    @Test
    fun scanResultSurvivesTheTripAndIsConsumedOnce() {
        launcher.scanQr(app, ScanTarget.Pair)
        background(2 * 60_000L)
        scanner.pending!!.invoke("nekopost://enroll?v=1&kind=device&url=https%3A%2F%2Fexample.com&code=abc")
        foreground()
        assertEquals(LockState.Unlocked, lock.state.value.state)
        // The enroll step does not take the pairing screen's result…
        assertNull(launcher.consume(ScanTarget.Enroll))
        // …the pairing screen does, exactly once.
        assertTrue(launcher.consume(ScanTarget.Pair)!!.raw!!.startsWith("nekopost://enroll"))
        assertNull(launcher.consume(ScanTarget.Pair))
    }

    @Test
    fun resultDeliveredWhileLockedWaitsForTheUnlock() {
        launcher.scanQr(app, ScanTarget.Enroll)
        background(10_000)
        lock.foldClosed()
        lock.lockNow() // e.g. screen off while the scanner is open
        scanner.pending!!.invoke("nekopost://enroll?v=1&kind=source")
        foreground()
        assertTrue(lock.state.value.state.hidesContent)
        lock.unlocked()
        assertEquals("nekopost://enroll?v=1&kind=source", launcher.consume(ScanTarget.Enroll)!!.raw)
    }

    @Test
    fun cancelledScanReleasesTheGuard() {
        launcher.scanQr(app, ScanTarget.Pair)
        scanner.cancel!!.invoke()
        background(60_000)
        foreground()
        assertEquals(LockState.Locked(LockReason.Background), lock.state.value.state)
    }

    @Test
    fun ordinaryTripLocks() {
        background(60_000)
        foreground()
        assertEquals(LockState.Locked(LockReason.Background), lock.state.value.state)
    }

    @Test
    fun settingsPageTripDoesNotLock() {
        assertTrue(launcher.startSettings(app, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
        background(5 * 60_000L)
        foreground()
        assertEquals(LockState.Unlocked, lock.state.value.state)
    }

    @Test
    fun deliveredLinkReachesThePairingScreen() {
        launcher.deliver(ScanTarget.Pair, "nekopost://enroll?v=1&kind=device")
        assertEquals("nekopost://enroll?v=1&kind=device", launcher.consume(ScanTarget.Pair)!!.raw)
    }
}
