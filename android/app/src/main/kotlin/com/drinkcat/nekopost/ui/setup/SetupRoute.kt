package com.drinkcat.nekopost.ui.setup

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import com.drinkcat.nekopost.core.enroll.EnrollmentParseResult
import com.drinkcat.nekopost.core.forwarder.EnrollResult
import com.drinkcat.nekopost.core.forwarder.ForwarderController
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.system.SystemIntents
import com.drinkcat.nekopost.system.rememberObscuredTouchGuard
import com.drinkcat.nekopost.system.startFirst
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

/**
 * Stateful wizard: wires [SetupWizard] to the forwarder, the system settings
 * screens, runtime permission requests and the Google code scanner (which
 * needs no camera permission: the scanning UI runs in Play services).
 *
 * The enrollment link is held only in memory (it carries the source token):
 * it is never put in saved instance state, and a link pasted from the
 * clipboard is cleared from the clipboard once enrollment succeeds.
 */
@Composable
fun SetupRoute(
    controller: ForwarderController,
    snapshot: SetupSnapshot,
    step: SetupStep,
    fold: FoldState,
    canClose: Boolean,
    allowInsecureLocalhost: Boolean,
    onStepChange: (SetupStep) -> Unit,
    onAcknowledge: (SetupStep) -> Unit,
    onSmsBlockedChange: (Boolean) -> Unit,
    onRefreshChecks: () -> Unit,
    onFinish: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enroll by remember { mutableStateOf<EnrollUiState>(EnrollUiState.Idle) }
    var fromClipboard by rememberSaveable { mutableStateOf(false) }
    // POST_NOTIFICATIONS refused for good ("don't ask again"): the request returns at once, so open the settings.
    var postBlocked by rememberSaveable { mutableStateOf(false) }
    val guard = rememberObscuredTouchGuard()

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.isNotEmpty() && result.values.all { it }
        val activity = context.findActivity()
        // Refused without a rationale right away: Android blocked it as a restricted setting
        // (or the user chose "don't ask again"). Either way the app details page is the way out.
        onSmsBlockedChange(
            !granted && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECEIVE_SMS),
        )
        onRefreshChecks()
    }
    val postLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        postBlocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        onRefreshChecks()
    }

    fun submit(raw: String, clipboard: Boolean) {
        fromClipboard = clipboard
        enroll = when (val r = EnrollmentLink.parse(raw, allowInsecureLocalhost)) {
            is EnrollmentParseResult.Valid -> EnrollUiState.Confirm(r.link)
            is EnrollmentParseResult.Invalid -> EnrollUiState.Invalid(r.error)
        }
    }

    val actions = SetupActions(
        onStepChange = onStepChange,
        onClose = onClose,
        onFinish = onFinish,
        onAcknowledge = onAcknowledge,
        onScanQr = {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode -> submit(barcode.rawValue.orEmpty(), clipboard = false) }
                .addOnFailureListener { enroll = EnrollUiState.ScannerUnavailable }
        },
        onSubmitLink = { submit(it, clipboard = false) },
        onPasteFromClipboard = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            submit(text.orEmpty(), clipboard = true)
        },
        onConfirmEnroll = guard {
            val link = when (val e = enroll) {
                is EnrollUiState.Confirm -> e.link
                is EnrollUiState.Failed -> e.link
                else -> null
            }
            if (link != null) {
                enroll = EnrollUiState.Enrolling(link)
                scope.launch {
                    enroll = when (val r = controller.enroll(link)) {
                        is EnrollResult.Success -> {
                            if (fromClipboard) {
                                context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
                                fromClipboard = false
                            }
                            EnrollUiState.Idle
                        }
                        is EnrollResult.Failure -> EnrollUiState.Failed(link, r.reason, r.httpStatus)
                    }
                }
            }
        },
        onCancelEnroll = { enroll = EnrollUiState.Idle },
        onOpenAppDetails = { context.startFirst(SystemIntents.appDetails(context)) },
        onRequestSms = guard {
            smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
        },
        onOpenNotificationAccess = {
            context.startFirst(SystemIntents.notificationAccess(context), SystemIntents.notificationAccessFallback())
        },
        onRequestPostNotifications = guard {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !postBlocked) {
                postLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startFirst(SystemIntents.appNotificationSettings(context), SystemIntents.appDetails(context))
            }
        },
        onRequestBatteryExemption = guard {
            context.startFirst(
                SystemIntents.requestIgnoreBatteryOptimizations(context),
                SystemIntents.batteryOptimizationList(),
            )
        },
        onOpenBatterySettings = {
            context.startFirst(SystemIntents.batteryOptimizationList(), SystemIntents.appDetails(context))
        },
        onOpenDisplaySettings = { context.startFirst(SystemIntents.displaySettings()) },
    )

    SetupWizard(
        snapshot = snapshot,
        step = step,
        enroll = enroll,
        fold = fold,
        actions = actions,
        canClose = canClose,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
