package com.drinkcat.nekopost.forwarder.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/** The parts of a notification we forward; plain data so ingest logic is testable. */
internal data class CapturedNotification(
    val packageName: String,
    /** StatusBarNotification key (stable for updates of the same notification). */
    val key: String,
    val postTime: Long,
    val channelId: String?,
    val title: String?,
    /** null when the system redacted the text. */
    val text: String?,
    /** The system replaced the content with a placeholder (Android 15+ OTP protection). */
    val redacted: Boolean,
)

internal sealed interface Extraction {
    data class Captured(val notification: CapturedNotification) : Extraction
    data class Ignored(val reason: String) : Extraction
}

/**
 * Filters and reads one posted notification (design §8.5): skips ongoing,
 * foreground-service, group-summary, progress/transport/call/service and
 * media notifications; reads EXTRA_TITLE and EXTRA_BIG_TEXT / EXTRA_TEXT, or
 * only the newest message of a MessagingStyle notification.
 */
internal object NotificationExtractor {
    private val skippedCategories = setOf(
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_SERVICE,
    )

    fun extract(
        packageName: String,
        key: String,
        postTime: Long,
        notification: Notification,
        placeholders: RedactionPlaceholders,
    ): Extraction {
        val flags = notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return Extraction.Ignored("ongoing")
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return Extraction.Ignored("foreground_service")
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return Extraction.Ignored("group_summary")
        if (notification.category?.let { it in skippedCategories } == true) return Extraction.Ignored("category")
        val extras: Bundle = notification.extras ?: Bundle.EMPTY
        if (isMedia(extras)) return Extraction.Ignored("media")
        if (extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        ) {
            return Extraction.Ignored("progress")
        }

        val messaging = latestMessage(notification)
        val title = messaging?.title ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = messaging?.text
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val cleanText = text?.trim()?.takeIf { it.isNotEmpty() }
        // Placeholder text, or no text at all, means the system hid the content from us.
        val redacted = placeholders.matches(cleanText) || (cleanTitle == null && cleanText == null)
        return Extraction.Captured(
            CapturedNotification(
                packageName = packageName,
                key = key,
                postTime = postTime,
                channelId = notification.channelId,
                title = cleanTitle,
                text = if (redacted) null else cleanText,
                redacted = redacted,
            ),
        )
    }

    private fun isMedia(extras: Bundle): Boolean =
        extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true

    private data class MessagingContent(val title: String?, val text: String?)

    /** Newest message only; "sender: text" in group conversations. */
    private fun latestMessage(notification: Notification): MessagingContent? {
        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val last = style.messages.lastOrNull() ?: return null
        val sender = last.person?.name?.toString()?.takeIf { it.isNotBlank() }
        val text = last.text?.toString()?.takeIf { it.isNotBlank() }
        val conversation = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
        val body = if (style.isGroupConversation && sender != null && text != null) "$sender: $text" else text
        return MessagingContent(title = conversation ?: sender, text = body)
    }
}

/**
 * Placeholder texts the system shows instead of sensitive notification content
 * to untrusted listeners (Android 15+). Built-in strings plus the framework's
 * own localized string when it can be found.
 */
internal class RedactionPlaceholders(placeholders: Collection<String>) {
    private val known: Set<String> = placeholders.map(::normalize).filter { it.isNotEmpty() }.toSet()

    fun matches(text: String?): Boolean = text != null && normalize(text) in known

    private fun normalize(text: String): String = text.trim().trimEnd('.', '。').lowercase()

    companion object {
        val BUILT_IN: List<String> = listOf(
            "Sensitive notification content hidden",
            "Sensitive content hidden",
            "Content hidden",
            "敏感通知内容已隐藏",
            "已隐藏敏感通知内容",
            "敏感内容已隐藏",
            "已隐藏敏感内容",
            "敏感通知內容已隱藏",
            "已隱藏敏感通知內容",
            "機密性の高い通知コンテンツは非表示です",
            "機密性の高い通知の内容は非表示です",
        )

        private val SYSTEM_STRINGS = listOf("redacted_notification_message", "redacted_otp_notification_message")

        @SuppressLint("DiscouragedApi") // framework-private string, looked up by name on purpose
        fun fromSystem(): RedactionPlaceholders {
            val res = Resources.getSystem()
            val system = SYSTEM_STRINGS.mapNotNull { name ->
                val id = res.getIdentifier(name, "string", "android")
                if (id == 0) null else runCatching { res.getString(id) }.getOrNull()
            }
            return RedactionPlaceholders(BUILT_IN + system)
        }
    }
}

/** App label for titles and the log. */
internal fun interface AppLabeler {
    fun label(packageName: String): String
}

internal class PackageManagerLabeler(context: Context) : AppLabeler {
    private val pm: PackageManager = context.packageManager
    private val cache = ConcurrentHashMap<String, String>()

    override fun label(packageName: String): String = cache.getOrPut(packageName) {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString().ifBlank { packageName }
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
