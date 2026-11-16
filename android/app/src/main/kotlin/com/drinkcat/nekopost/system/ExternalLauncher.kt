package com.drinkcat.nekopost.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.staticCompositionLocalOf
import com.drinkcat.nekopost.lock.ExternalKind
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide hook into the app lock: every activity the app starts itself
 * registers a guard first, so the trip to the scanner, a permission dialog or
 * the system settings never locks the app. Set by AppLock; a no-op in tests
 * and previews.
 */
object ExternalGuards {
    @Volatile
    var sink: ((ExternalKind) -> () -> Unit)? = null

    /** Registers a guard; call the returned function when the result has arrived (or the start failed). */
    fun expect(kind: ExternalKind): () -> Unit = sink?.invoke(kind) ?: {}
}

/** The Google code scanner behind an interface, so tests can fake the round trip. */
interface QrScanner {
    fun scan(context: Context, onResult: (String) -> Unit, onUnavailable: () -> Unit, onCancel: () -> Unit)
}

object GmsQrScanner : QrScanner {
    override fun scan(context: Context, onResult: (String) -> Unit, onUnavailable: () -> Unit, onCancel: () -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { onResult(it.rawValue.orEmpty()) }
            .addOnCanceledListener(onCancel)
            .addOnFailureListener { onUnavailable() }
    }
}

/** Who asked for a scan: the result waits in [ExternalLauncher.scans] until that screen takes it. */
enum class ScanTarget { Enroll, Pair }

data class ScanResult(val target: ScanTarget, val raw: String?, val serial: Long) {
    /** The scanner could not run (no Play services); the screen offers manual entry instead. */
    val unavailable: Boolean get() = raw == null
}

/**
 * Starts every external activity of the app (design: 扫码的时候不能被打断).
 * Results are held here, at process level, never in composition-scoped state:
 * the screen that asked consumes them once, whenever it is composed again.
 */
class ExternalLauncher(private val scanner: QrScanner = GmsQrScanner) {
    private val serial = AtomicLong()
    private val _scans = MutableStateFlow<ScanResult?>(null)
    val scans: StateFlow<ScanResult?> = _scans.asStateFlow()

    fun scanQr(context: Context, target: ScanTarget) {
        val release = ExternalGuards.expect(ExternalKind.QrScanner)
        try {
            scanner.scan(
                context,
                onResult = { raw ->
                    _scans.value = ScanResult(target, raw.take(4096), serial.incrementAndGet())
                    release()
                },
                onUnavailable = {
                    _scans.value = ScanResult(target, null, serial.incrementAndGet())
                    release()
                },
                onCancel = release,
            )
        } catch (e: RuntimeException) {
            release()
            _scans.value = ScanResult(target, null, serial.incrementAndGet())
        }
    }

    /** Hands text to [target] as if it had been scanned (a pasted device link the wizard routes to pairing). */
    fun deliver(target: ScanTarget, raw: String) {
        _scans.value = ScanResult(target, raw.take(4096), serial.incrementAndGet())
    }

    /** Takes the pending result for [target], exactly once. */
    fun consume(target: ScanTarget): ScanResult? {
        while (true) {
            val current = _scans.value ?: return null
            if (current.target != target) return null
            if (_scans.compareAndSet(current, null)) return current
        }
    }

    /** Opens the first system settings screen that resolves. */
    fun startSettings(context: Context, vararg intents: Intent): Boolean {
        val release = ExternalGuards.expect(ExternalKind.SystemSettings)
        val ok = context.startFirst(*intents)
        if (!ok) release()
        return ok
    }

    /** Runtime permission requests and other system dialogs started through the Activity Result API. */
    fun <I> launch(launcher: ActivityResultLauncher<I>, input: I) {
        val release = ExternalGuards.expect(ExternalKind.Permission)
        try {
            launcher.launch(input)
        } catch (e: ActivityNotFoundException) {
            release()
        }
    }

    fun share(context: Context, text: String, title: String) {
        val release = ExternalGuards.expect(ExternalKind.Share)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (!context.startFirst(Intent.createChooser(send, title))) release()
    }
}

/** The launcher of this process; the default (tests, previews) works the same without a lock. */
val LocalExternalLauncher = staticCompositionLocalOf { ExternalLauncher() }
