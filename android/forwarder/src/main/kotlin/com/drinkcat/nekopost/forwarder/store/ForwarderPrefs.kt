package com.drinkcat.nekopost.forwarder.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.drinkcat.nekopost.core.forwarder.DeliveryEvent
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.forwarder.FailureEvent
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.MessagePriority
import com.drinkcat.nekopost.core.forwarder.NotificationAppPolicy
import com.drinkcat.nekopost.core.forwarder.NotificationRule
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.core.forwarder.SmsBacklog
import com.drinkcat.nekopost.core.rules.SmsSenderRule
import com.drinkcat.nekopost.core.rules.SmsSenderRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.time.Instant

/** Everything the forwarder persists outside Room and the secret store. No content, no secrets. */
internal data class ForwarderSettings(
    val enrollment: Enrollment? = null,
    val smsEnabled: Boolean = false,
    /** When SMS forwarding (or enrollment) last started; the reconciler never looks further back. */
    val smsEnabledAt: Instant? = null,
    /** Highest inbox `_id` already handled; null = not set yet (set on first scan, never 0). */
    val smsLastSeenId: Long? = null,
    val notificationsEnabled: Boolean = false,
    val notificationApps: Set<String> = emptySet(),
    val notificationRules: Map<String, NotificationRule> = emptyMap(),
    val paused: PauseReason? = null,
    val lastSuccess: DeliveryEvent? = null,
    val lastFailure: FailureEvent? = null,
    val smsBacklog: SmsBacklog? = null,
    val smsSenderRules: List<SmsSenderRule> = emptyList(),
)

/** DataStore-backed settings (file `nekopost_forwarder.preferences_pb`). */
internal class ForwarderPrefs(private val store: DataStore<Preferences>) {

    val settings: Flow<ForwarderSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun current(): ForwarderSettings = settings.first()

    suspend fun setEnrollment(enrollment: Enrollment, smsStartId: Long?, now: Instant) = store.edit {
        it[SERVER_URL] = enrollment.serverUrl
        it[SOURCE_ID] = enrollment.sourceId
        it[SOURCE_NAME] = enrollment.sourceName
        it[ENROLLED_AT] = enrollment.enrolledAt.toEpochMilli()
        it.remove(PAUSED)
        it.remove(BACKLOG_COUNT)
        it.clearFailure()
        it.setSmsStart(smsStartId, now)
    }

    /** Unenroll: forgets everything except the SMS sender rules (user configuration, no secrets). */
    suspend fun clear() {
        store.edit {
            val rules = it[SMS_RULES]
            it.clear()
            if (rules != null) it[SMS_RULES] = rules
        }
    }

    suspend fun setSmsSenderRules(rules: List<SmsSenderRule>) = store.edit {
        it[SMS_RULES] = SmsSenderRules.encode(rules.take(SmsSenderRules.MAX_RULES))
    }

    suspend fun setSmsEnabled(enabled: Boolean, smsStartId: Long?, now: Instant) = store.edit {
        it[SMS_ENABLED] = enabled
        it.remove(BACKLOG_COUNT)
        if (enabled) it.setSmsStart(smsStartId, now)
    }

    /** Sets the reconciler start point only if none is set yet. */
    suspend fun initSmsStartIfUnset(smsStartId: Long) = store.edit {
        if (it[SMS_LAST_SEEN_ID] == null) it[SMS_LAST_SEEN_ID] = smsStartId
    }

    suspend fun setSmsLastSeenId(id: Long) = store.edit {
        val old = it[SMS_LAST_SEEN_ID]
        if (old == null || id > old) it[SMS_LAST_SEEN_ID] = id
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) = store.edit { it[NOTIF_ENABLED] = enabled }

    suspend fun setNotificationApps(packages: Set<String>) = store.edit {
        it[NOTIF_APPS] = packages.filter(NotificationAppPolicy::isSelectable).toSet()
    }

    suspend fun setNotificationRule(packageName: String, rule: NotificationRule?) = store.edit {
        val rules = decodeRules(it[NOTIF_RULES]).toMutableMap()
        if (rule == null) rules.remove(packageName) else rules[packageName] = rule
        it[NOTIF_RULES] = encodeRules(rules)
    }

    suspend fun setPaused(reason: PauseReason?) = store.edit {
        if (reason == null) it.remove(PAUSED) else it[PAUSED] = reason.name
    }

    suspend fun recordSuccess(event: DeliveryEvent) = store.edit {
        it[SUCCESS_AT] = event.at.toEpochMilli()
        it[SUCCESS_KIND] = event.kind.name
    }

    suspend fun recordFailure(event: FailureEvent) = store.edit {
        it[FAILURE_AT] = event.at.toEpochMilli()
        it[FAILURE_REASON] = event.reason.name
        if (event.httpStatus == null) it.remove(FAILURE_HTTP) else it[FAILURE_HTTP] = event.httpStatus!!
        if (event.detail == null) it.remove(FAILURE_DETAIL) else it[FAILURE_DETAIL] = event.detail!!.take(64)
    }

    suspend fun setBacklog(backlog: SmsBacklog?) = store.edit {
        if (backlog == null) {
            it.remove(BACKLOG_COUNT)
            it.remove(BACKLOG_OLDEST)
            it.remove(BACKLOG_DETECTED)
        } else {
            it[BACKLOG_COUNT] = backlog.count
            if (backlog.oldestAt == null) it.remove(BACKLOG_OLDEST) else it[BACKLOG_OLDEST] = backlog.oldestAt!!.toEpochMilli()
            it[BACKLOG_DETECTED] = backlog.detectedAt.toEpochMilli()
        }
    }

