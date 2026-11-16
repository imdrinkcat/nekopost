package com.drinkcat.nekopost.core.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EnrollmentLinkTest {

    private val token = "mgs_AbCdEfGhIjKlMnOpQrStUv_-12"

    /** Produced by the server's encoder (Go url.QueryEscape with %20 for spaces). */
    private val goLink = "nekopost://enroll?v=1&kind=source&url=https%3A%2F%2Fosaka.drinkcat.com" +
        "&id=fold8&token=$token" +
        "&name=%E6%88%91%E7%9A%84%20Fold8%20%26%20%E6%89%8B%E6%9C%BA%2B%E7%8C%AB"

    private fun valid(raw: String, debug: Boolean = false): EnrollmentLink =
        when (val r = EnrollmentLink.parse(raw, allowInsecureLocalhost = debug)) {
            is EnrollmentParseResult.Valid -> r.link
            is EnrollmentParseResult.Invalid -> {
                fail("expected valid link, got ${r.error} for $raw")
                throw AssertionError()
            }
        }

    private fun error(raw: String, debug: Boolean = false): EnrollmentError =
        when (val r = EnrollmentLink.parse(raw, allowInsecureLocalhost = debug)) {
            is EnrollmentParseResult.Valid -> {
                fail("expected rejection for $raw")
                throw AssertionError()
            }
            is EnrollmentParseResult.Invalid -> r.error
        }

    private fun link(
        url: String = "https%3A%2F%2Fosaka.drinkcat.com",
        id: String = "fold8",
        tok: String = token,
        name: String? = "Fold8",
        prefix: String = "nekopost://enroll?v=1&kind=source",
    ) = "$prefix&url=$url&id=$id&token=$tok" + (name?.let { "&name=$it" } ?: "")

    @Test
    fun parsesServerGeneratedLink() {
        val l = valid(goLink)
        assertEquals("https://osaka.drinkcat.com", l.serverUrl)
        assertEquals("osaka.drinkcat.com", l.serverHost)
        assertEquals("fold8", l.sourceId)
        assertEquals(token, l.token)
        assertEquals("我的 Fold8 & 手机+猫", l.displayName)
    }

    @Test
    fun encodesExactlyLikeTheServer() {
        assertEquals(goLink, valid(goLink).toUriString())
    }

    @Test
    fun roundTripsThroughCreate() {
        val created = (EnrollmentLink.create("https://a.example:8443", "phone-2", token, "备用 机") as EnrollmentParseResult.Valid).link
        assertEquals(created, valid(created.toUriString()))
    }

    @Test
    fun trimsWhitespaceAndAcceptsUpperCaseSchemeAndHost() {
        valid("  $goLink\n")
        valid(goLink.replaceFirst("nekopost://enroll", "NekoPost://ENROLL"))
    }

    @Test
    fun normalizesHostCase() {
        assertEquals("https://osaka.drinkcat.com", valid(link(url = "https%3A%2F%2FOsaka.DrinkCat.com")).serverUrl)
    }

    @Test
    fun keepsPort() {
        assertEquals("https://osaka.drinkcat.com:8443", valid(link(url = "https%3A%2F%2Fosaka.drinkcat.com%3A8443")).serverUrl)
    }

    @Test
    fun missingOrBlankNameFallsBackToSourceId() {
        assertEquals("fold8", valid(link(name = null)).displayName)
        assertEquals("fold8", valid(link(name = "%20%20")).displayName)
    }

    @Test
    fun plusDecodesAsSpace() {
        assertEquals("a b", valid(link(name = "a+b")).displayName)
    }

    @Test
    fun toStringHidesToken() {
        assertFalse(valid(goLink).toString().contains(token))
    }

    @Test
    fun rejectsOtherSchemesAndHosts() {
        assertEquals(EnrollmentError.NotNekopostLink, error(goLink.replace("nekopost://", "https://")))
        assertEquals(EnrollmentError.NotNekopostLink, error(goLink.replace("://enroll?", "://pair?")))
        assertEquals(EnrollmentError.NotNekopostLink, error(goLink.replace("://enroll?", "://enroll/?")))
        assertEquals(EnrollmentError.NotNekopostLink, error(goLink.replace("://enroll?", "://enroll.evil?")))
        assertEquals(EnrollmentError.NotNekopostLink, error("$goLink#frag"))
        assertEquals(EnrollmentError.NotNekopostLink, error("hello"))
        assertEquals(EnrollmentError.NotNekopostLink, error(""))
    }

    @Test
    fun rejectsNonAsciiOrSpacesInRawLink() {
        assertEquals(EnrollmentError.NotNekopostLink, error(link(name = "猫")))
        assertEquals(EnrollmentError.NotNekopostLink, error(link(name = "a b")))
    }

    @Test
    fun rejectsOtherVersionsAndKinds() {
        assertEquals(EnrollmentError.UnsupportedVersion, error(goLink.replace("v=1&", "v=2&")))
        assertEquals(EnrollmentError.UnsupportedVersion, error(goLink.replace("v=1&", "v=01&")))
        assertEquals(EnrollmentError.MissingParameter, error(goLink.replace("v=1&", "")))
        assertEquals(EnrollmentError.UnsupportedKind, error(goLink.replace("kind=source", "kind=device")))
        assertEquals(EnrollmentError.MissingParameter, error(goLink.replace("kind=source&", "")))
    }

    @Test
    fun rejectsMalformedQueries() {
        assertEquals(EnrollmentError.MalformedQuery, error("$goLink&"))
        assertEquals(EnrollmentError.MalformedQuery, error("$goLink&name"))
        assertEquals(EnrollmentError.MalformedQuery, error("$goLink&=x"))
        assertEquals(EnrollmentError.MalformedQuery, error("$goLink&Name=x"))
        assertEquals(EnrollmentError.MalformedQuery, error(link(name = "%E6%88")))
        assertEquals(EnrollmentError.MalformedQuery, error(link(name = "%zz")))
        assertEquals(EnrollmentError.MalformedQuery, error(link(name = "abc%4")))
    }

    @Test
    fun rejectsDuplicateAndUnknownParameters() {
        assertEquals(EnrollmentError.DuplicateParameter, error("$goLink&token=$token"))
        assertEquals(EnrollmentError.DuplicateParameter, error("$goLink&v=1"))
        assertEquals(EnrollmentError.UnknownParameter, error("$goLink&extra=1"))
    }

    @Test
    fun requiresUrlIdAndToken() {
        assertEquals(EnrollmentError.MissingParameter, error(goLink.replace("&id=fold8", "")))
        assertEquals(EnrollmentError.MissingParameter, error(goLink.replace("&token=$token", "")))
        assertEquals(EnrollmentError.MissingParameter, error(goLink.replace("&url=https%3A%2F%2Fosaka.drinkcat.com", "")))
    }

    @Test
    fun rejectsBadServerUrls() {
        val bad = listOf(
            "https%3A%2F%2Fosaka.drinkcat.com%2F", // trailing slash
            "https%3A%2F%2Fosaka.drinkcat.com%2Fapi", // path
            "https%3A%2F%2Fosaka.drinkcat.com%3Fa%3D1", // query
            "https%3A%2F%2Fu%3Ap%40osaka.drinkcat.com", // user info
            "https%3A%2F%2F", // no host
            "ftp%3A%2F%2Fosaka.drinkcat.com",
            "osaka.drinkcat.com",
            "https%3A%2F%2Fosaka%20drinkcat.com",
            "",
        )
        for (u in bad) assertEquals(u, EnrollmentError.InvalidServerUrl, error(link(url = u)))
    }

    @Test
    fun rejectsPlainHttpOutsideDebugLocalhost() {
        assertEquals(EnrollmentError.InsecureServerUrl, error(link(url = "http%3A%2F%2Fosaka.drinkcat.com")))
        assertEquals(EnrollmentError.InsecureServerUrl, error(link(url = "http%3A%2F%2F10.0.2.2%3A8080")))
        assertEquals(EnrollmentError.InsecureServerUrl, error(link(url = "http%3A%2F%2Fosaka.drinkcat.com"), debug = true))
        assertEquals(EnrollmentError.InsecureServerUrl, error(link(url = "http%3A%2F%2F192.168.1.2"), debug = true))
    }

    @Test
    fun allowsPlainHttpToEmulatorHostInDebug() {
        assertEquals("http://10.0.2.2:8080", valid(link(url = "http%3A%2F%2F10.0.2.2%3A8080"), debug = true).serverUrl)
        assertEquals("http://localhost:8080", valid(link(url = "http%3A%2F%2Flocalhost%3A8080"), debug = true).serverUrl)
    }

    @Test
    fun validatesSourceId() {
        for (id in listOf("", "Fold8", "-x", "_x", "a".repeat(33), "a%2Fb", "a.b")) {
            assertEquals(id, EnrollmentError.InvalidSourceId, error(link(id = id)))
        }
        valid(link(id = "a".repeat(32)))
        valid(link(id = "0-a_b"))
    }

    @Test
    fun validatesToken() {
        for (t in listOf("", "mga_AbCdEfGhIjKlMnOpQrStUv", "mgs_short", "mgs_AbCdEfGhIjKlMnOpQrSt.v", "MGS_AbCdEfGhIjKlMnOpQrStUv")) {
            assertEquals(t, EnrollmentError.InvalidToken, error(link(tok = t)))
        }
        valid(link(tok = "mgs_" + "a".repeat(20)))
    }

    @Test
    fun validatesName() {
        assertEquals(EnrollmentError.InvalidName, error(link(name = "a%0Ab")))
        assertEquals(EnrollmentError.InvalidName, error(link(name = "x".repeat(129))))
        valid(link(name = "x".repeat(128)))
    }

    @Test
    fun rejectsOverlongLinks() {
        assertEquals(EnrollmentError.TooLong, error(link(name = "x".repeat(2100))))
    }

    @Test
    fun parseOrNull() {
        assertNull(EnrollmentLink.parseOrNull("nekopost://enroll?v=2"))
        assertTrue(EnrollmentLink.parseOrNull(goLink) != null)
    }
}
