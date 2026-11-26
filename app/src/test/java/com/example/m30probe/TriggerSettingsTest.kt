package com.example.m30probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriggerSettingsTest {
    @Test
    fun decodesTheSelectedKeysSavedTriggerValues() {
        val profileData = ByteArray(1024)
        val offset = 6 * 8
        profileData[offset + 1] = 2
        profileData[offset + 2] = 79
        profileData[offset + 3] = 1
        profileData[offset + 4] = 24
        profileData[offset + 6] = 49

        assertEquals(
            TriggerSettings(
                actuation = 336,
                rapidTrigger = true,
                continuousRapidTrigger = true,
                pressDistance = 25,
                releaseDistance = 50
            ),
            readTriggerSettings(profileData, 6)
        )
    }

    @Test
    fun rejectsAKeyOutsideTheLoadedProfileData() {
        assertNull(readTriggerSettings(ByteArray(8), 1))
    }
}
