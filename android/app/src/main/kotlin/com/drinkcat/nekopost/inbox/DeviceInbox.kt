package com.drinkcat.nekopost.inbox

import android.content.Context
import android.os.Build
import com.drinkcat.nekopost.BuildConfig
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.forwarder.upload.ForwarderDeviceAuth
import com.drinkcat.nekopost.inbox.notify.InboxNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Collections

/** What `GET /v1/devices/self` says about this phone. */
internal data class DeviceSelf(
    val name: String,
    val pushState: String,
    /** null = Telegram receives every type; otherwise the types it receives (empty = none). */
    val telegramTypes: List<String>?,
    val pushEnabled: Boolean,
)

internal data class InboxState(
    /** null until the vault was read. */
    val paired: Boolean? = null,
    val server: String? = null,
    val serverUrl: String? = null,
    val deviceId: String? = null,
    val canRead: Boolean = false,
    val canAdmin: Boolean = false,
    val canSend: Boolean = false,
    val rows: List<InboxMessage> = emptyList(),
    val hasMore: Boolean = false,
    val unreadCount: Int? = null,
    /** Non-null while the rows are server search results for this query. */
    val searchQuery: String? = null,
    val detail: InboxMessage? = null,
    val detailLoading: Boolean = false,
    /** Nothing to show yet. */
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** A batch read change (多选 / 全部标为已读) is with the server. */
    val batchBusy: Boolean = false,
    /** The server could not be reached; [rows] come from the encrypted cache saved at this time. */
    val offlineSince: Instant? = null,
    val error: String? = null,
    val revoked: Boolean = false,
    val self: DeviceSelf? = null,
    val pairingNumber: String? = null,
    val pairing: Boolean = false,
)

/**
 * The receiving side of this phone: device session (DPoP), inbox list and
 * detail, read state, push fetches and admin calls. Every operation runs
 * through one [SerialQueue]: user actions wait for a push fetch instead of
 * being dropped, and a refresh is coalesced. The app lock never clears the
 * rows (only unpairing or revocation does); the content is hidden by the
 * lock overlay instead.
 */
