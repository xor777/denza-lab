package dev.denza.apps.feature.speaker

import dev.denza.apps.StateMarks
import dev.denza.apps.StateSlice

/**
 * Whether a report is on the wire right now. It is the only thing the service tells the screen,
 * and the screen spends it on one thing: greying «Поднять» for the second the shell call takes.
 *
 * It is not a status and never reaches the tile - see [SpeakerCoverStatus] for why.
 */
object SpeakerCoverRuntime {
    /** Marks the speakers' slice of the dashboard when it changes: the panel's button reads it. */
    @Volatile
    var reporting: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            StateMarks.mark(StateSlice.SPEAKER_COVERS, "speakers reporting")
        }
}
