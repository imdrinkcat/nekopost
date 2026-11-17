package com.drinkcat.nekopost.inbox

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.asImageBitmap
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.ui.settings.AdminDevice
import com.drinkcat.nekopost.ui.settings.AdminKey
import com.drinkcat.nekopost.ui.settings.AdminPage
import com.drinkcat.nekopost.ui.settings.AdminSource
import com.drinkcat.nekopost.ui.settings.AdminUi
import com.drinkcat.nekopost.ui.settings.AuditLine
import com.drinkcat.nekopost.ui.settings.DeviceRole
import com.drinkcat.nekopost.ui.settings.PairingRole
import com.drinkcat.nekopost.ui.settings.PairingUi
import com.drinkcat.nekopost.ui.settings.ServerStatusUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.security.Signature

/**
 * 设置 › 设备与管理 on an admin phone (design §3.7): device list, 添加设备 with
 * the pairing desk, sources, admin credentials, audit and server status.
 * Every high-risk call is signed with k_reauth through [authorize] (a strong
 * biometric prompt); a cancelled prompt is not an error.
 */
internal class AdminController(
    private val inbox: DeviceInbox,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    private val mutable = MutableStateFlow(AdminUi())
    val state: StateFlow<AdminUi> = mutable.asStateFlow()

    /** The k_reauth prompt; set by the screen (it needs an activity). */
    var authorize: suspend (Signature) -> Signature? = { null }
    private var poll: Job? = null

    /** The POST behind 添加设备: leaving the page cancels it, so no desk opens (or polls) behind the user's back. */
    private var adding: Job? = null

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    fun load(page: AdminPage) {
        scope.launch {
            try {
                when (page) {
                    AdminPage.Devices -> loadDevices()
                    AdminPage.Sources, AdminPage.AddDevice -> loadSources()
                    AdminPage.Keys -> {
                        val keys = inbox.manage("GET", listOf("v1", "admin", "keys")).optJSONArray("keys").objects().map {
                            AdminKey(it.field("name"), it.field("prefix"))
                        }
                        mutable.update { it.copy(keys = keys) }
                    }
                    AdminPage.Audit -> {
                        val reply = inbox.manage("GET", listOf("v1", "admin", "audit"))
                        val lines = reply.optJSONArray("entries").objects().map {
                            AuditLine(it.field("actor"), it.field("action"), it.field("result"))
                        }
                        mutable.update { it.copy(audit = lines, auditOk = reply.optBoolean("chain_ok")) }
                    }
                    AdminPage.Server -> loadServer()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(feedback = failure(e)) }
            }
        }
    }

    private suspend fun loadDevices() {
        val self = inbox.state.value.deviceId
        val devices = inbox.manage("GET", listOf("v1", "admin", "devices")).optJSONArray("devices").objects().mapNotNull {
            val id = it.optString("id")
            if (!DeviceId.matches(id)) return@mapNotNull null
            val scopes = it.optString("scopes").split(",").toSet()
            AdminDevice(
                id = id,
                name = it.optString("name"),
                model = it.optString("model"),
                role = when {
                    "admin:write" in scopes -> DeviceRole.Full
                    "msg:read" in scopes && "msg:send" !in scopes -> DeviceRole.Receiver
                    "msg:send" in scopes && "msg:read" !in scopes -> DeviceRole.Sender
                    else -> DeviceRole.Other
                },
                pushState = it.optString("push_state"),
                revoked = it.optBoolean("revoked"),
                suspended = it.optBoolean("suspended"),
                self = id == self,
            )
        }.sortedWith(compareBy<AdminDevice> { it.revoked }.thenByDescending { it.self })
        mutable.update { it.copy(devices = devices) }
    }

    private suspend fun loadSources() {
        val sources = inbox.manage("GET", listOf("v1", "admin", "sources")).optJSONArray("sources").objects().mapNotNull {
            val id = it.optString("id")
            if (!SourceIdPattern.matches(id)) null else AdminSource(id, it.optString("name"), it.optBoolean("enabled", true))
        }
        mutable.update { it.copy(sources = sources) }
    }

    private suspend fun loadServer() {
        val status = inbox.manage("GET", listOf("v1", "admin", "status")).optJSONObject("report")
        val stats = runCatching { inbox.manage("GET", listOf("v1", "admin", "stats")).optJSONObject("totals") }.getOrNull()
        val settings = runCatching { inbox.manage("GET", listOf("v1", "admin", "settings")) }.getOrNull()
        mutable.update {
            it.copy(
                server = ServerStatusUi(
                    overall = status?.optString("overall_text").orEmpty(),
                    components = status?.optJSONArray("components").objects().map { c -> c.optString("name") to c.optString("summary") },
                    received = stats?.optInt("received"),
                    delivered = stats?.optInt("delivered"),
                    filtered = stats?.optInt("filtered"),
                    version = settings?.optString("version")?.takeIf(String::isNotBlank),
                    timezone = settings?.optString("timezone")?.takeIf(String::isNotBlank),
                    retentionDays = settings?.takeIf { s -> s.has("retention_days") }?.optInt("retention_days"),
                ),
            )
        }
    }

    /** Runs an operation; [high] ones are signed with k_reauth first. */
    private fun run(high: Boolean, done: String?, reload: AdminPage?, block: suspend (auth: (suspend (Signature) -> Signature?)?) -> JSONObject) {
        scope.launch {
            mutable.update { it.copy(busy = true, feedback = null) }
            try {
                val reply = block(if (high) authorize else null)
                val secret = reply.optString("token").takeIf(String::isNotBlank)
                mutable.update { it.copy(feedback = done, secret = secret ?: it.secret) }
                reload?.let { load(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthCancelled) {
                // Cancelled prompt: say nothing.
            } catch (e: Exception) {
                mutable.update { it.copy(feedback = failure(e)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun testPush(id: String) = run(false, text(R.string.admin_test_push_sent), null) {
        inbox.manage("POST", listOf("v1", "admin", "devices", id, "test-push"), JSONObject())
    }

    fun revokeDevice(id: String) = run(true, text(R.string.admin_revoked), AdminPage.Devices) { auth ->
        inbox.manage("DELETE", listOf("v1", "admin", "devices", id), authenticate = auth)
    }

    fun createSource(id: String, name: String) = run(true, text(R.string.admin_done), AdminPage.Sources) { auth ->
        inbox.manage("POST", listOf("v1", "admin", "sources"), JSONObject().put("id", id).put("name", name), auth)
    }

    fun setSourceEnabled(id: String, enabled: Boolean) = run(true, text(R.string.admin_done), AdminPage.Sources) { auth ->
        inbox.manage("PATCH", listOf("v1", "admin", "sources", id), JSONObject().put("enabled", enabled), auth)
    }

    fun rotateSource(id: String) = run(true, text(R.string.admin_rotated), AdminPage.Sources) { auth ->
        inbox.manage("POST", listOf("v1", "admin", "sources", id, "rotate"), JSONObject(), auth)
    }

    fun deleteSource(id: String) = run(true, text(R.string.admin_done), AdminPage.Sources) { auth ->
        inbox.manage("DELETE", listOf("v1", "admin", "sources", id), authenticate = auth)
    }

    fun createKey(name: String) = run(true, null, AdminPage.Keys) { auth ->
        inbox.manage("POST", listOf("v1", "admin", "keys"), JSONObject().put("name", name), auth)
    }

    fun revokeKey(prefix: String) = run(true, text(R.string.admin_revoked), AdminPage.Keys) { auth ->
        inbox.manage("DELETE", listOf("v1", "admin", "keys", prefix), authenticate = auth)
    }

    fun dismissSecret() = mutable.update { it.copy(secret = null) }

    /** POST /v1/admin/pairings (k_reauth), then follows the pairing until it is done. */
    fun addDevice(name: String, role: PairingRole, source: String?) {
        adding?.cancel()
        adding = scope.launch {
            mutable.update { it.copy(busy = true, feedback = null) }
            try {
                val body = JSONObject().put("name", name).put("role", role.wire)
                if (role != PairingRole.Receiver) body.put("source", source ?: newSourceId())
                val reply = inbox.manage("POST", listOf("v1", "admin", "pairings"), body, authorize)
                val id = reply.optString("id")
                require(pairPattern.matches(id))
                val qr = runCatching {
                    val bytes = Base64.decode(reply.getString("qr_png"), Base64.DEFAULT)
                    require(bytes.size < 65536)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
                val expires = Instant.now().plusSeconds(reply.optLong("expires_in", 300).coerceIn(0, 3600))
                mutable.update {
                    it.copy(pairing = PairingUi(id = id, name = name, shortCode = reply.optString("short_code"), qr = qr, expiresAt = expires))
                }
                follow(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthCancelled) {
                // Cancelled prompt: nothing created.
            } catch (e: Exception) {
                mutable.update { it.copy(feedback = failure(e)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun follow(id: String) {
        poll?.cancel()
        poll = scope.launch {
            while (true) {
                try {
                    val s = inbox.manage("GET", listOf("v1", "admin", "pairings", id))
                    val choices = s.optJSONArray("choices").strings().filter { Regex("[0-9]{2}").matches(it) }
                    val state = s.optString("state")
                    val expires = InboxMessage.parseTimestamp(s.optString("expires_at"))
                    mutable.update { ui ->
                        val p = ui.pairing?.takeIf { it.id == id } ?: return@update ui
                        ui.copy(
                            pairing = p.copy(
                                state = state,
                                choices = choices,
                                claimedBy = listOf(s.optString("name"), s.optString("model")).filter(String::isNotBlank).joinToString(" · ").ifBlank { null },
                                expiresAt = expires ?: p.expiresAt,
                                checkedAt = Instant.now(),
                            ),
                        )
                    }
                    if (state in setOf("done", "rejected", "contested", "expired", "cancelled")) break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Try again; the desk keeps showing the last known state.
                }
                delay(2_000)
            }
        }
    }

    /** Number matching: the digit the new phone shows (k_reauth). */
    fun choose(choice: String) {
        val p = mutable.value.pairing ?: return
        scope.launch {
            mutable.update { it.copy(pairing = it.pairing?.copy(deciding = true, result = null)) }
            try {
                val reply = inbox.manage("POST", listOf("v1", "admin", "pairings", p.id, "decision"), JSONObject().put("choice", choice), authorize)
                val result = text(if (reply.optBoolean("approved")) R.string.pairing_approved else R.string.pairing_rejected)
                mutable.update { it.copy(pairing = it.pairing?.copy(result = result)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthCancelled) {
                // Choose again.
            } catch (e: Exception) {
                mutable.update { it.copy(pairing = it.pairing?.copy(result = failure(e))) }
            } finally {
                mutable.update { it.copy(pairing = it.pairing?.copy(deciding = false)) }
            }
        }
    }

    /** 作废配对码: DELETE /v1/admin/pairings/{id} while nobody has claimed it (no biometric needed). */
    fun cancelPairing() {
        val p = mutable.value.pairing ?: return
        scope.launch {
            try {
                inbox.manage("DELETE", listOf("v1", "admin", "pairings", p.id))
                poll?.cancel()
                poll = null
                mutable.update { ui -> ui.copy(pairing = ui.pairing?.takeIf { it.id == p.id }?.copy(state = "cancelled")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { ui -> ui.copy(pairing = ui.pairing?.copy(result = failure(e))) }
            }
        }
    }

    fun closePairing() {
        adding?.cancel()
        adding = null
        poll?.cancel()
        poll = null
        mutable.update { it.copy(pairing = null) }
    }

    private fun failure(e: Exception): String = when {
        e is ApiFailure && e.code == "reauth_required" -> text(R.string.admin_error_reauth)
        e is ApiFailure && e.status == 429 -> text(R.string.admin_error_rate)
        else -> inbox.failureText(e)
    }

    private companion object {
        val DeviceId = Regex("d_[A-Z2-7]{26}")
        val SourceIdPattern = Regex("[a-z0-9][a-z0-9_-]{0,31}")
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** 「新来源」: created by the server when the pairing completes (like the bot's device-xxxxxxxx). */
        fun newSourceId(): String {
            val random = SecureRandom()
            return "device-" + (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** Admin keys come back as Go structs: accept both `Name` and `name`. */
private fun JSONObject.field(name: String): String =
    optString(name).ifBlank { optString(name.replaceFirstChar { it.uppercase() }) }