internal class DeviceInbox private constructor(
    private val appContext: Context,
    private val keys: DeviceKeys,
    private val vault: DeviceVault,
    private val api: DeviceApi,
    private val queue: SerialQueue,
) {
    constructor(context: Context) : this(context.applicationContext, DeviceKeys(), DeviceVault(context.applicationContext), SerialQueue())
    private constructor(context: Context, keys: DeviceKeys, vault: DeviceVault, queue: SerialQueue) :
        this(context, keys, vault, DeviceApi(keys), queue)

    /** A second view on the same session (the adjacent window) with its own list and detail state. */
    fun newWindow() = DeviceInbox(appContext, keys, vault, api, queue)

    private val mutable = MutableStateFlow(InboxState())
    val state: StateFlow<InboxState> = mutable.asStateFlow()
    val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    private var cursor: Map<String, String>? = null
    private val pendingReads = Collections.synchronizedSet(mutableSetOf<String>())

    private fun session(): DeviceSession? = vault.read("session")?.let(DeviceSession::decode)

    private fun text(id: Int) = appContext.getString(id)

    fun signForwarder(server: HttpUrl, source: String, target: String, at: Instant, nonce: String?): ForwarderDeviceAuth.Proof? {
        val s = session() ?: return null
        if ("msg:send" !in s.scopes || s.source != source || s.server.scheme != server.scheme || s.server.host != server.host ||
            s.server.port != server.port) return null
        return ForwarderDeviceAuth.Proof(s.token, keys.proof("POST", target, s.token, at.epochSecond, nonce))
    }

    /** Whether a session is stored, without decrypting it (Application.onCreate). */
    fun hasStoredSession(): Boolean = runCatching { vault.contains("session") }.getOrDefault(false)

    suspend fun paired(): Boolean = withContext(Dispatchers.IO) { runCatching { session() != null }.getOrDefault(false) }

    suspend fun canReceive(): Boolean = withContext(Dispatchers.IO) {
        runCatching { session()?.scopes?.contains("msg:read") == true }.getOrDefault(false)
    }

    private fun identity(s: DeviceSession, from: InboxState) = from.copy(
        paired = true,
        server = s.server.host,
        serverUrl = s.server.toString(),
        deviceId = s.deviceID,
        canRead = "msg:read" in s.scopes,
        canAdmin = "admin:read" in s.scopes,
        canSend = "msg:send" in s.scopes,
        revoked = false,
    )

    /** Reads the session and the cached list, then refreshes from the server. */
    suspend fun load() = withContext(Dispatchers.IO) {
        val s = try {
            session()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (s == null) {
            mutable.update { InboxState(paired = false, error = it.error, revoked = it.revoked) }
            return@withContext
        }
        mutable.update { identity(s, it) }
        if (!mutable.value.canRead) return@withContext
        if (mutable.value.rows.isEmpty()) {
            val cache = runCatching { vault.read("list")?.let(::JSONObject) }.getOrNull()
            if (cache != null) {
                val at = Instant.ofEpochMilli(cache.optLong("at"))
                if (Duration.between(at, Instant.now()) <= Duration.ofHours(72)) {
                    val rows = rows(cache.optJSONArray("items"))
                    mutable.update { it.copy(rows = rows, offlineSince = at) }
                } else {
                    vault.remove("list")
                }
            }
            mutable.update { it.copy(loading = it.rows.isEmpty()) }
        }
        refresh()
    }

    /** Pull-to-refresh and live updates; re-runs the search while one is shown. */
    suspend fun refresh() {
        val query = mutable.value.searchQuery
        if (query != null) {
            search(query)
            return
        }
        withContext(Dispatchers.IO) {
            queue.coalesce("refresh") {
                val s = session() ?: return@coalesce
                mutable.update { it.copy(refreshing = true) }
                attempt { refreshLocked(s) }
                mutable.update { it.copy(refreshing = false, loading = false) }
            }
        }
    }

    private suspend fun refreshLocked(s: DeviceSession) {
        val reply = api.call(s.server, "GET", listOf("v1", "inbox"), token = s.token).json
        val received = reply.optJSONArray("items") ?: JSONArray()
        vault.write("list", JSONObject().put("at", Instant.now().toEpochMilli()).put("items", received).toString())
        for (i in 0 until received.length()) {
            val row = received.optJSONObject(i) ?: continue
            if (row.optString("body_state") != "stored") vault.remove("body:" + row.optString("id"))
        }
        pruneDetails()
        cursor = cursorOf(reply)
        val rows = rows(received)
        mutable.update {
            it.copy(
                server = s.server.host,
                rows = rows,
                hasMore = cursor != null,
                unreadCount = if (reply.has("unread_count")) reply.optInt("unread_count") else rows.count { r -> !r.read },
                searchQuery = null,
                offlineSince = null,
                error = null,
            )
        }
        InboxNotifier.reconcile(appContext, rows)
    }

    private fun cursorOf(reply: JSONObject): Map<String, String>? {
        val next = reply.optJSONObject("next_cursor") ?: return null
        val id = next.optString("before_id").takeIf(messagePattern::matches) ?: return null
        return mapOf("before" to next.optLong("before").toString(), "before_id" to id)
    }

    /** Infinite scroll: the next page of the list (not for search results). */
    suspend fun loadMore() = withContext(Dispatchers.IO) {
        if (cursor == null || mutable.value.searchQuery != null) return@withContext
        queue.coalesce("more") {
            val page = cursor ?: return@coalesce
            val s = session() ?: return@coalesce
            mutable.update { it.copy(loadingMore = true) }
            attempt {
                val reply = api.call(s.server, "GET", listOf("v1", "inbox"), token = s.token, query = page).json
                val more = rows(reply.optJSONArray("items"))
                cursor = cursorOf(reply)
                mutable.update { state ->
                    val known = state.rows.mapTo(HashSet()) { it.id }
                    state.copy(rows = state.rows + more.filter { it.id !in known }, hasMore = cursor != null)
                }
            }
            mutable.update { it.copy(loadingMore = false) }
        }
    }

    /** Server search (bounded by a 300 ms budget on the server); a blank query returns to the list. */
    suspend fun search(text: String) = withContext(Dispatchers.IO) {
        val query = text.trim().take(200)
        if (query.isEmpty()) {
            mutable.update { it.copy(searchQuery = null) }
            refresh()
            return@withContext
        }
        queue.run {
            val s = session() ?: return@run
            mutable.update { it.copy(refreshing = true) }
            attempt {
                val result = api.call(s.server, "POST", listOf("v1", "inbox", "search"), JSONObject().put("q", query), s.token).json
                mutable.update { it.copy(rows = rows(result.optJSONArray("items")), searchQuery = query, hasMore = false, error = null) }
            }
            mutable.update { it.copy(refreshing = false) }
        }
    }

    /** Opens a message: the row is shown at once, the body follows from the server (or the cache when offline). */
    suspend fun open(id: String) = withContext(Dispatchers.IO) {
        require(messagePattern.matches(id))
        val row = mutable.value.rows.firstOrNull { it.id == id }
        mutable.update {
            val keep = it.detail?.takeIf { d -> d.id == id }
            it.copy(detail = keep ?: row, detailLoading = true)
        }
        queue.run {
            attempt {
                val item = fetchDetail(id)
                mutable.update { state ->
                    if (state.detail?.id != id && state.detail != null) state
                    else state.copy(detail = item, rows = state.rows.map { r -> if (r.id == id) r.copy(read = item.read) else r })
                }
            }
        }
        mutable.update { it.copy(detailLoading = false) }
    }

    private suspend fun fetchDetail(id: String): InboxMessage {
        val s = session() ?: error("not paired")
        val key = "body:$id"
        return try {
            val raw = api.call(s.server, "GET", listOf("v1", "inbox", id), token = s.token).json
            saveDetail(raw)
            InboxMessage.parse(raw)
        } catch (e: ApiFailure) {
            if (e.status == 404 || e.status == 403) vault.remove(key)
            throw e
        } catch (e: IOException) {
            cachedDetail(id) ?: throw e
        }
    }

    /** The cached detail (no network): the notification's 复制验证码 action reads this. */
    fun cachedDetail(id: String): InboxMessage? {
        if (!messagePattern.matches(id)) return null
        val key = "body:$id"
        val offline = runCatching { vault.read(key)?.let(::JSONObject) }.getOrNull() ?: return null
        if (!cacheLive(offline)) {
            vault.remove(key)
            return null
        }
        return runCatching { InboxMessage.parse(offline.getJSONObject("item")) }.getOrNull()
    }

    /** The verification code of a message, for 复制验证码 on a row: detail, cache, then the server. */
    suspend fun codeFor(id: String): String? = withContext(Dispatchers.IO) {
        mutable.value.detail?.takeIf { it.id == id }?.otp?.let { return@withContext it }
        cachedDetail(id)?.otp?.let { return@withContext it }
        queue.run {
            try {
                fetchDetail(id).otp
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun cacheLive(record: JSONObject): Boolean {
        val now = Instant.now()
        val at = Instant.ofEpochMilli(record.optLong("at"))
        val item = record.optJSONObject("item") ?: return false
        return at <= now && Duration.between(at, now) <= Duration.ofHours(72) &&
            runCatching { InboxMessage.parse(item, now).body != null }.getOrDefault(false)
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
        mutable.update { it.copy(detail = null, detailLoading = false) }
    }

    /**
     * Marks a message read or unread. Optimistic; the request waits for the
     * queue (never dropped) and a repeat of the same request is coalesced.
     * Reading clears its notification.
     */
    suspend fun setRead(id: String, read: Boolean) = withContext(Dispatchers.IO) {
        require(messagePattern.matches(id))
        val key = "$id:$read"
        if (!pendingReads.add(key)) return@withContext
        try {
            applyRead(id, read)
            if (read) InboxNotifier.cancel(appContext, id)
            queue.run {
                val s = session() ?: return@run
                try {
                    api.call(s.server, "PATCH", listOf("v1", "inbox", id), JSONObject().put("read", read), s.token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    applyRead(id, !read)
                    mutable.update { it.copy(error = failureText(e)) }
                }
            }
        } finally {
            pendingReads.remove(key)
        }
    }

    private fun applyRead(id: String, read: Boolean) = mutable.update { state ->
        val before = state.rows.firstOrNull { it.id == id }
        val delta = when {
            before == null || before.read == read -> 0
            read -> -1
            else -> 1
        }
        state.copy(
            rows = state.rows.map { if (it.id == id) it.copy(read = read) else it },
            detail = state.detail?.let { if (it.id == id) it.copy(read = read) else it },
            unreadCount = state.unreadCount?.let { (it + delta).coerceAtLeast(0) },
        )
    }

    /**
     * 多选 标为已读/未读 and 全部标为已读 (server 0.10.2). Not optimistic: the rows
     * change only once the server accepted the batch, so a failure (offline,
     * locked down, rate limited) leaves nothing half done and says so in
     * [InboxState.error]. A selection over 500 goes in chunks, each applied as
     * it is accepted. Reading clears the covered messages' notifications; the
     * unread badge takes the server's `unread_count`. Returns how many
     * messages changed, or null when it failed.
     */
    suspend fun applyBatch(change: BatchRead): Int? = withContext(Dispatchers.IO) {
        val parts = when (change) {
            is BatchRead.All -> listOf(change)
            is BatchRead.Ids -> BatchRead.chunks(change.ids, change.read)
        }
        if (parts.isEmpty()) return@withContext 0
        mutable.update { it.copy(batchBusy = true) }
        try {
            queue.run {
                val s = session() ?: return@run null
                var changed = 0
                try {
                    for (part in parts) {
                        val reply = api.call(s.server, "POST", BatchRead.path(part), BatchRead.body(part), s.token).json
                        changed += reply.optInt("changed")
                        val covered = applyBatchLocally(part, if (reply.has("unread_count")) reply.optInt("unread_count") else null)
                        if (part.read) InboxNotifier.cancelMany(appContext, covered)
                    }
                    // A retry that went through also clears the failure it retried.
                    mutable.update { it.copy(error = null) }
                    changed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ApiFailure && e.code == "device_revoked") {
                        clear()
                        mutable.update { it.copy(revoked = true) }
                    }
                    if (e is ApiFailure && e.code == "stale_list") updates.tryEmit(Unit)
                    mutable.update { it.copy(error = appContext.getString(R.string.inbox_batch_failed, failureText(e))) }
                    null
                }
            }
        } finally {
            mutable.update { it.copy(batchBusy = false) }
        }
    }

    private fun applyBatchLocally(change: BatchRead, unread: Int?): List<String> {
        var covered: List<String> = emptyList()
        mutable.update { state ->
            val local = BatchRead.apply(state.rows, change)
            covered = local.covered
            val delta = if (change.read) -local.changed else local.changed
            state.copy(
                rows = local.rows,
                detail = state.detail?.let { d -> if (d.id in local.covered) d.copy(read = change.read) else d },
                unreadCount = unread ?: state.unreadCount?.let { (it + delta).coerceAtLeast(0) },
            )
        }
        return covered
    }

    /** A message was read on another device (FCM t:"read"): reflect it if loaded. */
    fun readElsewhere(ids: Collection<String>) {
        ids.forEach { id -> if (mutable.value.rows.any { it.id == id && !it.read }) applyRead(id, true) }
    }

    suspend fun pair(input: PairingInput, name: String) = withContext(Dispatchers.IO) {
        queue.run {
            if (session() != null) return@run
            mutable.value = InboxState(paired = false, pairing = true)
            try {
                attempt {
                    keys.init()
                    val claim = api.call(input.server, "POST", listOf("v1", "pairing", "claim"),
                        JSONObject().put("code", input.code).put("jwk", keys.jwk()).put("reauth_jwk", keys.jwk(true))
                            .put("device", JSONObject().put("name", name.trim().take(64))
                                .put("model", Build.MODEL.orEmpty().take(64)).put("app_version", BuildConfig.VERSION_NAME)),
                    ).json
                    val id = claim.getString("pairing_id")
                    require(pairPattern.matches(id))
                    val digits = claim.getString("short_code")
                    require(Regex("[0-9]{2}").matches(digits))
                    mutable.update { it.copy(pairingNumber = digits) }
                    repeat(145) {
                        val reply = api.call(input.server, "POST", listOf("v1", "pairing", id, "token"), JSONObject(), proof = true)
                        if (reply.status == 200) {
                            val scopes = reply.json.optJSONArray("scopes") ?: JSONArray()
                            val linked = DeviceSession(input.server, reply.json.getString("device_id"),
                                reply.json.getString("token"), reply.json.optString("source_id"),
                                (0 until scopes.length()).map { scopes.getString(it) }.toSet())
                            vault.write("session", linked.encode())
                            mutable.value = identity(linked, InboxState(loading = "msg:read" in linked.scopes))
                            if (mutable.value.canRead) refreshLocked(linked)
                            return@attempt
                        }
                        delay(2000)
                    }
                    mutable.update { it.copy(error = text(R.string.pair_error_expired)) }
                }
            } finally {
                mutable.update { it.copy(pairing = false, pairingNumber = null, loading = false) }
            }
        }
    }

    suspend fun unpair(): Boolean = withContext(Dispatchers.IO) {
        queue.run {
            val s = session() ?: return@run true
            var done = false
            attempt {
                api.call(s.server, "DELETE", listOf("v1", "devices", "self"), token = s.token)
                clear()
                done = true
            }
            done
        }
    }

    suspend fun registerPush(token: String): Boolean = withContext(Dispatchers.IO) {
        queue.run {
            val s = session() ?: return@run true
            try {
                api.call(s.server, "PUT", listOf("v1", "devices", "self", "push"), JSONObject().put("token", token), s.token)
                true
            } catch (e: ApiFailure) {
                if (e.code == "device_revoked") clear()
                e.status in listOf(400, 401, 403)
            }
        }
    }

    /**
     * The push fetch: one request that also acknowledges the push and is audited
     * as `message.push_fetch` (server 0.10). Returns null when the device is
     * revoked or the message is gone.
     */
    suspend fun messageForPush(id: String): InboxMessage? = withContext(Dispatchers.IO) {
        require(messagePattern.matches(id))
        queue.run {
            val s = session() ?: return@run null
            try {
                val msg = api.call(s.server, "GET", listOf("v1", "inbox", id), token = s.token,
                    query = mapOf("via" to "push", "ack" to "1")).json
                saveDetail(msg)
                pruneDetails()
                InboxMessage.parse(msg)
            } catch (e: ApiFailure) {
                if (e.code == "device_revoked") clear()
                null
            }
        }
    }

    suspend fun loadSelf() = withContext(Dispatchers.IO) {
        val s = runCatching { session() }.getOrNull() ?: return@withContext
        try {
            val json = queue.run { api.call(s.server, "GET", listOf("v1", "devices", "self"), token = s.token).json }
            val types = if (json.isNull("telegram_types") || !json.has("telegram_types")) null else {
                val arr = json.optJSONArray("telegram_types") ?: JSONArray()
                (0 until arr.length()).map { arr.optString(it) }
            }
            mutable.update {
                it.copy(self = DeviceSelf(json.optString("name"), json.optString("push_state"), types, json.optBoolean("push_enabled")))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            if (e.code == "device_revoked") clear()
        } catch (_: Exception) {
            // Shown as unknown.
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
                    mutable.update { it.copy(error = text(R.string.inbox_error_locked_down)) }
                }
            } catch (_: Exception) {
                // Reconnect after transient network loss.
            }
            delay(3000)
        }
    }

    suspend fun verifySelf(): Boolean = withContext(Dispatchers.IO) {
        queue.run {
            val s = session() ?: return@run false
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
        val current = withContext(Dispatchers.IO) { session() } ?: error(text(R.string.inbox_error_not_paired))
        if (authenticate != null) {
            val challenge = api.call(current.server, "POST", listOf("v1", "devices", "self", "reauth", "challenge"),
                JSONObject(), current.token).json.getString("challenge")
            val signer = withContext(Dispatchers.IO) { keys.biometricSignature() }
            val approved = authenticate(signer) ?: throw AuthCancelled()
            val signed = withContext(Dispatchers.IO) {
                approved.update("nekopost/reauth/v1|${current.deviceID}|$challenge".toByteArray())
                approved.sign().url64()
            }
            api.call(current.server, "POST", listOf("v1", "devices", "self", "reauth"),
                JSONObject().put("challenge", challenge).put("sig", signed), current.token)
        }
        return queue.run {
            val latest = withContext(Dispatchers.IO) { session() }
            check(latest?.deviceID == current.deviceID)
            api.call(current.server, method, path, data, current.token).json
        }
    }

    private fun clear() {
        vault.clear()
        keys.clear()
        cursor = null
        InboxNotifier.cancelAll(appContext)
        mutable.value = InboxState(paired = false)
    }

    /** Runs [block], turning failures into [InboxState.error] (or the offline banner). */
    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            mutable.update {
                if (it.rows.isNotEmpty() && it.searchQuery == null) it.copy(offlineSince = it.offlineSince ?: Instant.now(), error = null)
                else it.copy(error = text(R.string.inbox_error_offline))
            }
        } catch (e: ApiFailure) {
            if (e.code == "device_revoked") {
                clear()
                mutable.update { it.copy(revoked = true) }
            }
            mutable.update { it.copy(error = failureText(e)) }
        } catch (e: Exception) {
            mutable.update { it.copy(error = failureText(e)) }
        }
    }

    fun failureText(e: Exception): String = when {
        e is ApiFailure -> when (e.code) {
            "device_revoked" -> text(R.string.inbox_error_revoked)
            "pairing_contested" -> text(R.string.pair_error_contested)
            "locked_down" -> text(R.string.inbox_error_locked_down)
            "rate_limited" -> text(R.string.inbox_error_rate_limited)
            "stale_list" -> text(R.string.inbox_error_stale_list)
            "pairing_not_found", "pairing_gone" -> text(R.string.pair_error_invalid)
            else -> appContext.getString(R.string.inbox_error_server, e.status)
        }
        e is IOException -> text(R.string.inbox_error_offline)
        else -> text(R.string.inbox_error_generic)
    }

    private fun rows(arr: JSONArray?): List<InboxMessage> =
        (0 until (arr?.length() ?: 0)).mapNotNull { i -> runCatching { InboxMessage.parse(arr!!.getJSONObject(i)) }.getOrNull() }
}

/** The user cancelled the biometric prompt of an admin operation: not an error. */
internal class AuthCancelled : IllegalStateException("cancelled")
