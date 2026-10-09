package dev.denza.apps.feature.trip

/** Offline sun facts for the current position: the next event and its local time. */
data class SunInfo(
    val nextIsSunset: Boolean,
    val nextEventLabel: String,
)
