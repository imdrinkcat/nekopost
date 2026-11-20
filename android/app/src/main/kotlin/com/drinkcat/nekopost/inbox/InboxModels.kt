package com.drinkcat.nekopost.inbox

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** What kind of message: drives the small badge on the avatar. */
enum class MessageKind { Sms, Notification, Note, Script }

enum class MessagePriority { Low, Normal, High }

/** One delivery of a message (server `deliveries[]`). */
data class Delivery(val channel: String, val state: String, val aux: Boolean) {
    val delivered: Boolean get() = state == "delivered"
}

/**
 * One inbox message, list row or detail. Content fields ([sender], [preview],
 * [body], [otp]) are empty once the server body has expired. [title] and
 * [preview] are the server's masked versions (digit runs hidden); [sender] is
 * the unmasked sender the server sends for the conversation list.
 */
data class InboxMessage(
    val id: String,
    val seq: Long = 0,
    val sender: String = "",
    val title: String = "",
    val preview: String = "",
    val sourceId: String = "",
    val sourceName: String = "",
    val sourceIcon: String = "",
    val kind: MessageKind = MessageKind.Script,
    val category: String = "",
    val status: String = "",
    val receivedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val hasCode: Boolean = false,
    val read: Boolean = false,
    val late: Boolean = false,
    val priority: MessagePriority = MessagePriority.Normal,
    val appOnly: Boolean = false,
    val body: String? = null,
    val otp: String? = null,
    val deliveries: List<Delivery> = emptyList(),
) {
    /** The body is gone (expired, shredded or never kept): show 「正文已过期」. */
    val expired: Boolean get() = expiresAt == null

    /**
     * The server keeps the body until it is deleted by hand (automatic deletion is
     * off); its expiry is then the year 9999. The body is live, but there is no date.
     */
    val keptForever: Boolean get() = expiresAt?.let { it.epochSecond > 200_000_000_000L } == true

    /** The name shown on the row and as the notification title. */
    val displaySender: String get() = sender.ifBlank { title }.ifBlank { sourceName }.ifBlank { sourceId }

    companion object {
        /**
         * Parses a server inbox item. Timestamps carry an offset ("+09:00"), which
         * Instant.parse rejects before Android 14, so they go through OffsetDateTime.
         */
        fun parse(json: JSONObject, clock: Instant = Instant.now()): InboxMessage {
            val id = json.getString("id")
            require(messagePattern.matches(id))
            val source = json.optJSONObject("source")
            val expiry = json.timestamp("body_expires_at")
            val live = expiry != null && expiry.isAfter(clock) && json.optString("body_state", "stored") == "stored"
            val deliveries = json.optJSONArray("deliveries")
            return InboxMessage(
                id = id,
                seq = json.optLong("seq"),
                sender = if (live) json.text("sender").take(64) else "",
                title = if (live) json.text("title") else "",
                preview = if (live) json.text("preview") else "",
                sourceId = source?.text("id").orEmpty(),
                sourceName = source?.text("name").orEmpty(),
                sourceIcon = source?.text("icon").orEmpty().take(8),
                kind = when (json.text("type")) {
                    "sms" -> MessageKind.Sms
                    "notification" -> MessageKind.Notification
                    "note" -> MessageKind.Note
                    else -> MessageKind.Script
                },
                category = json.text("category_text"),
                status = json.text("status_text"),
                receivedAt = json.timestamp("received_at"),
                expiresAt = if (live) expiry else null,
                hasCode = json.optBoolean("has_code"),
                read = json.optBoolean("read"),
                late = json.optBoolean("late"),
                priority = when (json.text("priority")) {
                    "low" -> MessagePriority.Low
                    "high" -> MessagePriority.High
                    else -> MessagePriority.Normal
                },
                appOnly = json.optBoolean("app_only"),
                body = if (live && json.has("body") && !json.isNull("body")) json.optString("body") else null,
                otp = if (live) json.optJSONObject("otp")?.text("code")?.takeIf(String::isNotBlank) else null,
                deliveries = if (deliveries == null) emptyList() else (0 until deliveries.length()).mapNotNull { i ->
                    deliveries.optJSONObject(i)?.let { Delivery(it.text("channel"), it.text("state"), it.optBoolean("aux")) }
                },
            )
        }

        private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

        internal fun JSONObject.timestamp(key: String): Instant? {
            if (isNull(key)) return null
            val raw = optString(key).takeIf(String::isNotBlank) ?: return null
            return parseTimestamp(raw)
        }

        fun parseTimestamp(raw: String): Instant? =
            runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(raw) }.getOrNull()
    }
}
