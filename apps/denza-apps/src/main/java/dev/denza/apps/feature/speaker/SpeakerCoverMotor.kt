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
     * Whether the opposite value has to be sent first to make an edge at all.
     *
     * The property is edge-triggered, so a write only reaches the motor if it changes the value,
     * and the pair is the only way to guarantee one. It costs a close and an open 350 ms apart:
     * invisible on covers that are in, a visible twitch on covers that are out, and nothing on this
     * car can tell those two apart. So the question is never just "might a single write be a
     * no-op" - it is [SpeakerCoverCommandSource]'s question, how much visible movement this
     * particular asker is entitled to spend, and the answer is one line per source.
     *
     * [SpeakerCoverCommandSource.MANUAL] spends it in both cases the property allows: nothing
     * remembered, and the button asking for exactly the value already held. The second is the
     * driver saying the covers are not where the app thinks they are, which is the only way that
     * can ever be said - they are looking at the covers and pressed anyway. A button that sometimes
     * does nothing is a worse answer than one that sometimes twitches, and «Поднять» silently
     * dropped is the fault that made this rule take an asker at all.
     *
     * [SpeakerCoverCommandSource.AUTOMATIC] spends it only on an unknown property, where it is
     * nearly free and also required. Free because nothing is remembered at the start of a trip,
     * when the amplifier has just retracted the covers - the pair's close moves nothing and only
     * the open is seen. Required because the one opening of a trip is a promise, and against a
     * property that could be holding either value, only a pair guarantees the edge that keeps it.
     *
     * [SpeakerCoverCommandSource.BEST_EFFORT] never spends it, which is the whole of what best
     * effort means here: worth an open, not worth a twitch. On an unknown property its single write
     * either makes the edge and the covers rise, or lands on the value already held and does
     * nothing - and both are endings a parting gesture can live with. This line is missing from the
     * version that shipped: the rule took a two-way `manual` flag, so the toggle-off's parting open
     * fell into the automation's branch, and once `rearm` began clearing `last_command_value` there
     * was a `null` waiting for it to fall in with. Off went the toggle, out-in went the covers.
     *
     * Never otherwise: a value that differs from the last one written makes its own edge.
     */
    fun needsEdgeBreak(
        lastWritten: Int?,
        target: Int,
        source: SpeakerCoverCommandSource,
    ): Boolean = when (source) {
        SpeakerCoverCommandSource.MANUAL -> lastWritten == null || lastWritten == target
        SpeakerCoverCommandSource.AUTOMATIC -> lastWritten == null
        SpeakerCoverCommandSource.BEST_EFFORT -> false
    }

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
     * The mistake was treating one unknown as two. Where the covers *are* is unknowable and not
     * worth pretending about. What the property last *saw* is ours alone - we are its only writer -
     * so it can simply be remembered, for as long as the firmware holding it has been up. Knowing
     * it, the pair is needed exactly once per boot for the automation, and never again: every later
     * command differs from the last one written, so a single write makes its own edge.
     *
     * A button is the exception, and it is the request that carries the fact - the driver asking
     * for a position the property is already holding is the one case where nothing but a forced
     * edge can move anything. Which asker is entitled to what is
     * [SpeakerCoverMotorProtocol.needsEdgeBreak]'s to say; all that happens here is that the whole
     * source travels there, rather than the one bit of it the rule used to be asked in terms of.
     */
    fun execute(
        context: Context,
        request: SpeakerCoverMotorRequest,
        pause: (Long) -> Unit = Thread::sleep,
    ): String {
        val target = when (request.action) {
            SpeakerCoverMotorAction.OPEN -> SpeakerCoverMotorProtocol.OPEN
            SpeakerCoverMotorAction.CLOSE -> SpeakerCoverMotorProtocol.CLOSE
        }
        val session = DenzaLocalAdb.client(context).openPersistentShell()
        return try {
            val lastWritten = SpeakerCoverSettings.lastCommandValue(context)
            val needsBreak = SpeakerCoverMotorProtocol.needsEdgeBreak(
                lastWritten = lastWritten,
                target = target,
                source = request.source,
            )
            val prelude = if (needsBreak) {
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
