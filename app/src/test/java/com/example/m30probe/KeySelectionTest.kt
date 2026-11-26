package com.example.m30probe

import org.junit.Assert.assertEquals
import org.junit.Test

class KeySelectionTest {
    @Test
    fun allDetectedKeysAreSelectedForANewProfile() {
        assertEquals(setOf(1, 4, 12), initialKeySelection(listOf(1, 4, 12)))
    }

    @Test
    fun aRetainedSelectionIsLimitedToKeysInTheCurrentProfile() {
        assertEquals(
            setOf(4),
            initialKeySelection(listOf(1, 4, 12), retained = setOf(4, 99))
        )
    }
}
