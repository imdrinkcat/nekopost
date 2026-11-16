package com.drinkcat.nekopost.inbox.notify

import android.content.Context
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.inbox.InboxMessage
import com.drinkcat.nekopost.inbox.MessagePriority

/** Fixed texts of inbox notifications, read from resources once per post. */
data class NotificationTexts(
    val appTitle: String,
    val publicMessage: String,
    val publicCode: String,
    /** "%1$d 条新消息" */
    val publicSummary: String,
    /** "验证码 %1$s" */
    val codeLine: String,
    val expired: String,
    val newMessage: String,
    val copied: String,
) {
    companion object {
        fun from(context: Context) = NotificationTexts(
            appTitle = context.getString(R.string.brand_name),
            publicMessage = context.getString(R.string.notify_public_message),
            publicCode = context.getString(R.string.notify_public_code),
            publicSummary = context.getString(R.string.notify_public_summary),
            codeLine = context.getString(R.string.notify_code_line),
            expired = context.getString(R.string.inbox_body_expired),
            newMessage = context.getString(R.string.notify_new_message),
            copied = context.getString(R.string.notify_copied),
        )
    }
}

/** Everything a message notification shows; built by [NotificationFormatter], posted by [InboxNotifier]. */
data class NotificationSpec(
    val id: String,
    val channel: String,
    val title: String,
    val text: String,
    val subText: String?,
    val bigText: String,
    val whenMillis: Long?,
    /** Lock-screen version: never the sender, the text or the code. */
    val publicTitle: String,
    val publicText: String,
    val copyAction: Boolean,
    /** One line of the group summary (masked preview). */
    val summaryLine: String,
    /** First letter / emoji for the large-icon avatar, and the key of its colour. */
    val avatarLabel: String,
    val avatarKey: String,
)

/**
 * Pure: an inbox message → what its notification says (requirement: 像平时收消息的软件).
 * Title = sender, text = preview, verification codes up front with a
 * 复制验证码 action; the public (lock-screen) version says only 「1 条新消息」.
 * With content hidden ([showContent] off) it falls back to 「来源 · 类别」.
 */
object NotificationFormatter {
    const val CHANNEL_CODES = "inbox_codes"
    const val CHANNEL_MESSAGES = "inbox_messages"
    const val CHANNEL_QUIET = "inbox_quiet"
    const val CHANNEL_SYSTEM = "system"
    const val GROUP = "nekopost.inbox"
    private const val TITLE_MAX = 40
    private const val BIG_MAX = 500
    private const val LINE_MAX = 120

    fun channelFor(item: InboxMessage): String = when {
        item.hasCode -> CHANNEL_CODES
        item.priority == MessagePriority.Low -> CHANNEL_QUIET
        else -> CHANNEL_MESSAGES
    }

    fun format(item: InboxMessage, showContent: Boolean, texts: NotificationTexts): NotificationSpec {
        val source = item.sourceName.ifBlank { item.sourceId }
        val origin = listOf(source, item.category).filter(String::isNotBlank).joinToString(" · ").ifBlank { texts.newMessage }
        val sender = clip(item.displaySender.ifBlank { texts.appTitle }, TITLE_MAX)
        val code = item.otp?.takeIf(String::isNotBlank)
        val body = (item.body ?: item.preview).trim()
        val firstLine = body.lineSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
        val title: String
        val text: String
        val big: String
        if (!showContent) {
            title = source.ifBlank { texts.appTitle }
            text = origin
            big = origin
        } else if (item.expired) {
            title = sender
            text = texts.expired
            big = texts.expired
        } else if (code != null) {
            title = sender
            text = texts.codeLine.format(code)
            big = clip(text + if (body.isNotBlank()) "\n" + body else "", BIG_MAX)
        } else {
            title = sender
            text = clip(firstLine.ifBlank { texts.newMessage }, LINE_MAX)
            big = clip(body.ifBlank { texts.newMessage }, BIG_MAX)
        }
        val masked = item.preview.lineSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
        val summary = if (showContent && !item.expired) {
            clip("$sender：${masked.ifBlank { item.category }}", LINE_MAX)
        } else {
            origin
        }
        return NotificationSpec(
            id = item.id,
            channel = channelFor(item),
            title = title,
            text = text,
            subText = source.takeIf { showContent && it.isNotBlank() && it != title },
            bigText = big,
            whenMillis = item.receivedAt?.toEpochMilli(),
            publicTitle = texts.appTitle,
            publicText = if (item.hasCode) texts.publicCode else texts.publicMessage,
            copyAction = code != null || (item.hasCode && !item.expired),
            summaryLine = summary,
            avatarLabel = avatarLabel(item.sourceIcon, if (showContent) sender else source),
            avatarKey = if (showContent && !item.expired) sender else item.sourceId.ifBlank { source },
        )
    }

    /** The source's emoji icon, else the first character of the name. */
    fun avatarLabel(icon: String, name: String): String {
        if (icon.isNotBlank()) return icon.trim().take(2)
        val first = name.trim().codePoints().findFirst()
        return if (first.isPresent) String(Character.toChars(first.asInt)).uppercase() else "?"
    }

    private fun clip(text: String, max: Int): String {
        val oneSpace = text.replace(Regex("[ \\t]+"), " ")
        if (oneSpace.codePointCount(0, oneSpace.length) <= max) return oneSpace
        val end = oneSpace.offsetByCodePoints(0, max - 1)
        return oneSpace.substring(0, end) + "…"
    }
}
