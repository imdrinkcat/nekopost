package com.drinkcat.nekopost.inbox

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun PostureFeedback(posture: Posture) {
    val view = LocalView.current
    var last by remember { mutableStateOf(posture) }
    LaunchedEffect(posture) {
        if (last != posture && (posture == Posture.Book || posture == Posture.Tabletop)) {
            if (Build.VERSION.SDK_INT >= 34) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        last = posture
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverCode(message: InboxMessage, code: String, onCopy: () -> Unit) {
    val view = LocalView.current
    var copied by remember(message.id) { mutableStateOf(false) }
    var expired by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) {
        delay(300_000)
        expired = true
    }
    if (expired) return
    Card(Modifier.fillMaxWidth().combinedClickable(
        onClick = {
            onCopy()
            copied = true
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        },
        onLongClick = {
            if (Regex("[0-9]{4,8}").matches(code)) {
                val clip = ClipData.newPlainText("验证码", code)
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                view.startDragAndDrop(clip, android.view.View.DragShadowBuilder(view), null, android.view.View.DRAG_FLAG_GLOBAL)
            }
        },
    )) {
        Column(Modifier.padding(16.dp)) {
            Text(message.source, style = MaterialTheme.typography.labelLarge)
            Text(if (copied) "已复制 · 60 秒后清除" else code,
                style = if (copied) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displayMedium)
        }
    }
}

@Composable
internal fun SortingDesk(controller: DeviceInbox, fold: FoldState, onClose: () -> Unit) {
    var current by remember { mutableStateOf<InboxMessage?>(null) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var queued by remember { mutableStateOf<InboxMessage?>(null) }
    var feedback by remember { mutableStateOf("") }
    var clock by remember { mutableStateOf(0) }
    LaunchedEffect(skipped, clock) {
        try {
            val rows = controller.manage("GET", listOf("v1", "admin", "filtered")).optJSONArray("items")
            val id = if (rows == null) null else (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("id") }
                .firstOrNull { it !in skipped && messagePattern.matches(it) }
            current = id?.let { InboxMessage.parse(controller.manage("GET", listOf("v1", "inbox", it))) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = "无法读取已过滤消息。"
        }
    }
    LaunchedEffect(queued?.id) {
        val item = queued ?: return@LaunchedEffect
        delay(5000)
        try {
            controller.manage("POST", listOf("v1", "inbox", item.id, "rescue"), JSONObject())
            feedback = "已排入投递"
            skipped = skipped + item.id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = "找回失败"
        } finally {
            queued = null
        }
    }

    val display: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().pointerInput(current?.id, queued?.id) {
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onHorizontalDrag = { change, displacement -> change.consume(); drag += displacement },
                onDragEnd = {
                    val item = current
                    if (item != null && queued == null) {
                        if (drag > 88.dp.toPx() && item.body != null) queued = item
                        else if (drag < -88.dp.toPx()) skipped = skipped + item.id
                    }
                },
            )
        }.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("分拣台", style = MaterialTheme.typography.headlineSmall)
            val item = current
            if (item == null) Text("暂无待查看的已过滤消息") else {
                Text(item.source + " · " + item.category)
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                Text(item.body ?: "正文已过期")
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            TextButton(onClick = onClose) { Text("返回收件箱") }
            if (feedback.isNotBlank()) Text(feedback)
            if (queued != null) {
                Text("5 秒后找回并转发")
                Button(onClick = { queued = null; feedback = "" }) { Text("撤销") }
            } else {
                val message = current
                if (message != null) {
                    Button(enabled = message.body != null, onClick = { queued = message },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text("找回并转发") }
                    TextButton(onClick = { skipped = skipped + message.id; feedback = "保留过滤结果" },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text("确实是广告") }
                    TextButton(onClick = { skipped = skipped + message.id },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text("下一条") }
                }
                TextButton(onClick = { skipped = emptySet(); clock++ }) { Text("刷新") }
            }
        }
    }
    if (fold.posture == Posture.Tabletop) HingeAwareSplit(fold, SplitAxis.Vertical, first = display, second = controls)
    else if (fold.posture == Posture.Book) HingeAwareSplit(fold, SplitAxis.Horizontal, first = display, second = controls)
    else Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) { display() }
        Column(Modifier.weight(1f)) { controls() }
    }
}

