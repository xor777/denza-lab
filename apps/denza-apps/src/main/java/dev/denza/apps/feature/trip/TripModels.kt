package dev.denza.apps.feature.trip

/** Offline sun facts for the current position. */
data class SunInfo(
    val nextIsSunset: Boolean,
    val nextEventLabel: String,
    val countdownSeconds: Long,
)
