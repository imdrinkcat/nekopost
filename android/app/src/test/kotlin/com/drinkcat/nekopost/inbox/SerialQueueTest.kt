package com.drinkcat.nekopost.inbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** User actions wait for a running push fetch instead of being dropped (the old tryLock bug). */
class SerialQueueTest {
    @Test
    fun markReadDuringAPushFetchStillRuns() = runTest {
        val queue = SerialQueue()
        val fetchGate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val fetch = launch { queue.run { fetchGate.await(); order += "fetch" } }
        yield()
        val read = launch { queue.run { order += "read" } }
        yield()
        assertTrue(order.isEmpty())
        fetchGate.complete(Unit)
        fetch.join()
        read.join()
        assertEquals(listOf("fetch", "read"), order)
    }

    @Test
    fun repeatedRefreshesWhileOneWaitsAreCoalesced() = runTest {
        val queue = SerialQueue()
        val gate = CompletableDeferred<Unit>()
        var refreshes = 0
        val busy = launch { queue.run { gate.await() } }
        yield()
        val first = async { queue.coalesce("refresh") { refreshes++ } }
        yield()
        val second = async { queue.coalesce("refresh") { refreshes++ } }
        yield()
        gate.complete(Unit)
        busy.join()
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(1, refreshes)
        // Once it ran, the next refresh runs again.
        assertTrue(queue.coalesce("refresh") { refreshes++ })
        assertEquals(2, refreshes)
    }
}
