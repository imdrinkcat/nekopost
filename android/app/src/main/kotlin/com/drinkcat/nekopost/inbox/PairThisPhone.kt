package com.drinkcat.nekopost.inbox

import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.lock.LocalAppLock
import com.drinkcat.nekopost.system.LocalExternalLauncher
import com.drinkcat.nekopost.system.ScanTarget
import com.drinkcat.nekopost.ui.common.LevelBackHandler
import com.drinkcat.nekopost.ui.common.NoticeCard
import com.drinkcat.nekopost.ui.common.SubScreenTopBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 配对此手机 as rendered (stateless; screenshot-tested). The pairing code never goes into saved state. */
data class PairUi(
    val name: String = "",
    val server: String = "",
    val code: String = "",
    /** A link or code was read: the server host waits for confirmation. */
    val confirmHost: String? = null,
    /** The two digits to pick in Telegram or on the admin phone. */
    val number: String? = null,
    val connecting: Boolean = false,
    val error: String? = null,
)

data class PairActions(
    val onName: (String) -> Unit = {},
    val onServer: (String) -> Unit = {},
    val onCode: (String) -> Unit = {},
    val onScan: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onUseCode: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancelConfirm: () -> Unit = {},
    val onBack: (() -> Unit)? = null,
)

/**
 * Pairs this phone as a receiving device. The code comes from a QR (web panel,
 * the admin phone's 添加设备, or the Telegram bot's photo) or — because the
 * phone being paired is often the one showing Telegram — from a pasted
 * `nekopost://enroll?…kind=device` link or short code (CONTRACT §2.1).
 */
@Composable
fun PairThisPhonePane(ui: PairUi, actions: PairActions, modifier: Modifier = Modifier) {
    // While this phone shows the number to pick (or is still connecting), leaving asks first:
    // the pairing goes on in the background, but the number is no longer on screen.
    val waiting = ui.number != null || ui.connecting
    var confirmLeave by rememberSaveable(LocalAppLock.current.epoch) { mutableStateOf(false) }
    val leave = actions.onBack?.let { onBack -> if (waiting) ({ confirmLeave = true }) else onBack }
    if (confirmLeave && waiting && actions.onBack != null) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.pairing_leave_title)) },
            text = { Text(stringResource(R.string.pair_leave_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; actions.onBack?.invoke() }, modifier = Modifier.testTag("pair_leave_confirm")) {
                    Text(stringResource(R.string.pairing_leave_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }, modifier = Modifier.testTag("pair_leave_stay")) {
                    Text(stringResource(R.string.pairing_leave_stay))
                }
            },
        )
    }
    Column(modifier.fillMaxSize().testTag("pair_this_phone")) {
        SubScreenTopBar(stringResource(R.string.pair_title), leave)
        // The server confirmation is a step inside the page: back returns to the entry first.
        LevelBackHandler(enabled = ui.confirmHost != null && ui.number == null && !ui.connecting, onBack = actions.onCancelConfirm)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NekoTokens.Spacing.l).padding(bottom = NekoTokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
        ) {
            when {
                ui.number != null -> NumberCard(ui.number)
                ui.connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(NekoTokens.Spacing.m))
                    Text(stringResource(R.string.pair_connecting), style = MaterialTheme.typography.bodyLarge)
                }
                ui.confirmHost != null -> ConfirmCard(ui.confirmHost, actions)
                else -> Entry(ui, actions)
            }
            ui.error?.let { NoticeCard(text = it, tone = StatusTone.Error) }
        }
    }
}

