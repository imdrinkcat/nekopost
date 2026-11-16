package com.drinkcat.nekopost.forwarder.upload

import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Certificate pins for the Nekopost server, which uses Let's Encrypt.
 *
 * We pin the ISRG roots (SPKI SHA-256, base64), not the leaf or the
 * intermediates, so routine certificate renewals and intermediate rotations
 * never break forwarding. Values were computed from the PEMs published at
 * https://letsencrypt.org/certificates/ and checked against the copies in the
 * Debian ca-certificates store (2026-11-16):
 * - ISRG Root X1 (RSA 4096, valid until 2035-06-04)
 * - ISRG Root X2 (ECDSA P-384, valid until 2040-09-17)
 * - Root YE / Root YR (Generation Y, valid until 2045-09-02; not yet in
 *   trust stores, today they chain up to X2 / X1 — pinned so the switch to
 *   them cannot break us)
 *
 * After [EXPIRES_AT] pinning is skipped (fail open, like Android's
 * network-security-config `<pin-set expiration>`), so an app that was not
 * updated for a year keeps working with plain system trust.
 */
internal object ServerPins {
    const val ISRG_ROOT_X1 = "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="
    const val ISRG_ROOT_X2 = "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="
    const val ISRG_ROOT_YE = "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w="
    const val ISRG_ROOT_YR = "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88="

    val ALL: List<String> = listOf(ISRG_ROOT_X1, ISRG_ROOT_X2, ISRG_ROOT_YE, ISRG_ROOT_YR)

    /** About one year after this release; bump it with every release. */
    val EXPIRES_AT: Instant = Instant.parse("2027-11-16T00:00:00Z")

    fun isActive(now: Instant): Boolean = now.isBefore(EXPIRES_AT)

    fun pinnerFor(host: String, now: Instant): CertificatePinner =
        if (isActive(now)) CertificatePinner.Builder().add(host, *ALL.toTypedArray()).build() else CertificatePinner.DEFAULT
}

/** One shared connection pool; per-server clients only add the pins. */
internal class HttpClients(private val base: OkHttpClient, private val clock: Clock) {

    fun forServer(url: HttpUrl): OkHttpClient =
        if (url.isHttps) {
            base.newBuilder().certificatePinner(ServerPins.pinnerFor(url.host, clock.instant())).build()
        } else {
            // Plain http only reaches here for 10.0.2.2 / localhost in debug builds (EnrollmentLink).
            base
        }

    companion object {
        /** No logging interceptor: bodies and tokens must never reach logcat. */
        fun defaultBase(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            // A redirect is never expected; do not carry the token anywhere else.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
