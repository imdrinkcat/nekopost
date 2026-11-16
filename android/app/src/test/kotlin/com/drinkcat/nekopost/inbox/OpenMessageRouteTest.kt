package com.drinkcat.nekopost.inbox

import com.drinkcat.nekopost.inbox.notify.OpenMessageActivity
import com.drinkcat.nekopost.ui.shell.NavRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification trampoline accepts a well-formed message ID and nothing else. */
class OpenMessageRouteTest {
    @Test
    fun validIdBecomesANavigationRequest() {
        val got = mutableListOf<NavRequest>()
        assertTrue(OpenMessageActivity.route("m_01JB7QAB12CD34EF56GH78JK9M") { got += it })
        assertEquals(listOf(NavRequest.OpenMessage("m_01JB7QAB12CD34EF56GH78JK9M")), got)
    }

    @Test
    fun anythingElseOnlyOpensTheApp() {
        val got = mutableListOf<NavRequest>()
        for (bad in listOf(null, "", "m_short", "x_01JB7QAB12CD34EF56GH78JK9M", "m_01JB7QAB12CD34EF56GH78JK9M\n", "../m_01JB7QAB12CD34EF56GH78JK9M")) {
            assertFalse(OpenMessageActivity.route(bad) { got += it })
        }
        assertTrue(got.isEmpty())
    }
}
