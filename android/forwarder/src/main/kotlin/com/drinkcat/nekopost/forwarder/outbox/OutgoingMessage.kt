package com.drinkcat.nekopost.forwarder.outbox

import com.drinkcat.nekopost.core.forwarder.MessagePriority
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The body of `POST /v1/messages` (docs/api.md §3.1). [toJson] clips every
 * field to the server limits so a long SMS never turns into a 422.
 *
 * @param meta values must be String, Boolean or Number; at most 16 entries
 */
internal data class OutgoingMessage(
    val type: String,
    val title: String?,
    val body: String?,
    val priority: MessagePriority = MessagePriority.Normal,
    val occurredAt: Instant? = null,
    val meta: Map<String, Any> = emptyMap(),
) {
    fun toJson(zone: ZoneId): String = buildJsonObject {
        title?.let { put("title", clipTitle(it)) }
        body?.let { put("body", clip(it, MAX_BODY_CHARS)) }
        put("type", type)
        put("priority", priority.wire)
        occurredAt?.let { put("occurred_at", rfc3339(it, zone)) }
        if (meta.isNotEmpty()) {
            putJsonObject("meta") {
                for ((key, value) in meta.entries.take(MAX_META)) {
                    when (value) {
                        is Boolean -> put(key, value)
                        is Number -> put(key, value)
                        else -> put(key, clip(value.toString(), MAX_META_CHARS))
                    }
                }
            }
        }
    }.toString()

    companion object {
        const val MAX_TITLE_CHARS = 256
        const val MAX_BODY_CHARS = 12_000
        const val MAX_META = 16
        const val MAX_META_CHARS = 256

        /** Single line, at most [MAX_TITLE_CHARS] code points. */
        fun clipTitle(title: String): String =
            clip(title.replace(Regex("\\s+"), " ").trim(), MAX_TITLE_CHARS)

        /** At most [max] code points; ends with "…" when cut. */
        fun clip(text: String, max: Int): String {
            if (text.codePointCount(0, text.length) <= max) return text
            return text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…"
        }

        /** RFC 3339 with offset, whole seconds, e.g. 2026-11-16T10:20:30+09:00. */
        fun rfc3339(instant: Instant, zone: ZoneId): String =
            OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), zone)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
