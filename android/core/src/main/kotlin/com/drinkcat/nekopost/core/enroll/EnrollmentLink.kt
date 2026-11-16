package com.drinkcat.nekopost.core.enroll

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A validated source-enrollment link, as printed by `nekopost source add --qr`
 * (server package internal/enroll). The format is a contract shared with the
 * server and changes only together with `v`:
 *
 *     nekopost://enroll?v=1&kind=source&url=<base>&id=<id>&token=<mgs_…>&name=<name>
 *
 * Values are percent-encoded UTF-8. Anything that does not match exactly is
 * rejected: other schemes/hosts/versions/kinds, unknown or repeated
 * parameters, non-https server URLs (plain http only for 10.0.2.2 and
 * localhost when [parse] is called with `allowInsecureLocalhost = true`, i.e.
 * debug builds), and tokens not matching `^mgs_[A-Za-z0-9_-]{20,}$`.
 *
 * [toString] never contains the token.
 */
class EnrollmentLink private constructor(
    /** Normalized base URL: scheme://host[:port], lower-case, no trailing slash. */
    val serverUrl: String,
    /** Source ID on the server, `^[a-z0-9][a-z0-9_-]{0,31}$`. */
    val sourceId: String,
    /** The `mgs_` source token. Secret: never log it. */
    val token: String,
    /** Human readable source name (falls back to [sourceId]). */
    val displayName: String,
) {
    /** Host (and port, if any) of [serverUrl], for display. */
    val serverHost: String get() = serverUrl.substringAfter("://")

    /** Encodes the link exactly like the server does (spaces as %20, fixed parameter order). */
    fun toUriString(): String =
        "$SCHEME://$HOST?v=$VERSION&kind=$KIND_SOURCE" +
            "&url=${encode(serverUrl)}&id=${encode(sourceId)}&token=${encode(token)}&name=${encode(displayName)}"

    override fun equals(other: Any?): Boolean =
        other is EnrollmentLink && other.serverUrl == serverUrl && other.sourceId == sourceId &&
            other.token == token && other.displayName == displayName

    override fun hashCode(): Int = listOf(serverUrl, sourceId, token, displayName).hashCode()

    override fun toString(): String =
        "EnrollmentLink(serverUrl=$serverUrl, sourceId=$sourceId, token=mgs_…, displayName=$displayName)"

    companion object {
        const val SCHEME = "nekopost"
        const val HOST = "enroll"
        const val VERSION = "1"
        const val KIND_SOURCE = "source"
        const val MAX_LINK_LENGTH = 2048
        const val MAX_NAME_LENGTH = 128

        val TOKEN_PATTERN = Regex("^mgs_[A-Za-z0-9_-]{20,}$")
        val SOURCE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

        /** Hosts for which plain http is accepted in debug builds (emulator → host machine). */
        val INSECURE_DEBUG_HOSTS = setOf("10.0.2.2", "localhost")

        private val KNOWN_KEYS = setOf("v", "kind", "url", "id", "token", "name")
        private val KEY_PATTERN = Regex("^[a-z]{1,16}$")

        /** Parses a scanned or pasted link. Leading/trailing whitespace is ignored. */
        fun parse(raw: String, allowInsecureLocalhost: Boolean = false): EnrollmentParseResult {
            val text = raw.trim()
            if (text.length > MAX_LINK_LENGTH) return invalid(EnrollmentError.TooLong)
            if (text.any { it.code !in 0x21..0x7e }) return invalid(EnrollmentError.NotNekopostLink)

            val prefix = "$SCHEME://$HOST?"
            if (!text.startsWith(prefix, ignoreCase = true)) return invalid(EnrollmentError.NotNekopostLink)
            if (text.contains('#')) return invalid(EnrollmentError.NotNekopostLink)

            val params = LinkedHashMap<String, String>()
            for (pair in text.substring(prefix.length).split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) return invalid(EnrollmentError.MalformedQuery)
                val key = pair.substring(0, eq)
                if (!KEY_PATTERN.matches(key)) return invalid(EnrollmentError.MalformedQuery)
                val value = decode(pair.substring(eq + 1)) ?: return invalid(EnrollmentError.MalformedQuery)
                if (params.put(key, value) != null) return invalid(EnrollmentError.DuplicateParameter)
            }

            // The version decides how everything else is read, so check it first.
            val version = params["v"] ?: return invalid(EnrollmentError.MissingParameter)
            if (version != VERSION) return invalid(EnrollmentError.UnsupportedVersion)
            // Kind before unknown parameters: a device pairing link carries `code`
            // and must be reported as such, not as a broken link.
            val kind = params["kind"] ?: return invalid(EnrollmentError.MissingParameter)
            if (kind != KIND_SOURCE) return invalid(EnrollmentError.UnsupportedKind)
            if (params.keys.any { it !in KNOWN_KEYS }) return invalid(EnrollmentError.UnknownParameter)

            val url = params["url"] ?: return invalid(EnrollmentError.MissingParameter)
            val id = params["id"] ?: return invalid(EnrollmentError.MissingParameter)
            val token = params["token"] ?: return invalid(EnrollmentError.MissingParameter)
            return create(url, id, token, params["name"], allowInsecureLocalhost)
        }

        /** Like [parse] but returns null for anything invalid. */
        fun parseOrNull(raw: String, allowInsecureLocalhost: Boolean = false): EnrollmentLink? =
            (parse(raw, allowInsecureLocalhost) as? EnrollmentParseResult.Valid)?.link

        /** Validates already-decoded fields (manual entry, tests). */
        fun create(
            serverUrl: String,
            sourceId: String,
            token: String,
            displayName: String? = null,
            allowInsecureLocalhost: Boolean = false,
        ): EnrollmentParseResult {
            val base = when (val r = normalizeServerUrl(serverUrl, allowInsecureLocalhost)) {
                is UrlCheck.Ok -> r.url
                is UrlCheck.Bad -> return invalid(r.error)
            }
            if (!SOURCE_ID_PATTERN.matches(sourceId)) return invalid(EnrollmentError.InvalidSourceId)
            if (!TOKEN_PATTERN.matches(token)) return invalid(EnrollmentError.InvalidToken)

            val name = displayName?.trim().orEmpty().ifEmpty { sourceId }
            if (name.codePointCount(0, name.length) > MAX_NAME_LENGTH ||
                name.any { Character.isISOControl(it) }
            ) {
                return invalid(EnrollmentError.InvalidName)
            }
            return EnrollmentParseResult.Valid(EnrollmentLink(base, sourceId, token, name))
        }

        private sealed interface UrlCheck {
            data class Ok(val url: String) : UrlCheck
            data class Bad(val error: EnrollmentError) : UrlCheck
        }

        /** Mirrors the server's enroll.NormalizeBaseURL, plus the debug-only http exception. */
        private fun normalizeServerUrl(raw: String, allowInsecureLocalhost: Boolean): UrlCheck {
            val bad = UrlCheck.Bad(EnrollmentError.InvalidServerUrl)
            if (raw.isEmpty() || raw.endsWith("/")) return bad
            if (raw.any { it.isWhitespace() || it == '\\' || it == '#' }) return bad
            val uri = try {
                URI(raw)
            } catch (e: URISyntaxException) {
                return bad
            }
            val scheme = uri.scheme?.lowercase() ?: return bad
            val host = uri.host?.lowercase()
            if (uri.isOpaque || host.isNullOrEmpty()) return bad
            if (uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
                return bad
            }
            when (scheme) {
                "https" -> Unit
                "http" -> if (!allowInsecureLocalhost || host !in INSECURE_DEBUG_HOSTS) {
                    return UrlCheck.Bad(EnrollmentError.InsecureServerUrl)
                }
                else -> return bad
            }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return UrlCheck.Ok("$scheme://$host$port")
        }

        private fun invalid(error: EnrollmentError) = EnrollmentParseResult.Invalid(error)

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

        /** Percent-encodes like Go's url.QueryEscape, with spaces as %20 (never "+"). */
        internal fun encode(value: String): String = buildString {
            for (b in value.toByteArray(StandardCharsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c in UNRESERVED) {
                    append(c)
                } else {
                    append('%')
                    append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xf])
                    append("0123456789ABCDEF"[b.toInt() and 0xf])
                }
            }
        }

        /**
         * Strict percent-decoding of a query value: "+" is a space (as in Go's
         * url.QueryUnescape), every "%" must be followed by two hex digits, and
         * the result must be valid UTF-8. Returns null otherwise.
         */
        internal fun decode(value: String): String? {
            val out = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                when (val c = value[i]) {
                    '%' -> {
                        if (i + 2 >= value.length) return null
                        val hi = Character.digit(value[i + 1], 16)
                        val lo = Character.digit(value[i + 2], 16)
                        if (hi < 0 || lo < 0) return null
                        out.write(hi * 16 + lo)
                        i += 3
                    }
                    '+' -> {
                        out.write(' '.code)
                        i++
                    }
                    else -> {
                        out.write(c.code)
                        i++
                    }
                }
            }
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString()
            } catch (e: CharacterCodingException) {
                null
            }
        }
    }
}

/** Result of [EnrollmentLink.parse] / [EnrollmentLink.create]. */
sealed interface EnrollmentParseResult {
    data class Valid(val link: EnrollmentLink) : EnrollmentParseResult
    data class Invalid(val error: EnrollmentError) : EnrollmentParseResult
}

/** Why a link was rejected; the UI maps each value to a Chinese message. */
enum class EnrollmentError {
    /** Longer than [EnrollmentLink.MAX_LINK_LENGTH]. */
    TooLong,

    /** Not a `nekopost://enroll?` link at all (other QR code, URL, text). */
    NotNekopostLink,

    /** `v` is not "1": the app is older than the server's link format. */
    UnsupportedVersion,

    /** `kind` is not "source" (e.g. a device pairing code). */
    UnsupportedKind,

    /** A parameter is not `key=value` or has broken percent-encoding. */
    MalformedQuery,

    DuplicateParameter,
    UnknownParameter,
    MissingParameter,

    /** The server URL is not `https://host[:port]` without path, query or trailing slash. */
    InvalidServerUrl,

    /** Plain http for a host other than the debug-only emulator/localhost hosts. */
    InsecureServerUrl,

    InvalidSourceId,
    InvalidToken,
    InvalidName,
}
