package com.drinkcat.nekopost.forwarder.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Outbox, forward log and local dedupe state. Holds no plaintext content:
 * payloads are AEAD-sealed, the log has metadata only.
 * Schema v2 (v1 + sms_seen.inbox_id / live for one-to-one SMS matching).
 */
@Database(
    entities = [OutboxEntity::class, LogEntity::class, SentKeyEntity::class, SmsSeenEntity::class],
    version = 2,
    exportSchema = false,
)
internal abstract class ForwarderDatabase : RoomDatabase() {
    abstract fun dao(): OutboxDao

    companion object {
        const val FILE_NAME = "nekopost-forwarder.db"

        fun build(context: Context): ForwarderDatabase =
            Room.databaseBuilder(context, ForwarderDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v1 → v2: rows the broadcast captured are "live"; none is matched to an inbox row yet. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sms_seen ADD COLUMN inbox_id INTEGER")
                db.execSQL("ALTER TABLE sms_seen ADD COLUMN live INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sms_seen SET live = 1 WHERE via = 'broadcast'")
            }
        }
    }
}
