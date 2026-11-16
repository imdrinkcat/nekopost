package com.drinkcat.nekopost.forwarder

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.forwarder.sms.OtpStats
import kotlinx.coroutines.flow.first
import com.drinkcat.nekopost.forwarder.crypto.KeyManager
import com.drinkcat.nekopost.forwarder.notify.AppLabeler
import com.drinkcat.nekopost.forwarder.outbox.ForwarderDatabase
import com.drinkcat.nekopost.forwarder.sms.InboxSms
import com.drinkcat.nekopost.forwarder.sms.SimLabeler
import com.drinkcat.nekopost.forwarder.sms.SmsInboxSource
import com.drinkcat.nekopost.forwarder.store.ForwarderPrefs
import com.drinkcat.nekopost.forwarder.store.SecretStore
import com.drinkcat.nekopost.forwarder.upload.HttpClients
import com.drinkcat.nekopost.forwarder.work.WorkScheduler
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.io.File
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM via JCE, standing in for the Keystore-wrapped Tink keyset (no Keystore under Robolectric). */
class TestAead(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        associatedData?.let { cipher.updateAAD(it) }
        val ct = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(iv.size + ct.size).put(iv).put(ct).array()
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
        if (ciphertext.size < 12 + 16) throw GeneralSecurityException("too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext, 0, 12))
        associatedData?.let { cipher.updateAAD(it) }
        return try {
            cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
        } catch (e: GeneralSecurityException) {
            throw GeneralSecurityException("decryption failed", e)
        }
    }
}

internal class TestKeyManager : KeyManager {
    var current: Aead = TestAead()
    var destroyed = 0

    override fun aead(): Aead = current

    override fun destroy() {
        destroyed++
        current = TestAead()
    }
}

class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.ofHours(9)) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

internal class RecordingScheduler : WorkScheduler {
    var uploadNow = 0
    val uploadAfter = mutableListOf<Duration>()
    var reconcileNow = 0
    var periodic = 0
    var cancelled = 0

    override fun uploadNow() {
        uploadNow++
    }

    override fun uploadAfter(delay: Duration) {
        uploadAfter += delay
    }

    override fun reconcileNow() {
        reconcileNow++
    }

    override fun ensurePeriodic() {
        periodic++
    }

    override fun cancelAll() {
        cancelled++
    }
}

internal class FakeInbox : SmsInboxSource {
    var readable = true
    val rows = mutableListOf<InboxSms>()

    override fun canRead(): Boolean = readable
    override fun maxId(): Long? = if (readable) rows.maxOfOrNull { it.id } ?: 0L else null
    override fun after(afterId: Long, limit: Int): List<InboxSms> =
        rows.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
}

/** A fully wired graph on in-memory Room, a temp DataStore and fakes for everything Android-specific. */
internal class TestEnv(
    val context: Context,
    val clock: MutableClock = MutableClock(Instant.parse("2026-11-16T01:20:00Z")),
    baseHttp: OkHttpClient = OkHttpClient(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val keys = TestKeyManager()
    val scheduler = RecordingScheduler()
    val inbox = FakeInbox()
    val sim = mutableMapOf<Int, String>()
    val labels = mutableMapOf("jp.co.rakuten.bank" to "楽天銀行", "com.sagawa.app" to "佐川急便")
    private val name = "t" + UUID.randomUUID().toString().replace("-", "")
    val db: ForwarderDatabase = Room.inMemoryDatabaseBuilder(context, ForwarderDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val prefs = ForwarderPrefs(
        PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "$name.preferences_pb") },
    )
    val secretPrefs = context.getSharedPreferences("${name}_secrets", Context.MODE_PRIVATE)

    val graph = ForwarderGraph(
        context = context,
        scope = scope,
        clock = clock,
        db = db,
        prefs = prefs,
        keys = keys,
        secrets = SecretStore(secretPrefs, keys),
        scheduler = scheduler,
        inbox = inbox,
        simLabeler = SimLabeler { sim[it] },
        appLabeler = AppLabeler { labels[it] ?: it },
        http = HttpClients(baseHttp, clock),
    )

    /** Enrolls directly (no network), as enroll() would after a successful probe. */
    suspend fun enroll(
        serverUrl: String = "https://osaka.drinkcat.com",
        token: String = "mgs_abcdefghijklmnopqrstuvwxyz012345",
        smsEnabled: Boolean = true,
    ) {
        graph.secrets.setToken(token)
        graph.prefs.setEnrollment(Enrollment(serverUrl, "fold8", "Fold8", clock.instant()), inbox.maxId(), clock.instant())
        if (smsEnabled) graph.prefs.setSmsEnabled(true, inbox.maxId(), clock.instant())
    }

    suspend fun otpHealth(): OtpHealth = OtpStats.compute(db.dao().codeEvents().first(), clock.instant())

    fun close() {
        db.close()
        scope.cancel()
    }
}
