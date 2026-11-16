package com.drinkcat.nekopost.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.common.FootNote
import kotlinx.coroutines.delay

/** The pairing has not been used, rejected or expired yet. */
val PairingUi.live: Boolean get() = state == "open" || state == "claimed"

/**
 * 配对台 (§10.4 ⑬, design §3.7.2): the QR is shown only while pressed (and at
 * full brightness, for 30 s at most), the short code is always there to type
 * or paste on the new phone, and once claimed three big number buttons do the
 * number matching (each a k_reauth biometric in the controller).
 *
 * @param showQrHere false in tabletop, where the QR is on the upper half ([PairingDeskDisplay]).
 */
@Composable
fun PairingDeskContent(
    ui: PairingUi,
    showQrHere: Boolean,
    onChoose: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("pairing_desk"), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m)) {
        StatusPill(stateText(ui), stateTone(ui), large = true)
        when {
            ui.state == "claimed" && ui.choices.isNotEmpty() -> NumberMatch(ui, onChoose)
            ui.live -> {
                if (showQrHere) HoldQr(ui)
                CodeCard(ui, onCopy)
                FootNote(stringResource(R.string.pairing_desk_where))
            }
            else -> {
                ui.result?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget)) {
                    Text(stringResource(R.string.pairing_desk_done))
                }
            }
        }
    }
}

/** Tabletop upper half: the QR (held from below) or the claimed device's name. */
@Composable
fun PairingDeskDisplay(ui: PairingUi) {
    Box(Modifier.fillMaxSize().padding(NekoTokens.Spacing.l), contentAlignment = Alignment.Center) {
        when {
            ui.state == "claimed" -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                Text(stringResource(R.string.pairing_desk_claimed_by), style = MaterialTheme.typography.titleMedium)
                Text(ui.claimedBy ?: ui.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            }
            ui.live -> HoldQr(ui)
            else -> Text(stateText(ui), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun stateText(ui: PairingUi): String = stringResource(
    when (ui.state) {
        "open" -> R.string.pairing_state_open
        "claimed" -> R.string.pairing_state_claimed
        "done" -> R.string.pairing_state_done
        "rejected" -> R.string.pairing_state_rejected
        "contested" -> R.string.pairing_state_contested
        else -> R.string.pairing_state_expired
    },
)

private fun stateTone(ui: PairingUi): StatusTone = when (ui.state) {
    "open" -> StatusTone.Pending
    "claimed" -> StatusTone.Warning
    "done" -> StatusTone.Success
    else -> StatusTone.Error
}

/** Press and hold to show the QR (never shown by default; at most 30 s, full brightness). */
@Composable
private fun HoldQr(ui: PairingUi) {
    var show by remember(ui.id) { mutableStateOf(false) }
    var spent by remember(ui.id) { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            delay(30_000)
            show = false
            spent = true
        }
    }
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(show, window) {
        val before = window?.attributes?.screenBrightness ?: -1f
        if (show && window != null) window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose { if (show && window != null) window.attributes = window.attributes.apply { screenBrightness = before } }
    }
    val qr = ui.qr
    Surface(
        shape = MaterialTheme.shapes.large,
        // The QR always on white (scanners need the contrast), whatever the theme.
        color = if (show) NekoTokens.Light.surfaceContainerLowest else MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .testTag("pairing_qr")
            .pointerInput(ui.id, spent) {
                if (!spent && qr != null) {
                    detectTapGestures(onPress = {
                        show = true
                        tryAwaitRelease()
                        show = false
                    })
                }
            },
    ) {
        Box(Modifier.padding(NekoTokens.Spacing.l), contentAlignment = Alignment.Center) {
            if (show && qr != null) {
                Image(qr, contentDescription = stringResource(R.string.pairing_qr_label), modifier = Modifier.size(240.dp), filterQuality = FilterQuality.None)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                    Icon(NekoIcons.QrScan, contentDescription = null, modifier = Modifier.size(40.dp))
                    Text(
                        stringResource(if (spent) R.string.pairing_qr_spent else R.string.pairing_qr_hold),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeCard(ui: PairingUi, onCopy: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(NekoTokens.Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs)) {
                Text(stringResource(R.string.pairing_short_code), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ui.shortCode, style = NekoTextStyles.mono.copy(fontSize = 24.sp, letterSpacing = 2.sp), modifier = Modifier.testTag("pairing_code"))
            }
            FilledTonalButton(onClick = { onCopy(ui.shortCode) }) {
                Icon(NekoIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(NekoTokens.Spacing.xs))
                Text(stringResource(R.string.pairing_copy))
            }
        }
    }
}

@Composable
private fun NumberMatch(ui: PairingUi, onChoose: (String) -> Unit) {
    Text(
        stringResource(R.string.pairing_match_title, ui.claimedBy ?: ui.name),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(stringResource(R.string.pairing_match_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m), modifier = Modifier.fillMaxWidth()) {
        ui.choices.forEach { choice ->
            OutlinedButton(
                enabled = !ui.deciding,
                onClick = { onChoose(choice) },
                modifier = Modifier.weight(1f).heightIn(min = NekoTokens.Touch.tabletopTarget).testTag("pairing_choice_$choice"),
            ) { Text(choice, style = MaterialTheme.typography.headlineMedium) }
        }
    }
    if (ui.deciding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Text(stringResource(R.string.pairing_deciding))
        }
    }
    ui.result?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Text(it)
        }
    }
    FootNote(stringResource(R.string.pairing_match_elsewhere))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
