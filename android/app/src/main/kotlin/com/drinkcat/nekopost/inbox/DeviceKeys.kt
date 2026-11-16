package com.drinkcat.nekopost.inbox

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

internal fun ByteArray.url64(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

internal class DeviceKeys {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun init() {
        ensure(DEVICE, false)
        ensure(REAUTH, true)
    }

    private fun ensure(alias: String, biometric: Boolean) {
        if (store().containsAlias(alias)) return
        try {
            generate(alias, biometric, true)
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, biometric, false)
        }
    }

    private fun generate(alias: String, biometric: Boolean, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
        if (biometric) {
            spec.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec.build())
        }.generateKeyPair()
    }

    fun jwk(reauth: Boolean = false): JSONObject {
        val alias = if (reauth) REAUTH else DEVICE
        val point = (store().getCertificate(alias).publicKey as ECPublicKey).w
        return JSONObject().put("kty", "EC").put("crv", "P-256")
            .put("x", unsigned32(point.affineX).url64())
            .put("y", unsigned32(point.affineY).url64())
    }

    fun proof(method: String, target: String, token: String?, issued: Long, nonce: String?): String {
        val header = JSONObject().put("typ", "dpop+jwt").put("alg", "ES256").put("jwk", jwk())
        val claims = JSONObject().put("jti", UUID.randomUUID().toString())
            .put("htm", method).put("htu", target).put("iat", issued)
        if (token != null) {
            claims.put("ath", MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).url64())
        }
        if (nonce != null) claims.put("nonce", nonce)
        val input = header.toString().toByteArray().url64() + "." + claims.toString().toByteArray().url64()
        val der = signer(DEVICE).run { update(input.toByteArray()); sign() }
        return input + "." + derToJose(der).url64()
    }

    fun biometricSignature(): Signature = signer(REAUTH)

    private fun signer(alias: String): Signature =
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(store().getKey(alias, null) as java.security.PrivateKey)
        }

    fun clear() {
        store().apply { deleteEntry(DEVICE); deleteEntry(REAUTH) }
    }

    private companion object {
        const val DEVICE = "nekopost_device_p256"
        const val REAUTH = "nekopost_device_reauth_p256"
    }
}

internal fun unsigned32(number: BigInteger): ByteArray {
    val bytes = number.toByteArray()
    val from = if (bytes.size == 33 && bytes[0] == 0.toByte()) 1 else 0
    val count = bytes.size - from
    require(number.signum() >= 0 && count <= 32)
    return ByteArray(32).also { bytes.copyInto(it, 32 - count, from) }
}

// A P-256 ECDSA signature is a DER sequence of two positive INTEGERs.
internal fun derToJose(der: ByteArray): ByteArray {
    require(der.size in 8..72 && der[0] == 0x30.toByte() && der[1].toInt() == der.size - 2)
    fun parseInteger(at: Int): Pair<ByteArray, Int> {
        require(at + 2 <= der.size && der[at] == 2.toByte())
        val size = der[at + 1].toInt()
        val end = at + 2 + size
        require(size in 1..33 && end <= der.size)
        return unsigned32(BigInteger(der.copyOfRange(at + 2, end))) to end
    }
    val (r, next) = parseInteger(2)
    val (s, end) = parseInteger(next)
    require(end == der.size)
    return r + s
}
