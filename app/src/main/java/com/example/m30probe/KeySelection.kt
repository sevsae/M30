package com.example.m30probe

/** Starts each profile with all discovered keys selected, while allowing a rebuild to retain a subset. */
internal fun initialKeySelection(
    availableKeys: Collection<Int>,
    retained: Set<Int>? = null
): Set<Int> {
    val available = availableKeys.toSet()
    return (retained ?: available).intersect(available)
}
