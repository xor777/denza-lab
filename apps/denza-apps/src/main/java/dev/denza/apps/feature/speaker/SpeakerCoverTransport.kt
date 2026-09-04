package dev.denza.apps.feature.speaker

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb

/**
 * Tell the car once. One shell command, about a second, is the whole trip.
 *
 * Nothing is read first and nothing is decided here: the policy has already said yes by the time
 * this is called. The earlier shape read the amplifier's auto-lift setting and wrote it back on
 * when it was off, which made the app a second hand on the car's own switch.
 */
internal object SpeakerCoverTransport {

    fun report(context: Context) {
        val output = DenzaLocalAdb.client(context).shell(SpeakerCoverProtocol.reportPlayingCommand())
        check(SpeakerCoverProtocol.accepted(output)) {
            output.trim().ifBlank { "the car acknowledged nothing" }
        }
    }
}
