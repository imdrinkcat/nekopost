package com.drinkcat.nekopost.forwarder

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.forwarder.crypto.AndroidKeyManager
import com.drinkcat.nekopost.forwarder.outbox.ForwarderDatabase
import com.drinkcat.nekopost.forwarder.outbox.SmsSeenEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
class StorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migratesSmsSeenFromV1() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        // Build the current schema, then turn sms_seen back into its v1 shape.
        Room.databaseBuilder(context, ForwarderDatabase::class.java, name).build().apply {
            openHelper.writableDatabase
            close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE sms_seen")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sms_seen` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`fingerprint` TEXT NOT NULL, `smsc_at` INTEGER NOT NULL, `seen_at` INTEGER NOT NULL, " +
                    "`via` TEXT NOT NULL, `is_code` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_seen_fingerprint` ON `sms_seen` (`fingerprint`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_seen_seen_at` ON `sms_seen` (`seen_at`)")
            db.execSQL("INSERT INTO sms_seen (fingerprint, smsc_at, seen_at, via, is_code) VALUES ('a', 1, 2, 'broadcast', 1)")
            db.execSQL("INSERT INTO sms_seen (fingerprint, smsc_at, seen_at, via, is_code) VALUES ('b', 3, 4, 'reconciler', 0)")
            db.version = 1
        }

        val migrated = Room.databaseBuilder(context, ForwarderDatabase::class.java, name)
            .addMigrations(ForwarderDatabase.MIGRATION_1_2)
            .build()
        try {
            val a = migrated.dao().seenByFingerprint("a").single()
            assertTrue(a.live)
            assertNull(a.inboxId)
            assertEquals(SmsSeenEntity.VIA_BROADCAST, a.via)
            val b = migrated.dao().seenByFingerprint("b").single()
            assertFalse(b.live)
            assertNull(b.inboxId)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun keyManagerRefusesAKeysetTheKeystoreDoesNotWrap() {
        // Robolectric has no Android Keystore, so Tink would fall back to a
        // keyset in the clear: the key manager must fail closed instead.
        val keys = AndroidKeyManager(context)
        try {
            keys.aead()
            fail("expected no AEAD without the Android Keystore")
        } catch (expected: GeneralSecurityException) {
        }
        val keyset = File(context.applicationInfo.dataDir, "shared_prefs/nekopost_fwd_keyset.xml")
        assertFalse("no unwrapped keyset may stay on disk", keyset.exists() && keyset.readText().contains("fwd"))
    }
}
