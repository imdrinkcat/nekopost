package com.drinkcat.nekopost.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.ui.common.AdaptiveListDetail
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

@Composable
internal fun InboxScreen(controller: DeviceInbox, fold: FoldState, mode: PaneMode, initialMessage: String? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    var selection by rememberSaveable { mutableStateOf(initialMessage?.takeIf(messagePattern::matches)) }
    var lastPosture by remember { mutableStateOf(fold.posture) }
    PostureFeedback(fold.posture)
    var unlocked by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<CancellationSignal?>(null) }

    LifecycleStartEffect(controller) {
        onStopOrDispose {
            unlocked = false
            controller.hideContent()
        }
    }
    DisposableEffect(Unit) { onDispose { prompt?.cancel(); controller.hideContent() } }
    LaunchedEffect(state.canRead) {
        if (state.canRead) PushRegistration.enable(context)
    }

    LaunchedEffect(fold.posture) {
        if (lastPosture != Posture.Cover && fold.posture == Posture.Cover) {
            controller.hideContent()
            unlocked = false
        }
        lastPosture = fold.posture
    }
    LaunchedEffect(unlocked) {
        if (unlocked) {
            while (InboxIdle.recent()) delay(1_000)
            controller.hideContent()
            unlocked = false
        }
    }

    // Nothing to protect before pairing, and the QR scanner is a separate
    // activity: locking on its onStop would drop the scan result. state.server
    // is only filled in after unlocking, so ask the vault whether a session exists.
    val paired by produceState<Boolean?>(null, controller, state.server) { value = controller.paired() }
    if (paired == false && state.server == null) {
        val pane: @Composable () -> Unit = { PairPane(state) { input, name -> scope.launch { controller.pair(input, name) } } }
        if (mode == PaneMode.TopBottom) {
            HingeAwareSplit(fold, SplitAxis.Vertical, Modifier.fillMaxSize(), first = {
                Text("设备配对", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
            }, second = pane)
        } else pane()
        return
    }

    if (!unlocked) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("收件箱已锁定", style = MaterialTheme.typography.headlineSmall)
            Text("用指纹或屏幕锁解锁收件箱。")
            issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                prompt?.cancel()
                val signal = CancellationSignal()
                prompt = signal
                try {
                    BiometricPrompt.Builder(context)
                        .setTitle("解锁猫邮局")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        .build().authenticate(signal, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                issue = null
                                InboxIdle.touch()
                                unlocked = true
                                scope.launch { controller.load(); selection?.let { controller.detail(it) } }
                            }
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                issue = errString.toString()
                            }
                        })
                } catch (_: RuntimeException) {
                    issue = "无法打开设备验证，请先设置屏幕锁。"
                }
            }) { Text("解锁") }
        }
        return
    }

    if (!state.canRead) {
        Column(Modifier.padding(16.dp)) {
            Text("这台设备只负责转发，可在顶部切换到转发页。")
            var ask by remember { mutableStateOf(false) }
            TextButton(onClick = { ask = !ask }) { Text("解除设备配对") }
            if (ask) TextButton(onClick = { scope.launch { controller.unpair() } }) { Text("确认解除") }
            state.error?.let { Text(it) }
        }
        return
    }

    var dropNote by remember { mutableStateOf(false) }
    if (dropNote) {
        val pane: @Composable () -> Unit = { DropNote(controller) { dropNote = false } }
        if (mode == PaneMode.TopBottom) HingeAwareSplit(fold, SplitAxis.Vertical, first = {
            Text("发给自己", Modifier.padding(16.dp))
        }, second = pane) else pane()
        return
    }
    var sorter by remember { mutableStateOf(false) }
    var monitor by remember { mutableStateOf(false) }
    if (sorter) {
        SortingDesk(controller, fold) { sorter = false }
        return
    }
    if (monitor) {
        LaunchedEffect(controller) { controller.trackForeground() }
        LaunchedEffect(controller) { controller.updates.collect { controller.refresh() } }
        WatchDesk(controller, state, fold) {
            monitor = false
            controller.hideContent()
            unlocked = false
        }
        return
    }
    var management by remember { mutableStateOf(false) }
    if (management) {
        val pane: @Composable () -> Unit = { DeviceManagement(controller) { management = false } }
        if (mode == PaneMode.TopBottom) {
            HingeAwareSplit(fold, SplitAxis.Vertical, Modifier.fillMaxSize(), first = {
                Text("设备管理", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
            }, second = pane)
        } else pane()
        return
    }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(controller, state.server) { controller.trackForeground() }
    LaunchedEffect(controller) {
        controller.updates.collect {
            if (query.isBlank() && state.detail == null) controller.refresh()
        }
    }
    var confirmUnpair by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    var keyboardMenu by remember { mutableStateOf(false) }
    LaunchedEffect(fold.posture, selection, state.rows) {
        if (mode == PaneMode.TwoPane && selection != null) {
            val index = state.rows.indexOfFirst { it.id == selection }
            if (index >= 0) listState.scrollToItem(index + 1)
        }
    }
    fun open(id: String) {
        selection = id
        scope.launch { controller.detail(id) }
    }
    val keyboard = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) return@onPreviewKeyEvent false
        when (event.key) {
            Key.K -> { keyboardMenu = !keyboardMenu; true }
            Key.F -> { searchFocus.requestFocus(); true }
            Key.C -> {
                state.detail?.otp?.let { copyVerificationCode(context, it) }
                state.detail?.otp != null
            }
            else -> false
        }
    }.focusable()
    val list: @Composable () -> Unit = {
        LazyColumn(keyboard.fillMaxSize().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("收件箱", style = MaterialTheme.typography.headlineSmall)
                if (mode != PaneMode.TopBottom) EnvelopeOpening(state.rows.firstOrNull { !it.read }, fold.posture, ::open)
                if (mode == PaneMode.TopBottom) state.detail?.let { MessageActions(controller, it) }
                Row {
                    TextButton(onClick = { query = ""; scope.launch { controller.refresh() } }) { Text("刷新") }
                    TextButton(onClick = { confirmUnpair = !confirmUnpair }) { Text("设备配对") }
                    if (state.canAdmin) TextButton(onClick = { management = true }) { Text("管理") }
                }
                Row {
                    if (state.canAdmin) TextButton(onClick = { sorter = true }) { Text("分拣台") }
                    TextButton(onClick = { monitor = true }) { Text("值守台") }
                    TextButton(onClick = { keyboardMenu = !keyboardMenu }) { Text("命令") }
                    if (state.canSend) TextButton(onClick = { dropNote = true }) { Text("发给自己") }
                }
                if (confirmUnpair) {
                    Text("解除后会清除本机收件箱和设备凭证。")
                    TextButton(onClick = {
                        scope.launch {
                            controller.unpair()
                            if (!controller.paired()) PushRegistration.disable(context)
                            confirmUnpair = false
                        }
                    }) { Text("确认解除配对") }
                }
                if (keyboardMenu) {
                    Text("Ctrl/⌘+F 搜索 · Ctrl/⌘+C 复制验证码 · Ctrl/⌘+K 命令")
                    TextButton(onClick = { searchFocus.requestFocus() }) { Text("聚焦搜索") }
                }
                OutlinedTextField(query, { query = it.take(200) }, modifier = Modifier.focusRequester(searchFocus),
                    label = { Text("搜索消息") }, singleLine = true)
                TextButton(enabled = !state.busy, onClick = { scope.launch { controller.search(query) } }) { Text("搜索") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.rows.isEmpty()) Text(if (state.busy) "正在读取消息…" else "暂无消息")
            }
            items(state.rows, key = { it.id }) { message ->
                Card(onClick = { open(message.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(message.title.ifBlank { message.source }, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(message.source, message.category).filter(String::isNotBlank).joinToString(" · "))
                        if (message.preview.isNotBlank()) Text(message.preview, maxLines = 2)
                        if (!message.read) Text("未读", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    val detail: @Composable () -> Unit = {
        val bookPaging = if (fold.posture == Posture.Book) Modifier.pointerInput(selection, state.rows) {
            var edge = false
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { where -> edge = where.x >= size.width - 48.dp.toPx(); drag = 0f },
                onHorizontalDrag = { change, amount -> if (edge) { change.consume(); drag += amount } },
                onDragEnd = {
                    if (edge && kotlin.math.abs(drag) > 72.dp.toPx()) {
                        val index = state.rows.indexOfFirst { it.id == selection }
                        val next = state.rows.getOrNull(index + if (drag < 0f) 1 else -1)
                        if (next != null) open(next.id)
                    }
                },
            )
        } else Modifier
        Column(bookPaging.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val message = state.detail
            if (message == null) {
                Text("选择一条消息查看详情")
            } else {
                Text(message.title.ifBlank { message.source }, style = MaterialTheme.typography.headlineSmall)
                Text(listOf(message.source, message.category, message.status).filter(String::isNotBlank).joinToString(" · "))
                message.otp?.takeIf { fold.posture == Posture.Cover && it.isNotBlank() }?.let { code ->
                    CoverCode(message, code) { copyVerificationCode(context, code) }
                }
                Text(message.body ?: "正文已过期或已删除")
                if (mode != PaneMode.TopBottom) {
                    MessageActions(controller, message)
                    if (mode == PaneMode.TwoPane && initialMessage == null) {
                        TextButton(onClick = { openAdjacent(context, message.id) }) { Text("在旁边打开") }
                    }
                }
            }
        }
    }
    if (mode == PaneMode.TopBottom) {
        HingeAwareSplit(fold, SplitAxis.Vertical, Modifier.fillMaxSize(), first = detail, second = list)
    } else {
        AdaptiveListDetail(fold, mode == PaneMode.TwoPane, state.detail != null, {
            selection = null
            controller.hideDetail()
        }, list, detail)
    }
}

@Composable
private fun PairPane(state: InboxState, onPair: (PairingInput, String) -> Unit) {
    val context = LocalContext.current
    var server by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<PairingInput?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val digits = state.pairingNumber
        Text("配对收件设备", style = MaterialTheme.typography.headlineSmall)
        when {
            digits != null -> {
                Text("请在 Telegram 或服务器上选择这个数字")
                Text(digits, style = MaterialTheme.typography.displayLarge)
            }
            state.pairing -> Text("正在连接服务器…")
            selected != null -> {
                val input = selected!!
                Text("确认服务器地址：${input.server.host}")
                Button(onClick = {
                    selected = null
                    code = ""
                    onPair(input, name)
                }) { Text("确认配对") }
                TextButton(onClick = { selected = null }) { Text("返回") }
            }
            else -> {
                Text("使用管理员提供的设备配对二维码，或手动输入服务器地址和配对码。")
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("设备名称") }, singleLine = true)
                Button(onClick = {
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { barcode ->
                            selected = runCatching { PairingInput.scanned(barcode.rawValue.orEmpty()) }
                                .onFailure { error = "设备配对二维码无效。" }.getOrNull()
                        }.addOnFailureListener { error = "二维码扫描不可用，可以输入配对码。" }
                }) { Text("扫描配对二维码") }
                OutlinedTextField(server, { server = it.take(256) }, label = { Text("HTTPS 服务器地址") }, singleLine = true)
                OutlinedTextField(code, { code = it.take(64) }, label = { Text("配对码") }, singleLine = true)
                TextButton(onClick = {
                    selected = runCatching { PairingInput.manual(server, code) }
                        .onFailure { error = "请检查 HTTPS 地址和配对码。" }.getOrNull()
                }) { Text("使用配对码") }
            }
        }
        (state.error ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

internal object InboxIdle {
    private val at = AtomicLong(0)
    fun touch() { at.set(SystemClock.elapsedRealtime()) }
    fun recent() = SystemClock.elapsedRealtime() - at.get() < 60_000
}

@Composable
private fun MessageActions(controller: DeviceInbox, message: InboxMessage) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Row {
        TextButton(onClick = { scope.launch { controller.markRead(message.id, !message.read) } }) {
            Text(if (message.read) "标为未读" else "标为已读")
        }
        message.otp?.takeIf { it.isNotBlank() }?.let { code ->
            TextButton(onClick = { copyVerificationCode(context, code) }) { Text("复制验证码") }
        }
    }
}

private fun copyVerificationCode(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("猫邮局验证码", code)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    clipboard.setPrimaryClip(clip)
    val timestamp = clipboard.primaryClipDescription?.timestamp ?: return
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        if (clipboard.primaryClipDescription?.timestamp == timestamp) clipboard.clearPrimaryClip()
    }, 60_000)
}
