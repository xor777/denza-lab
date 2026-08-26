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

    const val OPEN = 1
    const val CLOSE = 2
}

internal object SpeakerCoverMotor {
    fun execute(
        context: Context,
        action: SpeakerCoverMotorAction,
        pause: (Long) -> Unit = Thread::sleep,
    ): String {
        val session = DenzaLocalAdb.client(context).openPersistentShell()
        return try {
            when (action) {
                SpeakerCoverMotorAction.OPEN -> call(session, SpeakerCoverMotorProtocol.OPEN)
                SpeakerCoverMotorAction.CLOSE -> call(session, SpeakerCoverMotorProtocol.CLOSE)
                SpeakerCoverMotorAction.ESTABLISH_OPEN -> {
                    val closed = call(session, SpeakerCoverMotorProtocol.CLOSE)
                    pause(ESTABLISH_EDGE_PAUSE_MS)
                    val opened = call(session, SpeakerCoverMotorProtocol.OPEN)
                    "$closed\n$opened"
                }
            }
        } finally {
            session.close()
        }
    }

    private fun call(
        session: dev.denza.disharebridge.LocalAdbClient.PersistentShellSession,
        value: Int,
    ): String {
        val output = session.shell(SpeakerCoverMotorProtocol.command(value))
        check(SpeakerCoverMotorProtocol.accepted(output)) {
            output.trim().ifBlank { "autoservice returned no cover acknowledgement" }
        }
        return output
    }

    private const val ESTABLISH_EDGE_PAUSE_MS = 350L
}
