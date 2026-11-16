package com.drinkcat.nekopost.forwarder.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Outbox, forward log and local dedupe state. Holds no plaintext content:
 * payloads are AEAD-sealed, the log has metadata only.
 * Schema v1; export is off until the first migration (M1 has none).
 */
@Database(
    entities = [OutboxEntity::class, LogEntity::class, SentKeyEntity::class, SmsSeenEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class ForwarderDatabase : RoomDatabase() {
    abstract fun dao(): OutboxDao

    companion object {
        const val FILE_NAME = "nekopost-forwarder.db"

        fun build(context: Context): ForwarderDatabase =
            Room.databaseBuilder(context, ForwarderDatabase::class.java, FILE_NAME).build()
    }
}
