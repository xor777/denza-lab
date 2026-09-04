package dev.denza.apps.feature.speaker

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb

/**
 * Ask the policy, and if it says so, tell the car once.
 *
 * Nothing is read first. The earlier shape read the amplifier's auto-lift setting and wrote it
 * back on when it was off, which made the app a second hand on the car's own switch; that switch
 * is the driver's (on the N9) or permanently on (on the Z9GT), and either way not ours to move.
 * One shell command, about a second, is the whole trip.
 */
internal object SpeakerCoverTransport {

    /** Whether the car was told; false means the policy kept quiet, not that anything failed. */
    fun run(
        context: Context,
        trigger: SpeakerCoverTrigger,
        featureEnabled: Boolean,
    ): Boolean {
        if (!SpeakerCoverPolicy.reports(trigger, featureEnabled)) return false
        val output = DenzaLocalAdb.client(context).shell(SpeakerCoverProtocol.reportPlayingCommand())
        check(SpeakerCoverProtocol.accepted(output)) {
            output.trim().ifBlank { "the car acknowledged nothing" }
        }
        return true
    }
}
