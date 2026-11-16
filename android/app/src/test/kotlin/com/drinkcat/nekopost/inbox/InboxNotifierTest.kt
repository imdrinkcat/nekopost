package com.drinkcat.nekopost.inbox

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.inbox.notify.InboxNotifier
import com.drinkcat.nekopost.inbox.notify.NotificationFormatter
import com.drinkcat.nekopost.inbox.notify.OpenMessageActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/** Posting, grouping, privacy and clearing of message notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class InboxNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    private val otp = InboxMessage(
        id = "m_01JB7QAB12CD34EF56GH78JK9M",
        sender = "95555",
        preview = "【招商银行】您的验证码为******",
        sourceId = "fold8",
        sourceName = "Fold8 主力机",
        category = "验证码",
        receivedAt = Instant.parse("2026-11-16T00:41:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        hasCode = true,
        body = "【招商银行】您的验证码为482913",
        otp = "482913",
    )
    private val chat = otp.copy(
        id = "m_01JB7QAB12CD34EF56GH78JK9N",
        sender = "微信 · 张三",
        preview = "今晚一起吃饭吗？",
        body = "今晚一起吃饭吗？",
        hasCode = false,
        otp = null,
    )

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        InboxNotifier.ensureChannels(app)
    }

    private fun posted(tag: String): Notification? =
        manager.activeNotifications.firstOrNull { it.tag == tag && it.id == 1 }?.notification

    @Test
    fun channelsAreCreatedOnceWithPrivateLockScreen() {
        for (id in listOf(NotificationFormatter.CHANNEL_CODES, NotificationFormatter.CHANNEL_MESSAGES, NotificationFormatter.CHANNEL_QUIET, NotificationFormatter.CHANNEL_SYSTEM)) {
            val channel = manager.getNotificationChannel(id)
            assertNotNull(id, channel)
            assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(NotificationFormatter.CHANNEL_CODES).importance)
    }

    @Test
    fun codeNotificationIsPrivateWithAPublicVersionAndActions() {
        InboxNotifier.post(app, otp, showContent = true)
        val n = posted(otp.id)!!
        assertEquals(NotificationFormatter.CHANNEL_CODES, n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(NotificationFormatter.GROUP, n.group)
        assertEquals("95555", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("482913"))
        val public = n.publicVersion!!
        val publicText = public.extras.getCharSequence(Notification.EXTRA_TITLE).toString() + public.extras.getCharSequence(Notification.EXTRA_TEXT)
        assertFalse(publicText.contains("482913"))
        assertFalse(publicText.contains("95555"))
        val actions = n.actions.map { it.title.toString() }
        assertEquals(listOf("复制验证码", "标为已读"), actions)
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, n.actions[1].semanticAction)
        // Tapping opens the non-exported trampoline with the ID only.
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(OpenMessageActivity::class.java.name, intent.component!!.className)
        assertEquals(otp.id, intent.getStringExtra(OpenMessageActivity.EXTRA_MESSAGE_ID))
        assertEquals(setOf(OpenMessageActivity.EXTRA_MESSAGE_ID), intent.extras!!.keySet())
    }

    @Test
    fun summaryAppearsWithTwoAndGoesAwayWithOne() {
        InboxNotifier.post(app, otp, showContent = true)
        assertNull(posted(InboxNotifier.SUMMARY_TAG))
        InboxNotifier.post(app, chat, showContent = true)
        val summary = posted(InboxNotifier.SUMMARY_TAG)!!
        assertTrue((summary.flags and Notification.FLAG_GROUP_SUMMARY) != 0)
        val summaryPublic = summary.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("2 条新消息", summaryPublic)
        InboxNotifier.cancel(app, otp.id)
        assertNull(posted(otp.id))
        assertNull(posted(InboxNotifier.SUMMARY_TAG))
        assertNotNull(posted(chat.id))
    }

    @Test
    fun reconcileClearsReadAndExpiredMessages() {
        InboxNotifier.post(app, otp, showContent = true)
        InboxNotifier.post(app, chat, showContent = true)
        InboxNotifier.reconcile(app, listOf(otp.copy(read = true), chat))
        assertNull(posted(otp.id))
        assertNotNull(posted(chat.id))
        InboxNotifier.reconcile(app, listOf(chat.copy(expiresAt = null)))
        assertNull(posted(chat.id))
    }

    @Test
    fun hiddenContentShowsOnlySourceAndCategory() {
        InboxNotifier.post(app, otp, showContent = false)
        val n = posted(otp.id)!!
        val text = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString() + n.extras.getCharSequence(Notification.EXTRA_TEXT)
        assertFalse(text.contains("482913"))
        assertFalse(text.contains("95555"))
    }

    @Test
    fun placeholderIsQuietAndReplacedByTheRealOne() {
        InboxNotifier.postPlaceholder(app, otp.id)
        assertEquals(NotificationFormatter.CHANNEL_SYSTEM, posted(otp.id)!!.channelId)
        InboxNotifier.post(app, otp, showContent = true)
        assertEquals(NotificationFormatter.CHANNEL_CODES, posted(otp.id)!!.channelId)
        assertEquals(1, manager.activeNotifications.count { it.tag == otp.id })
    }

    @Test
    fun nothingIsPostedWithoutPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        InboxNotifier.post(app, otp, showContent = true)
        assertNull(posted(otp.id))
        assertFalse(InboxNotifier.canPost(app as Context))
    }
}
