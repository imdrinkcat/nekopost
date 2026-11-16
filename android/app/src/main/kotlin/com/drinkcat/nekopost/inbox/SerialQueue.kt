package com.drinkcat.nekopost.inbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * Runs inbox operations one at a time. User actions queue (they are never
 * dropped because a push fetch or another call holds the lock); repeated
 * requests that only need to happen once (a refresh, marking the same message
 * read) are coalesced while one is still waiting.
 */
internal class SerialQueue {
    private val mutex = Mutex()
    private val waiting = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Runs [block] unless an identical request ([key]) is already waiting for the
     * lock; returns false when coalesced. A request arriving while the previous
     * one is running is queued, so it sees the newest state.
     */
    suspend fun coalesce(key: String, block: suspend () -> Unit): Boolean {
        if (!waiting.add(key)) return false
        var started = false
        try {
            mutex.withLock {
                waiting.remove(key)
                started = true
                block()
            }
        } finally {
            if (!started) waiting.remove(key)
        }
        return true
    }
}
