package com.drinkcat.nekopost.inbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class DeviceEncodingTest {
    @Test
    fun p256DerCoordinatesArePaddedForJws() {
        val der = byteArrayOf(0x30, 6, 2, 1, 42, 2, 1, 7)
        val proof = derToJose(der)
        assertEquals(64, proof.size)
        assertEquals(42, proof[31].toInt())
        assertEquals(7, proof[63].toInt())
    }

    @Test
    fun unsignedBigIntegerDoesNotIncludeSignByte() {
        val bits = ByteArray(32) { 0xff.toByte() }
        assertArrayEquals(bits, unsigned32(BigInteger(1, bits)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedSignatureIsRejected() {
        derToJose(byteArrayOf(0x30, 6, 2, 1, 42, 2))
    }
}
