package dev.denza.apps.feature.trip

/** Remaining-route figures sourced only from validated Yandex guidance. */
data class GuidanceRemaining(val distanceMeters: Int?, val timeSeconds: Int?)

/** Offline sun facts for the current position. */
data class SunInfo(
    val nextIsSunset: Boolean,
    val nextEventLabel: String,
    val countdownSeconds: Long,
)
