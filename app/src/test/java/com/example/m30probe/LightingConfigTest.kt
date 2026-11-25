package com.example.m30probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class LightingConfigTest {
    @Test
    fun configureSingleColorEnablesLightingAndPreservesUnchangedBytes() {
        val original = ByteArray(64) { it.toByte() }

        val updated = configureSingleColor(original, "#12aBef", 73)

        assertNotSame(original, updated)
        assertEquals(1, updated[8].toInt() and 0xff)
        assertEquals(73, updated[9].toInt() and 0xff)
        assertEquals(0, updated[12].toInt() and 0xff)
        assertEquals(0x12, updated[14].toInt() and 0xff)
        assertEquals(0xab, updated[15].toInt() and 0xff)
        assertEquals(0xef, updated[16].toInt() and 0xff)
        for (index in original.indices) {
            if (index !in setOf(8, 9, 12, 14, 15, 16)) {
                assertEquals("byte $index", original[index], updated[index])
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun configureSingleColorRejectsMalformedColor() {
        configureSingleColor(ByteArray(64), "red", 50)
    }

    @Test(expected = IllegalArgumentException::class)
    fun configureSingleColorRejectsUnexpectedConfigSize() {
        configureSingleColor(ByteArray(63), "FFFFFF", 50)
    }

    @Test
    fun configureSingleColorClampsBrightnessToSupportedRange() {
        assertEquals(0, configureSingleColor(ByteArray(64), "FFFFFF", -1)[9].toInt() and 0xff)
        assertEquals(100, configureSingleColor(ByteArray(64), "FFFFFF", 101)[9].toInt() and 0xff)
    }
}
