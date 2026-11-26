package com.example.m30probe

internal data class TriggerSettings(
    val actuation: Int,
    val rapidTrigger: Boolean,
    val continuousRapidTrigger: Boolean,
    val pressDistance: Int,
    val releaseDistance: Int
)

/** Decodes the trigger values stored in one key's eight-byte profile record. */
internal fun readTriggerSettings(profileData: ByteArray, keyIndex: Int): TriggerSettings? {
    if (keyIndex < 0 || keyIndex >= profileData.size / 8) return null
    val offset = keyIndex * 8

    fun unsigned(index: Int): Int = profileData[offset + index].toInt() and 0xFF
    fun distance(lowIndex: Int, highIndex: Int): Int =
        (((unsigned(highIndex) and 1) shl 8) or unsigned(lowIndex)) + 1

    val mode = unsigned(1) and 0x0F
    return TriggerSettings(
        actuation = distance(2, 3),
        rapidTrigger = mode > 0,
        continuousRapidTrigger = mode >= 2,
        pressDistance = distance(4, 5),
        releaseDistance = distance(6, 7)
    )
}
