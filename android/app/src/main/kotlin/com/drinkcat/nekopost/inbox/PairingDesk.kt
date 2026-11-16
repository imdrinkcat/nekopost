package com.drinkcat.nekopost.inbox

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun PairingDesk(controller: DeviceInbox, created: JSONObject, onDismiss: () -> Unit) {
    val id = created.optString("id")
    if (!pairPattern.matches(id)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(id) {
        runCatching {
            val bytes = Base64.decode(created.getString("qr_png"), Base64.DEFAULT)
            require(bytes.size < 65536)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    var show by remember(id) { mutableStateOf(false) }
    var timedOut by remember(id) { mutableStateOf(false) }
    var status by remember(id) { mutableStateOf<JSONObject?>(null) }
    var working by remember(id) { mutableStateOf(false) }
    var feedback by remember(id) { mutableStateOf("") }
    LaunchedEffect(id) {
        while (true) {
            try {
                status = controller.manage("GET", listOf("v1", "admin", "pairings", id))
                if (status?.optString("state") in setOf("done", "rejected", "contested", "expired")) break
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                feedback = "暂时无法查看配对进度。"
            }
            delay(2000)
        }
    }

    LaunchedEffect(show) {
        if (show) {
            delay(30_000)
            show = false
            timedOut = true
        }
    }
    val window = context.activity()?.window
    DisposableEffect(show, window) {
        val old = window?.attributes?.screenBrightness ?: -1f
        if (show && window != null) window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose { if (show && window != null) window.attributes = window.attributes.apply { screenBrightness = old } }
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (show && bitmap != null && status?.optString("state") in listOf(null, "open", "claimed")) {
            Image(bitmap, contentDescription = "设备配对二维码", modifier = Modifier.align(Alignment.CenterHorizontally).size(220.dp),
                filterQuality = FilterQuality.None)
        }
        if (status?.optString("state") in listOf(null, "open", "claimed")) {
            if (bitmap != null && !timedOut) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().pointerInput(id) {
                        detectTapGestures(onPress = {
                            show = true
                            tryAwaitRelease()
                            show = false
                        })
                    }) { Box(Modifier.padding(16.dp)) { Text("按住显示二维码") } }
            }
            Text("手动配对码 " + created.optString("short_code"))
        }
        val choices = status?.optJSONArray("choices")
        if (choices != null && status?.optString("state") == "claimed") {
            Text("设备自报： " + status?.optString("name").orEmpty())
            Text("请选择新设备显示的数字")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until choices.length()).map { choices.optString(it) }.filter { Regex("[0-9]{2}").matches(it) }.forEach { choice ->
                    Button(enabled = !working, onClick = {
                        scope.launch {
                            working = true
                            try {
                                val result = controller.manage("POST", listOf("v1", "admin", "pairings", id, "decision"),
                                    JSONObject().put("choice", choice)) { authorizeOperation(context, it) }
                                feedback = if (result.optBoolean("approved")) "已确认配对" else "已拒绝配对"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                feedback = "确认失败"
                            } finally { working = false }
                        }
                    }) { Text(choice) }
                }
            }
        }
        if (feedback.isNotBlank()) Text(feedback)
        TextButton(onClick = onDismiss) { Text("关闭配对信息") }
    }
}
