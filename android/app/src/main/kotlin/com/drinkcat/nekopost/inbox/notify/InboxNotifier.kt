package com.drinkcat.nekopost.inbox.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.inbox.InboxMessage
import com.drinkcat.nekopost.inbox.messagePattern

/**
 * Posts and clears inbox notifications: one per message (tag = message ID,
 * id = 1), grouped under a summary, with 复制验证码 / 标为已读 actions and a tap
 * that opens exactly that message. Lock screen: VISIBILITY_PRIVATE plus a
 * public version without sender, text or code. All notifications are local
 * only (no Wear mirroring) and never get system "smart" actions.
 */
object InboxNotifier {
    private const val ID = 1
    const val SUMMARY_TAG = "nekopost.summary"
    const val TEST_TAG = "nekopost.test"
    private const val EXTRA_LINE = "com.drinkcat.nekopost.SUMMARY_LINE"
    private const val EXTRA_REDACTED = "com.drinkcat.nekopost.REDACTED"

    /** The device is locked (keyguard showing or not yet unlocked since boot). */
    fun isDeviceLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return true
        return keyguard.isDeviceLocked || keyguard.isKeyguardLocked
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val group = "inbox"
        manager.createNotificationChannelGroup(NotificationChannelGroup(group, context.getString(R.string.notify_group_inbox)))
        fun channel(id: String, name: Int, importance: Int, description: Int) =
            NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
                this.group = group
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        manager.createNotificationChannels(
            listOf(
                channel(NotificationFormatter.CHANNEL_CODES, R.string.notify_channel_codes, NotificationManager.IMPORTANCE_HIGH, R.string.notify_channel_codes_detail),
                channel(NotificationFormatter.CHANNEL_MESSAGES, R.string.notify_channel_messages, NotificationManager.IMPORTANCE_DEFAULT, R.string.notify_channel_messages_detail),
                channel(NotificationFormatter.CHANNEL_QUIET, R.string.notify_channel_quiet, NotificationManager.IMPORTANCE_LOW, R.string.notify_channel_quiet_detail),
                channel(NotificationFormatter.CHANNEL_SYSTEM, R.string.notify_channel_system, NotificationManager.IMPORTANCE_LOW, R.string.notify_channel_system_detail),
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The rich notification of one message; replaces its placeholder. */
    fun post(context: Context, item: InboxMessage, showContent: Boolean, copied: Boolean = false) {
        val texts = NotificationTexts.from(context)
        val spec = NotificationFormatter.format(item, showContent, texts, deviceLocked = isDeviceLocked(context))
        val builder = base(context, spec.channel, spec.title, if (copied) texts.copied else spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (copied) texts.copied else spec.bigText))
            .setSubText(spec.subText)
            .setLargeIcon(avatar(context, spec.avatarLabel, spec.avatarKey))
            .setPublicVersion(publicVersion(context, spec.channel, spec.publicTitle, spec.publicText))
            .setContentIntent(openIntent(context, item.id))
            .setGroup(NotificationFormatter.GROUP)
            .setOnlyAlertOnce(true)
            .addExtras(
                Bundle().apply {
                    putString(EXTRA_LINE, spec.summaryLine)
                    putBoolean(EXTRA_REDACTED, spec.redacted)
                },
            )
        spec.whenMillis?.let { builder.setWhen(it).setShowWhen(true) }
        if (spec.copyAction && !copied) {
            builder.addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.inbox_copy_code), actionIntent(context, InboxActionReceiver.ACTION_COPY, item.id))
                    .setAuthenticationRequired(true)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(0, context.getString(R.string.inbox_mark_read), actionIntent(context, InboxActionReceiver.ACTION_READ, item.id))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build(),
        )
        if (item.hasCode) builder.setTimeoutAfter(30 * 60_000L)
        notify(context, item.id, builder.build())
        refreshSummary(context)
    }

    /**
     * After the unlock (ACTION_USER_PRESENT): notifications posted while locked
     * get their sender, text and 复制验证码 back, quietly (setOnlyAlertOnce).
     * [load] reads the message from the encrypted cache.
     */
    suspend fun revealAfterUnlock(context: Context, showContent: Boolean, load: suspend (String) -> InboxMessage?) {
        if (isDeviceLocked(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val redacted = runCatching { manager.activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.tag != null && messagePattern.matches(it.tag) && it.notification.extras.getBoolean(EXTRA_REDACTED) }
        for (note in redacted) {
            val item = runCatching { load(note.tag) }.getOrNull() ?: continue
            post(context, item, showContent)
        }
    }

    /** The fetch did not finish in time: a quiet generic notice, replaced by [post] later. */
    fun postPlaceholder(context: Context, id: String) {
        if (!messagePattern.matches(id)) return
        val texts = NotificationTexts.from(context)
        val note = base(context, NotificationFormatter.CHANNEL_SYSTEM, texts.appTitle, texts.newMessage)
            .setPublicVersion(publicVersion(context, NotificationFormatter.CHANNEL_SYSTEM, texts.appTitle, texts.publicMessage))
            .setContentIntent(openIntent(context, id))
            .setGroup(NotificationFormatter.GROUP)
            .addExtras(Bundle().apply { putString(EXTRA_LINE, texts.newMessage) })
            .build()
        notify(context, id, note)
        refreshSummary(context)
    }

    /** 推送测试成功 (FCM ping). */
    fun postTest(context: Context) {
        val note = base(context, NotificationFormatter.CHANNEL_SYSTEM, context.getString(R.string.brand_name), context.getString(R.string.notify_test_ok))
            .setTimeoutAfter(5 * 60_000L)
            .build()
        notify(context, TEST_TAG, note)
    }

    fun cancel(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(id, ID)
        refreshSummary(context)
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Cancels notifications of messages that are read, expired or gone in [rows] (after every refresh). */
    fun reconcile(context: Context, rows: List<InboxMessage>) {
        val byId = rows.associateBy { it.id }
        val active = activeMessageIds(context)
        var changed = false
        for (id in active) {
            val row = byId[id] ?: continue
            if (row.read || row.expired) {
                NotificationManagerCompat.from(context).cancel(id, ID)
                changed = true
            }
        }
        if (changed) refreshSummary(context)
    }

    fun activeMessageIds(context: Context): List<String> {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return runCatching { manager.activeNotifications.toList() }.getOrDefault(emptyList())
            .mapNotNull { it.tag }
            .filter(messagePattern::matches)
    }

    /** The group summary: 「N 条新消息」 with up to five masked lines; gone below two children. */
    fun refreshSummary(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val children = runCatching { manager.activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.tag != null && messagePattern.matches(it.tag) }
            .sortedByDescending { it.postTime }
        if (children.size < 2) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_TAG, ID)
            return
        }
        val texts = NotificationTexts.from(context)
        val count = texts.publicSummary.format(children.size)
        val style = NotificationCompat.InboxStyle().setSummaryText(count)
        children.take(5).forEach { child ->
            child.notification.extras.getString(EXTRA_LINE)?.let(style::addLine)
        }
        val channel = children.first().notification.channelId ?: NotificationFormatter.CHANNEL_MESSAGES
        val summary = base(context, channel, texts.appTitle, count)
            .setStyle(style)
            .setGroup(NotificationFormatter.GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setPublicVersion(publicVersion(context, channel, texts.appTitle, count))
            .setContentIntent(openIntent(context, null))
            .build()
        notify(context, SUMMARY_TAG, summary)
    }

    private fun base(context: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_nekopost)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setAllowSystemGeneratedContextualActions(false)

    private fun publicVersion(context: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_nekopost)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /** Tap: the non-exported trampoline that hands the ID to the running app, nothing else. */
    fun openIntent(context: Context, id: String?): PendingIntent {
        val intent = Intent(context, OpenMessageActivity::class.java)
        if (id != null) intent.putExtra(OpenMessageActivity.EXTRA_MESSAGE_ID, id)
        return PendingIntent.getActivity(context, id?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(context: Context, action: String, id: String): PendingIntent {
        val intent = Intent(context, InboxActionReceiver::class.java).setAction(action)
            .putExtra(InboxActionReceiver.EXTRA_MESSAGE_ID, id)
        return PendingIntent.getBroadcast(context, (action + id).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Source avatar for the large icon: the same tonal circle and letter as in the inbox list. */
    private fun avatar(context: Context, label: String, key: String): Bitmap {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val palette = if (dark) NekoTokens.Avatar.dark else NekoTokens.Avatar.light
        val (container, content) = palette[NekoTokens.Avatar.index(key)]
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = container.toArgb() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = content.toArgb()
        paint.textSize = size * 0.42f
        paint.textAlign = Paint.Align.CENTER
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, size / 2f, baseline, paint)
        return bitmap
    }

    private fun notify(context: Context, tag: String, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(tag, ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and post.
        }
    }
}