@Composable
internal fun WatchDesk(controller: DeviceInbox, state: InboxState, fold: FoldState, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context.activity()
    var visibleCode by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<CancellationSignal?>(null) }
    DisposableEffect(Unit) { onDispose { prompt?.cancel(); controller.hideDetail() } }
    LaunchedEffect(visibleCode) {
        if (visibleCode != null) {
            delay(30_000)
            visibleCode = null
            controller.hideDetail()
        }
    }
    var keepOn by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var visibleRows by remember { mutableStateOf(state.rows) }
    LaunchedEffect(state.rows, paused) { if (!paused) visibleRows = state.rows }
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    DisposableEffect(activity, keepOn, charging, fold.posture, level) {
        if (activity != null && fold.posture == Posture.Tabletop && level >= 20 && (charging || keepOn)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val display: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("桌面值守台", style = MaterialTheme.typography.headlineSmall)
            Text(if (state.error == null) "收件连接正常" else "收件连接有异常")
            visibleCode?.let { Text(it, style = MaterialTheme.typography.displayLarge) }
            visibleRows.take(5).forEach {
                Text(if (it.hasCode) "验证码已到达 · " + it.source else it.source + " · " + it.category)
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp)) {
            Text("最近消息 ${visibleRows.size} 条")
            Row {
                TextButton(onClick = { paused = !paused }) { Text(if (paused) "继续" else "暂停") }
                if (fold.posture == Posture.Tabletop) {
                    TextButton(onClick = { keepOn = !keepOn }) { Text(if (keepOn) "关闭常亮" else "保持常亮") }
                }
                val recentCode = visibleRows.firstOrNull { it.hasCode }
                if (recentCode != null) {
                    TextButton(onClick = {
                        prompt?.cancel()
                        val signal = CancellationSignal()
                        prompt = signal
                        runCatching {
                            BiometricPrompt.Builder(context).setTitle("翻开验证码")
                                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
                                .authenticate(signal, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                        scope.launch {
                                            controller.detail(recentCode.id)
                                            visibleCode = controller.state.value.detail?.takeIf { it.id == recentCode.id }?.otp
                                        }
                                    }
                                })
                        }
                    }) { Text("翻牌") }
                }
                TextButton(onClick = onExit) { Text("退出值守台") }
            }
        }
    }
    if (fold.posture == Posture.Tabletop) HingeAwareSplit(fold, SplitAxis.Vertical, first = display, second = controls)
    else Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) { display() }
        controls()
    }
}

@Composable
internal fun EnvelopeOpening(message: InboxMessage?, posture: Posture, open: (String) -> Unit) {
    if (message == null || posture == Posture.Cover) return
    val context = LocalContext.current
    val sensors = remember(context) { context.getSystemService(android.hardware.SensorManager::class.java) }
    val hinge = remember(sensors) { sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE) }
    var angle by remember { mutableStateOf(if (hinge == null) 180f else 0f) }
    var turnedOff by remember { mutableStateOf(false) }
    DisposableEffect(hinge, turnedOff) {
        val listener = object : android.hardware.SensorEventListener {
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                angle = event?.values?.firstOrNull()?.coerceIn(0f, 180f) ?: angle
            }
        }
        if (!turnedOff && hinge != null) sensors?.registerListener(listener, hinge, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors?.unregisterListener(listener) }
    }
    if (turnedOff) return
    val progress = (angle / 180f).coerceIn(0f, 1f)
    val reveal = progress > 0.55f
    Card(Modifier.fillMaxWidth().graphicsLayer {
        alpha = 0.75f + 0.25f * progress
        rotationX = (1f - progress) * 20f
    }) {
        Column(Modifier.padding(10.dp)) {
            Text(if (reveal) message.title.ifBlank { message.source } else "有新消息")
            Row {
                TextButton(onClick = { open(message.id) }) { Text("打开信封") }
                TextButton(onClick = { turnedOff = true }) { Text("关闭") }
            }
        }
    }
}
