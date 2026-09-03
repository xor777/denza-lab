package dev.denza.apps.feature.speaker

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb

/**
 * One trip to the car: read the amplifier's setting, ask the policy, say what it decided.
 *
 * The read is not an optimisation. On the Z9GT the enable write also drives the motor, so sending
 * it unconditionally would twitch covers that are already out; on both cars a report without the
 * setting on is a write that changes nothing. Reading first is what lets one code path be correct
 * on two different amplifiers.
 *
 * Everything happens inside a single persistent shell session because opening one is the expensive
 * part - about a second - and a raise is at most two writes after the read.
 */
internal object SpeakerCoverTransport {

    data class Outcome(
        val steps: List<SpeakerCoverStep>,
        val autoLift: SpeakerCoverAutoLift,
    )

    fun run(
        context: Context,
        trigger: SpeakerCoverTrigger,
        featureEnabled: Boolean,
    ): Outcome {
        val session = DenzaLocalAdb.client(context).openPersistentShell()
        return try {
            val autoLift = SpeakerCoverProtocol.autoLift(
                session.shell(SpeakerCoverProtocol.readAutoLiftCommand()),
            )
            val steps = SpeakerCoverPolicy.steps(trigger, featureEnabled, autoLift)
            steps.forEach { step ->
                val output = session.shell(SpeakerCoverProtocol.command(step))
                check(SpeakerCoverProtocol.accepted(output)) {
                    output.trim().ifBlank { "the car acknowledged nothing for $step" }
                }
            }
            Outcome(steps, autoLift)
        } finally {
            session.close()
        }
    }
}
