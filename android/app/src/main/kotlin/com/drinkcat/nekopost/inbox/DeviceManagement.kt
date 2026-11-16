package com.drinkcat.nekopost.inbox

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.security.Signature
import kotlin.coroutines.resume

private enum class AdminTab(val label: String, val segment: String) {
    Sources("来源", "sources"), Devices("设备", "devices"), Keys("管理凭证", "keys"),
    Status("状态", "status"), Stats("统计", "stats"), Security("审计", "audit"), Settings("设置", "settings"),
}

@Composable
internal fun DeviceManagement(controller: DeviceInbox, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(AdminTab.Sources) }
    var pairing by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }

    suspend fun reload() {
        result = controller.manage("GET", listOf("v1", "admin", tab.segment))
    }

    fun action(method: String, path: List<String>, data: JSONObject? = null, high: Boolean = false) {
        scope.launch {
            pending = true
            feedback = null
            try {
                val response = controller.manage(method, path, data, if (high) ({ signature ->
                    authorizeOperation(context, signature)
                }) else null)
                if (response.has("qr_png")) pairing = response
                feedback = when {
                    response.has("token") -> "请现在保存凭证，页面只显示一次\n" + response.getString("token")
                    response.has("short_code") -> "手动配对码 " + response.getString("short_code") + "（5 分钟有效）"
                    else -> "操作已完成"
                }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiFailure) {
                feedback = e.message
            } catch (_: Exception) {
                feedback = "操作未完成。若设备验证失败，请检查指纹设置。"
            } finally {
                pending = false
            }
        }
    }

    LaunchedEffect(tab) {
        result = null
        pairing = null
        confirm = null
        feedback = null
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = "无法读取此管理页面。"
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = onBack) { Text("返回收件箱") }
            Text("管理", style = MaterialTheme.typography.headlineSmall)
            AdminTab.entries.chunked(3).forEach { tabs ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabs.forEach { current ->
                        FilterChip(selected = tab == current, onClick = { if (!pending) tab = current }, label = { Text(current.label) })
                    }
                }
            }
            feedback?.let { Text(it) }
            confirm?.let { submit ->
                Text("确认执行此操作？")
                Row {
                    TextButton(enabled = !pending, onClick = { confirm = null; submit() }) { Text("确认") }
                    TextButton(onClick = { confirm = null }) { Text("取消") }
                }
            }
        }
        when (tab) {
            AdminTab.Sources -> {
                item {
                    OutlinedTextField(source, { source = it.take(32) }, label = { Text("来源 ID") }, singleLine = true)
                    OutlinedTextField(name, { name = it.take(64) }, label = { Text("来源名称") }, singleLine = true)
                    Button(enabled = !pending && sourceID.matches(source), onClick = {
                        action("POST", listOf("v1", "admin", "sources"), JSONObject().put("id", source).put("name", name), true)
                    }) { Text("新增来源") }
                }
                items(result?.optJSONArray("sources").objects()) { entry ->
                    val id = entry.optString("id")
                    if (sourceID.matches(id)) {
                        Column {
                            Text(entry.optString("name") + " · " + id)
                            Row {
                                TextButton(enabled = !pending && name.isNotBlank(), onClick = {
                                    action("PATCH", listOf("v1", "admin", "sources", id), JSONObject().put("name", name))
                                }) { Text("改名") }
                                TextButton(enabled = !pending, onClick = {
                                    confirm = { action("PATCH", listOf("v1", "admin", "sources", id),
                                        JSONObject().put("enabled", !entry.optBoolean("enabled")), true) }
                                }) { Text(if (entry.optBoolean("enabled")) "停用" else "启用") }
                                TextButton(enabled = !pending, onClick = {
                                    confirm = { action("POST", listOf("v1", "admin", "sources", id, "rotate"), JSONObject(), true) }
                                }) { Text("轮换") }
                                TextButton(enabled = !pending, onClick = {
                                    confirm = { action("DELETE", listOf("v1", "admin", "sources", id), high = true) }
                                }) { Text("删除") }
                            }
                        }
                    }
                }
            }
            AdminTab.Devices -> {
                pairing?.let { draft -> item { PairingDesk(controller, draft) { pairing = null } } }
                item {
                    OutlinedTextField(source, { source = it.take(32) }, label = { Text("新设备的来源 ID") }, singleLine = true)
                    OutlinedTextField(name, { name = it.take(64) }, label = { Text("新设备名称") }, singleLine = true)
                    Button(enabled = !pending && name.isNotBlank() && sourceID.matches(source), onClick = {
                        action("POST", listOf("v1", "admin", "pairings"),
                            JSONObject().put("name", name).put("source", source).put("role", "full"), true)
                    }) { Text("创建设备配对") }
                }
                items(result?.optJSONArray("devices").objects()) { entry ->
                    val id = entry.optString("id")
                    if (deviceID.matches(id)) {
                        Column {
                            Text(entry.optString("name") + " · " + (if (entry.optBoolean("revoked")) "已吊销" else entry.optString("push_state")))
                            if (!entry.optBoolean("revoked")) {
                                Row {
                                    TextButton(enabled = !pending, onClick = {
                                        action("POST", listOf("v1", "admin", "devices", id, "test-push"), JSONObject())
                                    }) { Text("测试推送") }
                                    TextButton(enabled = !pending, onClick = {
                                        confirm = { action("DELETE", listOf("v1", "admin", "devices", id), high = true) }
                                    }) { Text("吊销") }
                                }
                            }
                        }
                    }
                }
            }
            AdminTab.Keys -> {
                item {
                    OutlinedTextField(name, { name = it.take(64) }, label = { Text("管理凭证名称") }, singleLine = true)
                    Button(enabled = !pending && name.isNotBlank(), onClick = {
                        action("POST", listOf("v1", "admin", "keys"), JSONObject().put("name", name), true)
                    }) { Text("创建管理凭证") }
                }
                items(result?.optJSONArray("keys").objects()) { entry ->
                    val prefix = entry.optString("Prefix")
                    Row {
                        Text(entry.optString("Name") + " · " + prefix, Modifier.weight(1f))
                        if (Regex("mga_[A-Za-z0-9_-]{6}").matches(prefix)) {
                            TextButton(enabled = !pending, onClick = {
                                confirm = { action("DELETE", listOf("v1", "admin", "keys", prefix), high = true) }
                            }) { Text("吊销") }
                        }
                    }
                }
            }
            AdminTab.Security -> {
                item { Text(if (result?.optBoolean("chain_ok") == true) "审计链校验通过" else "审计链状态未知") }
                items(result?.optJSONArray("entries").objects()) { entry ->
                    Text(entry.optString("actor") + " · " + entry.optString("action") + " · " + entry.optString("result"))
                }
            }
            AdminTab.Status -> {
                item { Text(result?.optJSONObject("report")?.optString("overall_text").orEmpty()) }
                items(result?.optJSONObject("report")?.optJSONArray("components").objects()) { entry ->
                    Text(entry.optString("name") + " · " + entry.optString("summary"))
                }
            }
            AdminTab.Stats -> item {
                val totals = result?.optJSONObject("totals")
                if (totals != null) Text("接收 ${totals.optInt("received")} · 送达 ${totals.optInt("delivered")} · 过滤 ${totals.optInt("filtered")}")
            }
            AdminTab.Settings -> item {
                val value = result
                if (value != null) Text("版本 ${value.optString("version")} · 时区 ${value.optString("timezone")} · 保留 ${value.optInt("retention_days")} 天")
            }
        }
    }
}

private val sourceID = Regex("[a-z0-9][a-z0-9_-]{0,31}")
private val deviceID = Regex("d_[A-Z2-7]{26}")
private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal suspend fun authorizeOperation(context: Context, signature: Signature): Signature? =
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            BiometricPrompt.Builder(context).setTitle("确认管理操作")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("取消", context.mainExecutor) { _, _ ->
                    if (continuation.isActive) continuation.resume(null)
                }.build().authenticate(BiometricPrompt.CryptoObject(signature), signal, context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(result.cryptoObject?.signature)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
        } catch (_: RuntimeException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }
