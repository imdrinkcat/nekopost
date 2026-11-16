package com.drinkcat.nekopost.inbox

import android.content.Context
import android.os.Build
import com.drinkcat.nekopost.BuildConfig
import com.drinkcat.nekopost.forwarder.upload.ForwarderDeviceAuth
import okhttp3.HttpUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.Signature
import java.time.Duration
import java.time.Instant

internal data class InboxMessage(
    val id: String,
    val seq: Long,
    val title: String,
    val preview: String,
    val source: String,
    val category: String,
    val status: String,
    val received: String,
    val expiry: String?,
    val hasCode: Boolean,
    val read: Boolean,
    val body: String?,
    val otp: String?,
) {
    companion object {
        fun parse(json: JSONObject, clock: Instant = Instant.now()): InboxMessage {
            val id = json.getString("id")
            require(messagePattern.matches(id))
            val source = json.optJSONObject("source")
            val expiry = json.optString("body_expires_at").takeIf { !json.isNull("body_expires_at") && it.isNotBlank() }
            val live = expiry?.let { runCatching { Instant.parse(it).isAfter(clock) }.getOrDefault(false) } == true
            return InboxMessage(
                id, json.optLong("seq"), if (live) json.optString("title") else "", if (live) json.optString("preview") else "",
                source?.optString("name")?.takeIf(String::isNotBlank) ?: source?.optString("id").orEmpty(),
                json.optString("category_text"), json.optString("status_text"), json.optString("received_at"),
                expiry, json.optBoolean("has_code"), json.optBoolean("read"),
                if (live && json.has("body")) json.optString("body") else null,
                if (live) json.optJSONObject("otp")?.optString("code") else null,
            )
        }
    }
}

