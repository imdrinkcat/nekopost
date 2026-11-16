package com.drinkcat.nekopost.core.enroll

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the parser against `enroll-fixtures.json`, which the server's Go
 * tests generate from its link builder (internal/enroll, `-update-fixture`)
 * and verify byte for byte. Together they keep both sides of the QR contract
 * (CONTRACT.md §2) in step.
 */
class EnrollmentLinkFixtureTest {

    private val fixture: JsonObject = run {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("enroll-fixtures.json")) {
            "enroll-fixtures.json missing from test resources"
        }
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).use { it.readText() }).jsonObject
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun cases(key: String): List<JsonObject> = fixture.getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun acceptsEveryServerLinkUnchanged() {
        val valid = cases("valid")
        assertTrue("fixture has only ${valid.size} valid cases", valid.size >= 10)
        for (c in valid) {
            val link = c.str("link")
            val result = EnrollmentLink.parse(link)
            assertTrue("rejected server link ${c.str("id")}: $result", result is EnrollmentParseResult.Valid)
            val parsed = (result as EnrollmentParseResult.Valid).link
            assertEquals(c.str("serverUrl"), parsed.serverUrl)
            assertEquals(c.str("serverHost"), parsed.serverHost)
            assertEquals(c.str("id"), parsed.sourceId)
            assertEquals(c.str("token"), parsed.token)
            assertEquals(c.str("displayName"), parsed.displayName)
            assertEquals("re-encoding ${c.str("id")}", link, parsed.toUriString())
        }
    }

    @Test
    fun createNormalizesServerInputLikeTheServer() {
        for (c in cases("valid")) {
            val result = EnrollmentLink.create(c.str("base"), c.str("id"), c.str("token"), c.str("name"))
            assertTrue("create rejected ${c.str("id")}: $result", result is EnrollmentParseResult.Valid)
            assertEquals(c.str("link"), (result as EnrollmentParseResult.Valid).link.toUriString())
        }
    }

    @Test
    fun rejectsWhatTheServerRejects() {
        val rejected = cases("rejected")
        assertTrue(rejected.isNotEmpty())
        for (c in rejected.filter { it.getValue("appRejects").jsonPrimitive.boolean }) {
            val result = EnrollmentLink.create(c.str("base"), c.str("id"), c.str("token"), c.str("name"))
            assertTrue("accepted ${c.str("why")}", result is EnrollmentParseResult.Invalid)
        }
    }

    @Test
    fun overlongLinksAreRejectedByTheParser() {
        // The server refuses to build links over MAX_LINK_LENGTH; the parser must refuse them too.
        val c = cases("rejected").first { it.str("token").length > EnrollmentLink.MAX_LINK_LENGTH }
        val raw = "nekopost://enroll?v=1&kind=source&url=https%3A%2F%2Fexample.com&id=p&token=${c.str("token")}&name=x"
        assertEquals(EnrollmentParseResult.Invalid(EnrollmentError.TooLong), EnrollmentLink.parse(raw))
    }
}
