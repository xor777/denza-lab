package dev.denza.apps.feature.speaker

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb

internal object SpeakerCoverMotorProtocol {
    /**
     * The words inside a `service call` reply parcel, however many it printed.
     *
     * This used to require exactly two and read the second, which is the shape a reply takes when
     * it carries an exception code and then a value. This car answers the cover command with one:
     *
     *     Result: Parcel(00000001    '....')
     *
     * So nothing matched, every successful command was read as a refusal, and the feature sat in
     * "Повторю команду автоматически" turning a spinner on the dashboard while the covers did
     * exactly what they were told. The status is the last word either way.
     */
    private val PARCEL = Regex("""Parcel\(([^')]*)""")
    private val WORD = Regex("""[0-9a-fA-F]{8}""")

    fun command(value: Int): String {
        require(value == OPEN || value == CLOSE) { "unsupported cover value" }
        return "service call autoservice 6 i32 1002 i32 372244517 i32 $value null"
    }

    fun accepted(output: String): Boolean {
        val body = PARCEL.find(output)?.groupValues?.get(1) ?: return false
        val last = WORD.findAll(body).lastOrNull()?.value ?: return false
        return last.toLongOrNull(16)?.toInt() == 1
    }

    fun opposite(value: Int): Int = if (value == OPEN) CLOSE else OPEN

    /**
     * Whether writing [target] on its own would move nothing.
     *
     * The property is edge-triggered, so only a *change* of value reaches the motor. A car whose
     * last written value is already [target] needs the opposite sent first to make one. We are the
     * only writer, so [lastWritten] is knowable - except before our first ever command, where the
     * firmware's value could be either and the break has to be paid once.
     */
    fun needsEdgeBreak(lastWritten: Int?, target: Int): Boolean = lastWritten != opposite(target)

    const val OPEN = 1
    const val CLOSE = 2
}

internal object SpeakerCoverMotor {
    /**
     * Send the covers where they are wanted, in one visible movement wherever that is possible.
     *
     * This used to answer the edge-triggered property by always sending in-then-out whenever it did
     * not know where the covers were - two commands 350 ms apart, which from the driver's seat is
     * the covers twitching. That is what the car showed: дёрг-дёрг on every switch-on.
     *
     * The mistake was treating one unknown as two. Where the covers *are* is the automaton's
     * business and can change behind our back, because the amplifier lowers them on its own. What
     * the property last *saw* is ours alone - we are its only writer - so it can simply be
     * remembered. Knowing it, the break is needed only when the value we want is the value already
     * there; and in that case the opposite command asks a motor that is already at that end to go
     * there again, which moves nothing. Either way the driver sees one movement.
     */
    fun execute(
        context: Context,
        action: SpeakerCoverMotorAction,
        pause: (Long) -> Unit = Thread::sleep,
    ): String {
        val target = when (action) {
            SpeakerCoverMotorAction.OPEN -> SpeakerCoverMotorProtocol.OPEN
            SpeakerCoverMotorAction.CLOSE -> SpeakerCoverMotorProtocol.CLOSE
        }
        val session = DenzaLocalAdb.client(context).openPersistentShell()
        return try {
            val lastWritten = SpeakerCoverSettings.lastCommandValue(context)
            val prelude = if (SpeakerCoverMotorProtocol.needsEdgeBreak(lastWritten, target)) {
                val broken = call(context, session, SpeakerCoverMotorProtocol.opposite(target))
                pause(EDGE_BREAK_PAUSE_MS)
                "$broken\n"
            } else {
                ""
            }
            prelude + call(context, session, target)
        } finally {
            session.close()
        }
    }

    private fun call(
        context: Context,
        session: dev.denza.disharebridge.LocalAdbClient.PersistentShellSession,
        value: Int,
    ): String {
        val output = session.shell(SpeakerCoverMotorProtocol.command(value))
        check(SpeakerCoverMotorProtocol.accepted(output)) {
            output.trim().ifBlank { "autoservice returned no cover acknowledgement" }
        }
        // Written before the pair is finished on purpose: a process killed between the two halves
        // of a break leaves a true record of what the property saw, not a hopeful one.
        SpeakerCoverSettings.rememberCommandValue(context, value)
        return output
    }

    /** The only knob left if a break ever does become visible on a car. */
    private const val EDGE_BREAK_PAUSE_MS = 350L
}
