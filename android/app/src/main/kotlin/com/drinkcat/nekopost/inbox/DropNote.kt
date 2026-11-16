package com.drinkcat.nekopost.inbox

import android.view.DragEvent
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DropNote(controller: DeviceInbox, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.activity()
    val dark = MaterialTheme.colorScheme.onSurface.toArgb()
    var enabled by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onClose) { Text("返回收件箱") }
        Text("发给自己", style = MaterialTheme.typography.headlineSmall)
        Row {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Text("允许从其他 App 拖入文字", Modifier.padding(8.dp))
        }
        if (enabled) {
            AndroidView(factory = { ctx -> TextView(ctx) }, modifier = Modifier.fillMaxWidth(), update = { target ->
                target.text = "将纯文本拖到这里预览"
                target.setTextColor(dark)
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
                                    message = "只接受一段不超过 12,000 字的纯文本。"
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
        }
        OutlinedTextField(text, { text = it.take(12000) }, label = { Text("消息正文") }, modifier = Modifier.fillMaxWidth())
        message?.let { Text(it) }
        Button(enabled = !pending && text.isNotBlank(), onClick = {
            scope.launch {
                pending = true
                try {
                    controller.sendNote(text)
                    text = ""
                    message = "已提交"
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = "未发送，请确认设备有发送权限。"
                } finally { pending = false }
            }
        }) { Text("发送到猫邮局") }
    }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
