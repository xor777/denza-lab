package dev.denza.apps.feature.speaker

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb

internal object SpeakerCoverMotorProtocol {
    private val PARCEL = Regex(
        """Parcel\(([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})""",
    )

    fun command(value: Int): String {
        require(value == OPEN || value == CLOSE) { "unsupported cover value" }
        return "service call autoservice 6 i32 1002 i32 372244517 i32 $value null"
    }

    fun accepted(output: String): Boolean = PARCEL.find(output)
        ?.groupValues
        ?.get(2)
        ?.toLongOrNull(16)
        ?.toInt() == 1

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