internal data class InboxState(
    val server: String? = null,
    val canRead: Boolean = false,
    val canAdmin: Boolean = false,
    val canSend: Boolean = false,
    val rows: List<InboxMessage> = emptyList(),
    val detail: InboxMessage? = null,
    val pairingNumber: String? = null,
    val pairing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

internal class DeviceInbox private constructor(
    private val appContext: Context,
    private val keys: DeviceKeys,
    private val vault: DeviceVault,
    private val api: DeviceApi,
    private val serial: Mutex,
) {
    constructor(context: Context) : this(context.applicationContext, DeviceKeys(), DeviceVault(context.applicationContext), Mutex())
    private constructor(context: Context, keys: DeviceKeys, vault: DeviceVault, serial: Mutex) :
        this(context, keys, vault, DeviceApi(keys), serial)

    fun newWindow() = DeviceInbox(appContext, keys, vault, api, serial)
    private val mutable = MutableStateFlow(InboxState())
    val state: StateFlow<InboxState> = mutable.asStateFlow()
    val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun session(): DeviceSession? = vault.read("session")?.let(DeviceSession::decode)

    fun signForwarder(server: HttpUrl, source: String, target: String, at: Instant, nonce: String?): ForwarderDeviceAuth.Proof? {
        val s = session() ?: return null
        if ("msg:send" !in s.scopes || s.source != source || s.server.scheme != server.scheme || s.server.host != server.host ||
            s.server.port != server.port) return null
        return ForwarderDeviceAuth.Proof(s.token, keys.proof("POST", target, s.token, at.epochSecond, nonce))
    }

    suspend fun paired(): Boolean = withContext(Dispatchers.IO) { runCatching { session() != null }.getOrDefault(false) }

    suspend fun canReceive(): Boolean = withContext(Dispatchers.IO) {
        runCatching { session()?.scopes?.contains("msg:read") == true }.getOrDefault(false)
    }

    private fun initial(s: DeviceSession) = InboxState(
        server = s.server.host,
        canRead = "msg:read" in s.scopes,
        canAdmin = "admin:read" in s.scopes,
        canSend = "msg:send" in s.scopes,
    )

    suspend fun load() = onlyWhenIdle {
        val session = session() ?: return@onlyWhenIdle
        mutable.value = initial(session)
        if (!mutable.value.canRead) return@onlyWhenIdle
        val cache = vault.read("list")?.let(::JSONObject)
        pruneDetails()
        if (cache != null) {
            val at = Instant.ofEpochMilli(cache.optLong("at"))
            if (Duration.between(at, Instant.now()) <= Duration.ofHours(72)) {
                mutable.value = mutable.value.copy(rows = rows(cache.optJSONArray("items")))
            } else {
                vault.remove("list")
            }
        }
        refreshLocked(session)
    }

    suspend fun pair(input: PairingInput, name: String) = onlyWhenIdle {
        if (session() != null) return@onlyWhenIdle
        mutable.value = InboxState(pairing = true, busy = true)
        keys.init()
        val claim = api.call(input.server, "POST", listOf("v1", "pairing", "claim"),
            JSONObject().put("code", input.code).put("jwk", keys.jwk()).put("reauth_jwk", keys.jwk(true))
                .put("device", JSONObject().put("name", name.trim().take(64))
                    .put("model", Build.MODEL.orEmpty().take(64)).put("app_version", BuildConfig.VERSION_NAME))
        ).json
        val id = claim.getString("pairing_id")
        require(pairPattern.matches(id))
        val digits = claim.getString("short_code")
        require(Regex("[0-9]{2}").matches(digits))
        mutable.value = mutable.value.copy(pairingNumber = digits)
        repeat(145) {
            val reply = api.call(input.server, "POST", listOf("v1", "pairing", id, "token"), JSONObject(), proof = true)
            if (reply.status == 200) {
                val scopes = reply.json.optJSONArray("scopes") ?: JSONArray()
                val linked = DeviceSession(input.server, reply.json.getString("device_id"),
                    reply.json.getString("token"), reply.json.optString("source_id"),
                    (0 until scopes.length()).map { scopes.getString(it) }.toSet())
                vault.write("session", linked.encode())
                mutable.value = initial(linked)
                if (mutable.value.canRead) refreshLocked(linked)
                return@onlyWhenIdle
            }
            delay(2000)
        }
        error("配对确认已过期")
    }

    suspend fun refresh() = onlyWhenIdle { session()?.let { refreshLocked(it) } }

    private suspend fun refreshLocked(s: DeviceSession) {
        val reply = api.call(s.server, "GET", listOf("v1", "inbox"), token = s.token).json
        val received = reply.optJSONArray("items") ?: JSONArray()
        vault.write("list", JSONObject().put("at", Instant.now().toEpochMilli()).put("items", received).toString())
        for (i in 0 until received.length()) {
            val row = received.optJSONObject(i) ?: continue
            if (row.optString("body_state") != "stored") vault.remove("body:" + row.optString("id"))
        }
        pruneDetails()
        mutable.value = mutable.value.copy(server = s.server.host, rows = rows(received), error = null)
    }

    suspend fun search(text: String) = onlyWhenIdle {
        val s = session() ?: return@onlyWhenIdle
        val query = text.trim().take(200)
        if (query.isEmpty()) { refreshLocked(s); return@onlyWhenIdle }
        val result = api.call(s.server, "POST", listOf("v1", "inbox", "search"),
            JSONObject().put("q", query), s.token).json
        mutable.value = mutable.value.copy(rows = rows(result.optJSONArray("items")), detail = null)
    }

    suspend fun detail(id: String) = onlyWhenIdle {
        require(messagePattern.matches(id))
        val s = session() ?: return@onlyWhenIdle
        val key = "body:$id"
        val item = try {
            val raw = api.call(s.server, "GET", listOf("v1", "inbox", id), token = s.token).json
            saveDetail(raw)
            InboxMessage.parse(raw)
        } catch (e: ApiFailure) {
            if (e.status == 404 || e.status == 403) vault.remove(key)
            throw e
        } catch (e: IOException) {
            val offline = vault.read(key)?.let(::JSONObject) ?: throw e
            if (!cacheLive(offline)) { vault.remove(key); throw e }
            InboxMessage.parse(offline.getJSONObject("item"))
        }
        mutable.value = mutable.value.copy(detail = item)
    }

    private fun cacheLive(record: JSONObject): Boolean {
        val now = Instant.now()
        val at = Instant.ofEpochMilli(record.optLong("at"))
        val item = record.optJSONObject("item") ?: return false
        return at <= now && Duration.between(at, now) <= Duration.ofHours(72) && InboxMessage.parse(item, now).body != null
    }

    private fun pruneDetails() {
        val keys = vault.detailKeys()
        val keep = keys.mapNotNull { key ->
            runCatching {
                val item = JSONObject(vault.read(key) ?: return@runCatching null)
                if (cacheLive(item)) key to item.optLong("at") else null
            }.getOrNull()
        }.sortedByDescending { it.second }.take(100).map { it.first }.toSet()
        (keys - keep).forEach(vault::remove)
    }

    private fun saveDetail(item: JSONObject) {
        val parsed = InboxMessage.parse(item)
        val key = "body:" + parsed.id
        if (parsed.body == null) vault.remove(key)
        else vault.write(key, JSONObject().put("at", Instant.now().toEpochMilli()).put("item", item).toString())
    }

    fun hideDetail() {
        mutable.value = mutable.value.copy(detail = null)
    }

    fun hideContent() {
        mutable.value = mutable.value.copy(rows = emptyList(), detail = null)
    }

    suspend fun markRead(id: String, read: Boolean) = onlyWhenIdle {
        require(messagePattern.matches(id))
        val s = session() ?: return@onlyWhenIdle
        api.call(s.server, "PATCH", listOf("v1", "inbox", id), JSONObject().put("read", read), s.token)
        if (read) androidx.core.app.NotificationManagerCompat.from(appContext).cancel(id, 1)
        mutable.value = mutable.value.copy(
            rows = mutable.value.rows.map { if (it.id == id) it.copy(read = read) else it },
            detail = mutable.value.detail?.takeIf { it.id == id }?.copy(read = read),
        )
    }

    suspend fun unpair() = onlyWhenIdle {
        val s = session() ?: return@onlyWhenIdle
        api.call(s.server, "DELETE", listOf("v1", "devices", "self"), token = s.token)
        clear()
    }

    suspend fun registerPush(token: String): Boolean = withContext(Dispatchers.IO) {
        serial.withLock {
            val s = session() ?: return@withLock true
            try {
                api.call(s.server, "PUT", listOf("v1", "devices", "self", "push"),
                    JSONObject().put("token", token), s.token)
                true
            } catch (e: ApiFailure) {
                if (e.code == "device_revoked") clear()
                e.status in listOf(400, 401, 403)
            }
        }
    }

    suspend fun messageForPush(id: String): InboxMessage? = withContext(Dispatchers.IO) {
        serial.withLock {
            require(messagePattern.matches(id))
            val s = session() ?: return@withLock null
            try {
                val msg = api.call(s.server, "GET", listOf("v1", "inbox", id), token = s.token).json
                saveDetail(msg)
                pruneDetails()
                api.call(s.server, "POST", listOf("v1", "devices", "self", "acks"),
                    JSONObject().put("ids", JSONArray().put(id)), s.token)
                InboxMessage.parse(msg)
            } catch (e: ApiFailure) {
                if (e.code == "device_revoked") clear()
                null
            }
        }
    }

    suspend fun trackForeground() = withContext(Dispatchers.IO) {
        var after = mutable.value.rows.maxOfOrNull { it.seq } ?: 0L
        while (currentCoroutineContext().isActive) {
            val s = session() ?: return@withContext
            try {
                api.events(s, after) { seq ->
                    after = seq ?: 0L
                    updates.tryEmit(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiFailure) {
                if (e.status == 401) {
                    verifySelf()
                } else if (e.status == 403) {
                    hideContent()
                }
            } catch (_: Exception) {
                // Reconnect after transient network loss.
            }
            delay(3000)
        }
    }

    suspend fun verifySelf(): Boolean = withContext(Dispatchers.IO) {
        serial.withLock {
            val s = session() ?: return@withLock false
            try {
                api.call(s.server, "GET", listOf("v1", "devices", "self"), token = s.token)
                true
            } catch (e: ApiFailure) {
                if (e.code == "device_revoked") clear()
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun sendNote(note: String) {
        require(note.isNotBlank() && note.length <= 12000)
        manage("POST", listOf("v1", "messages"), JSONObject().put("type", "note").put("body", note)
            .put("idempotency_key", java.util.UUID.randomUUID().toString()))
    }

    suspend fun manage(
        method: String,
        path: List<String>,
        data: JSONObject? = null,
        authenticate: (suspend (Signature) -> Signature?)? = null,
    ): JSONObject {
        val current = withContext(Dispatchers.IO) { session() } ?: error("设备未配对")
        if (authenticate != null) {
            val challenge = api.call(current.server, "POST", listOf("v1", "devices", "self", "reauth", "challenge"),
                JSONObject(), current.token).json.getString("challenge")
            val signer = withContext(Dispatchers.IO) { keys.biometricSignature() }
            val approved = authenticate(signer) ?: error("未完成指纹验证")
            val signed = withContext(Dispatchers.IO) {
                approved.update("nekopost/reauth/v1|${current.deviceID}|$challenge".toByteArray())
                approved.sign().url64()
            }
            api.call(current.server, "POST", listOf("v1", "devices", "self", "reauth"),
                JSONObject().put("challenge", challenge).put("sig", signed), current.token)
        }
        return serial.withLock {
            val latest = withContext(Dispatchers.IO) { session() }
            check(latest?.deviceID == current.deviceID)
            api.call(current.server, method, path, data, current.token).json
        }
    }

    private fun clear() {
        vault.clear()
        keys.clear()
        mutable.value = InboxState()
    }

    private suspend fun onlyWhenIdle(work: suspend () -> Unit) = withContext(Dispatchers.IO) {
        if (!serial.tryLock()) return@withContext
        try {
            mutable.value = mutable.value.copy(busy = true, error = null)
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            if (e.code == "device_revoked") clear()
            mutable.value = mutable.value.copy(error = when (e.code) {
                "device_revoked" -> "此设备已被吊销，请重新配对。"
                "pairing_contested" -> "配对码已发生争用，请重新生成。"
                "locked_down" -> "设备读取已被暂停。"
                "pairing_not_found", "pairing_gone" -> "配对码无效或已过期。"
                else -> e.message
            })
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "操作未完成，请检查网络、设备安全设置或配对码。")
        } finally {
            mutable.value = mutable.value.copy(busy = false, pairing = false, pairingNumber = null)
            serial.unlock()
        }
    }

    private fun rows(arr: JSONArray?) = List(arr?.length() ?: 0) { i -> InboxMessage.parse(arr!!.getJSONObject(i)) }
}
