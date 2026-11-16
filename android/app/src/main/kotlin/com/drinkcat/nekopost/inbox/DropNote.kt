package com.drinkcat.nekopost.inbox

import android.view.DragEvent
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.common.SettingsGroup
import com.drinkcat.nekopost.ui.common.SubScreenTopBar
import com.drinkcat.nekopost.ui.common.SwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Stateless 发给自己 (§10.4 ⑭); drag-in from other apps is off by default. */
@Composable
fun DropNoteContent(
    text: String,
    onTextChange: (String) -> Unit,
    dragEnabled: Boolean,
    onDragEnabledChange: (Boolean) -> Unit,
    message: String?,
    pending: Boolean,
    onSend: () -> Unit,
    onBack: () -> Unit,
    dropTarget: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().testTag("drop_note")) {
        SubScreenTopBar(stringResource(R.string.inbox_tool_drop_note), onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NekoTokens.Spacing.l),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
        ) {
            Text(stringResource(R.string.drop_note_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                text,
                { onTextChange(it.take(12000)) },
                label = { Text(stringResource(R.string.drop_note_body)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("drop_note_field"),
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(
                enabled = !pending && text.isNotBlank(),
                onClick = onSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget),
            ) { Text(stringResource(R.string.drop_note_send)) }
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.drop_note_drag),
                    summary = stringResource(R.string.drop_note_drag_detail),
                    checked = dragEnabled,
                    onCheckedChange = onDragEnabledChange,
                )
            }
            if (dragEnabled) dropTarget()
        }
    }
}

@Composable
internal fun DropNote(controller: DeviceInbox, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.activity()
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    var enabled by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val dropHint = stringResource(R.string.drop_note_drop_here)
    val tooLong = stringResource(R.string.drop_note_too_long)
    val sent = stringResource(R.string.drop_note_sent)
    val failed = stringResource(R.string.drop_note_failed)
    DropNoteContent(
        text = text,
        onTextChange = { text = it },
        dragEnabled = enabled,
        onDragEnabledChange = { enabled = it },
        message = message,
        pending = pending,
        onSend = {
            scope.launch {
                pending = true
                try {
                    controller.sendNote(text)
                    text = ""
                    message = sent
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = failed
                } finally {
                    pending = false
                }
            }
        },
        onBack = onClose,
        dropTarget = {
            // Untrusted input: a single plain-text item only, never more than 12,000 characters.
            AndroidView(factory = { ctx -> TextView(ctx) }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), update = { target ->
                target.text = dropHint
                target.setTextColor(ink)
                target.setPadding(24, 36, 24, 36)
                target.setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.hasMimeType("text/plain") == true
                        DragEvent.ACTION_DROP -> {
                            val grant = activity?.requestDragAndDropPermissions(event)
                            try {
                                val clip = event.clipData
                                val raw = if (clip != null && clip.itemCount == 1) clip.getItemAt(0).text?.toString() else null
                                if (raw == null || raw.isBlank() || raw.length > 12000) {
                                    message = tooLong
                                    false
                                } else {
                                    text = raw
                                    message = null
                                    true
                                }
                            } finally {
                                grant?.release()
                            }
                        }
                        else -> true
                    }
                }
            })
        },
    )
}
