package dev.denza.apps.feature.speaker

/**
 * Whether a report is on the wire right now. It is the only thing the service tells the screen,
 * and the screen spends it on one thing: greying «Поднять» for the second the shell call takes.
 *
 * It is not a status and never reaches the tile - see [SpeakerCoverStatus] for why.
 */
object SpeakerCoverRuntime {
    @Volatile
    var reporting: Boolean = false
}