    private fun MutablePreferences.setSmsStart(smsStartId: Long?, now: Instant) {
        this[SMS_ENABLED_AT] = now.toEpochMilli()
        if (smsStartId == null) remove(SMS_LAST_SEEN_ID) else this[SMS_LAST_SEEN_ID] = smsStartId
    }

    private fun MutablePreferences.clearFailure() {
        remove(FAILURE_AT)
        remove(FAILURE_REASON)
        remove(FAILURE_HTTP)
        remove(FAILURE_DETAIL)
    }

    private fun Preferences.toSettings(): ForwarderSettings {
        val url = this[SERVER_URL]
        val sourceId = this[SOURCE_ID]
        val enrollment = if (url != null && sourceId != null) {
            Enrollment(
                serverUrl = url,
                sourceId = sourceId,
                sourceName = this[SOURCE_NAME] ?: sourceId,
                enrolledAt = Instant.ofEpochMilli(this[ENROLLED_AT] ?: 0L),
            )
        } else {
            null
        }
        val success = this[SUCCESS_AT]?.let { at ->
            DeliveryEvent(Instant.ofEpochMilli(at), enumOrNull<ForwardKind>(this[SUCCESS_KIND]) ?: ForwardKind.Sms)
        }
        val failure = this[FAILURE_AT]?.let { at ->
            FailureEvent(
                at = Instant.ofEpochMilli(at),
                reason = enumOrNull<FailureReason>(this[FAILURE_REASON]) ?: FailureReason.Network,
                httpStatus = this[FAILURE_HTTP],
                detail = this[FAILURE_DETAIL],
            )
        }
        val backlog = this[BACKLOG_COUNT]?.let { count ->
            SmsBacklog(
                count = count,
                oldestAt = this[BACKLOG_OLDEST]?.let(Instant::ofEpochMilli),
                detectedAt = Instant.ofEpochMilli(this[BACKLOG_DETECTED] ?: 0L),
            )
        }
        return ForwarderSettings(
            enrollment = enrollment,
            smsEnabled = this[SMS_ENABLED] ?: false,
            smsEnabledAt = this[SMS_ENABLED_AT]?.let(Instant::ofEpochMilli),
            smsLastSeenId = this[SMS_LAST_SEEN_ID],
            notificationsEnabled = this[NOTIF_ENABLED] ?: false,
            notificationApps = this[NOTIF_APPS] ?: emptySet(),
            notificationRules = decodeRules(this[NOTIF_RULES]),
            paused = enumOrNull<PauseReason>(this[PAUSED]),
            lastSuccess = success,
            lastFailure = failure,
            smsBacklog = backlog,
            smsSenderRules = SmsSenderRules.decode(this[SMS_RULES].orEmpty()),
        )
    }

    companion object {
        const val FILE_NAME = "nekopost_forwarder"

        private val SERVER_URL = stringPreferencesKey("server_url")
        private val SOURCE_ID = stringPreferencesKey("source_id")
        private val SOURCE_NAME = stringPreferencesKey("source_name")
        private val ENROLLED_AT = longPreferencesKey("enrolled_at")
        private val SMS_ENABLED = booleanPreferencesKey("sms_enabled")
        private val SMS_ENABLED_AT = longPreferencesKey("sms_enabled_at")
        private val SMS_LAST_SEEN_ID = longPreferencesKey("sms_last_seen_id")
        private val NOTIF_ENABLED = booleanPreferencesKey("notif_enabled")
        private val NOTIF_APPS = stringSetPreferencesKey("notif_apps")
        private val NOTIF_RULES = stringPreferencesKey("notif_rules")
        private val PAUSED = stringPreferencesKey("paused")
        private val SUCCESS_AT = longPreferencesKey("success_at")
        private val SUCCESS_KIND = stringPreferencesKey("success_kind")
        private val FAILURE_AT = longPreferencesKey("failure_at")
        private val FAILURE_REASON = stringPreferencesKey("failure_reason")
        private val FAILURE_HTTP = intPreferencesKey("failure_http")
        private val FAILURE_DETAIL = stringPreferencesKey("failure_detail")
        private val BACKLOG_COUNT = intPreferencesKey("backlog_count")
        private val BACKLOG_OLDEST = longPreferencesKey("backlog_oldest")
        private val BACKLOG_DETECTED = longPreferencesKey("backlog_detected")
        private val SMS_RULES = stringPreferencesKey("sms_sender_rules")

        private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

        internal fun encodeRules(rules: Map<String, NotificationRule>): String = buildJsonObject {
            for ((pkg, rule) in rules) {
                putJsonObject(pkg) {
                    put("mode", rule.mode.name)
                    put("keywords", JsonArray(rule.keywords.map { JsonPrimitive(it) }))
                    put("include_title", rule.includeTitle)
                    put("priority", rule.priority.name)
                }
            }
        }.toString()

        internal fun decodeRules(raw: String?): Map<String, NotificationRule> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val root = try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            } ?: return emptyMap()
            return root.mapNotNull { (pkg, value) ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val rule = NotificationRule(
                    mode = enumOrNull<NotificationRule.Mode>(obj["mode"]?.jsonPrimitive?.contentOrNull)
                        ?: NotificationRule.Mode.All,
                    keywords = (obj["keywords"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    includeTitle = obj["include_title"]?.jsonPrimitive?.booleanOrNull ?: true,
                    priority = enumOrNull<MessagePriority>(obj["priority"]?.jsonPrimitive?.contentOrNull)
                        ?: MessagePriority.Normal,
                )
                pkg to rule
            }.toMap()
        }
    }
}