@Composable
private fun Entry(ui: PairUi, actions: PairActions) {
    Text(stringResource(R.string.pair_intro), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        ui.name,
        { actions.onName(it.take(64)) },
        label = { Text(stringResource(R.string.pair_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("pair_name"),
    )
    Button(
        onClick = actions.onScan,
        modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.coverPrimaryTarget).testTag("pair_scan"),
    ) {
        Icon(NekoIcons.QrScan, contentDescription = null)
        Spacer(Modifier.width(NekoTokens.Spacing.s))
        Text(stringResource(R.string.pair_scan), style = MaterialTheme.typography.titleMedium)
    }
    FilledTonalButton(
        onClick = actions.onPaste,
        modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget).testTag("pair_paste"),
    ) {
        Icon(NekoIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NekoTokens.Spacing.s))
        Text(stringResource(R.string.pair_paste))
    }
    Text(stringResource(R.string.pair_paste_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(R.string.pair_or_manual),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = NekoTokens.Spacing.m),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
    OutlinedTextField(
        ui.server,
        { actions.onServer(it.take(256)) },
        label = { Text(stringResource(R.string.pair_server)) },
        singleLine = true,
        textStyle = NekoTextStyles.mono,
        modifier = Modifier.fillMaxWidth().testTag("pair_server"),
    )
    OutlinedTextField(
        ui.code,
        { actions.onCode(it.take(64)) },
        label = { Text(stringResource(R.string.pair_code)) },
        placeholder = { Text("XXXX-XXXX-XX") },
        singleLine = true,
        textStyle = NekoTextStyles.mono,
        modifier = Modifier.fillMaxWidth().testTag("pair_code"),
    )
    OutlinedButton(
        enabled = ui.code.isNotBlank(),
        onClick = actions.onUseCode,
        modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget).testTag("pair_use_code"),
    ) { Text(stringResource(R.string.pair_use_code)) }
}

@Composable
private fun ConfirmCard(host: String, actions: PairActions) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(NekoTokens.Spacing.l), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m)) {
            Text(stringResource(R.string.pair_confirm_title), style = MaterialTheme.typography.titleMedium)
            Text(host, style = NekoTextStyles.mono.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), modifier = Modifier.testTag("pair_host"))
            Text(stringResource(R.string.pair_confirm_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = actions.onConfirm,
                modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.coverPrimaryTarget).testTag("pair_confirm"),
            ) { Text(stringResource(R.string.pair_confirm), style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = actions.onCancelConfirm, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_back)) }
        }
    }
}

@Composable
private fun NumberCard(number: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("pair_number")) {
        Column(
            Modifier.padding(NekoTokens.Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
        ) {
            Text(stringResource(R.string.pair_number_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(number, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                stringResource(R.string.pair_number_detail),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Stateful 配对此手机: the scanner result arrives through [LocalExternalLauncher]
 * (process level), so leaving for the scanner never drops it.
 */
@Composable
internal fun PairThisPhoneRoute(controller: DeviceInbox, serverHint: String?, scope: CoroutineScope, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val launcher = LocalExternalLauncher.current
    val state by controller.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(Build.MODEL.orEmpty().take(64)) }
    var server by remember { mutableStateOf(serverHint.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<PairingInput?>(null) }
    var fromClipboard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidQr = stringResource(R.string.pair_error_qr)
    val noScanner = stringResource(R.string.pair_error_scanner)
    val invalidInput = stringResource(R.string.pair_error_input)
    val emptyClipboard = stringResource(R.string.pair_error_clipboard)

    fun accept(text: String, clipboard: Boolean, failure: String) {
        selected = runCatching { PairingInput.parse(text, server) }.getOrNull()
        fromClipboard = clipboard && selected != null
        error = if (selected == null) failure else null
    }

    LaunchedEffect(launcher) {
        launcher.scans.collect {
            val result = launcher.consume(ScanTarget.Pair) ?: return@collect
            if (result.unavailable) error = noScanner else accept(result.raw.orEmpty(), clipboard = false, failure = invalidQr)
        }
    }
    PairThisPhonePane(
        ui = PairUi(
            name = name,
            server = server,
            code = code,
            confirmHost = selected?.server?.host,
            number = state.pairingNumber,
            connecting = state.pairing && state.pairingNumber == null,
            error = error ?: state.error.takeIf { !state.pairing && state.paired == false },
        ),
        actions = PairActions(
            onName = { name = it },
            onServer = { server = it },
            onCode = { code = it },
            onScan = { error = null; launcher.scanQr(context, ScanTarget.Pair) },
            onPaste = {
                val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) error = emptyClipboard else accept(text, clipboard = true, failure = invalidInput)
            },
            onUseCode = { accept(code, clipboard = false, failure = invalidInput) },
            onConfirm = {
                selected?.let { input ->
                    selected = null
                    code = ""
                    scope.launch {
                        controller.pair(input, name)
                        if (fromClipboard && controller.paired()) {
                            context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
                        }
                        fromClipboard = false
                    }
                }
            },
            onCancelConfirm = { selected = null },
            onBack = onBack,
        ),
    )
}
