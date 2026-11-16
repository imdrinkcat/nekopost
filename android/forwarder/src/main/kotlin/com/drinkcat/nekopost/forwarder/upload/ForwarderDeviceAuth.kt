package com.drinkcat.nekopost.forwarder.upload

import okhttp3.HttpUrl
import java.time.Instant

/** Optional proof signer supplied by the application after device pairing. */
object ForwarderDeviceAuth {
    @Volatile
    var signer: ((server: HttpUrl, source: String, target: String, at: Instant, nonce: String?) -> Proof?)? = null

    class Proof(val token: String, val jws: String) {
        override fun toString() = "DeviceProof"
    }
}
