package com.example.m30probe

/** Returns a copy of a 64-byte lighting bank with a supported active effect and color. */
internal fun configureSingleColor(
    config: ByteArray,
    color: String,
    brightness: Int
): ByteArray {
    require(config.size == 64) { "Lighting config must be 64 bytes." }
    val rgb = color.trim().removePrefix("#")
    require(rgb.matches(Regex("[0-9a-fA-F]{6}"))) { "Enter a six-digit RGB color." }

    return config.copyOf().apply {
        // The bundled keyboard software defines lighting effects 1 through 6; 0 leaves LEDs off.
        this[8] = 1
        this[9] = brightness.coerceIn(0, 100).toByte()
        this[12] = 0
        this[14] = rgb.substring(0, 2).toInt(16).toByte()
        this[15] = rgb.substring(2, 4).toInt(16).toByte()
        this[16] = rgb.substring(4, 6).toInt(16).toByte()
    }
}
