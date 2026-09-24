package dev.denza.apps.feature.split

/**
 * The BYD split transactions this process sends itself, in front of the shell and the resident
 * helper (findings, "No permission guards the BYD split family").
 *
 * Every command of a recipe already goes through one funnel ([SplitOperationWorkspace.shell]), and
 * this sits at its front the way the resident helper sits behind it: a command it recognises
 * letter for letter is answered here, in the exact words `service call` would have printed, and
 * anything else - or any failure at all - goes on down the funnel as before. No recipe knows it
 * exists, no parser and no postcondition changes.
 *
 * What it serves is what the firmware lets an app UID do on the `activity_task` binder (proven on
 * the car 2026-09-23): the area (tx30), the root of an area (tx118), whether a package is
 * split-capable (tx112), the runtime split list (tx125) and the gate (tx126). The reads come back
 * in under a millisecond instead of a round trip, and none of the five needs ADB to be alive.
 * The world read (`am stack list`) and every task move stay where they are: the firmware guards
 * those with permissions an app does not hold.
 */
internal fun interface SplitInProcessCalls {
    /** @return what `service call` would have printed for [command], or `null` when not served. */
    fun answer(command: String): String?

    companion object {
        /** Nothing is served here; every command goes on down the funnel. */
        val NONE = SplitInProcessCalls { null }
    }
}

/** One argument of a binder call, as `service call` spells it. */
internal sealed interface SplitBinderArgument {
    data class Int32(val value: Int) : SplitBinderArgument

    data class Utf16(val value: String) : SplitBinderArgument
}

/**
 * One `service call activity_task` command this process may send itself, parsed exactly.
 *
 * Deny by default, the same discipline as [SplitResidentRequest]: only the five codes above, only
 * with the argument shapes the recipes send, and the argument words split by the one quoting rule
 * the recipes use ([SplitTaskProxyMain.splitArguments]).
 */
internal data class SplitBinderCall(
    val code: Int,
    val arguments: List<SplitBinderArgument>,
    val repliesInt: Boolean,
) {
    companion object {
        fun of(command: String): SplitBinderCall? {
            if (!command.startsWith(PREFIX)) return null
            val words = runCatching { SplitTaskProxyMain.splitArguments(command.removePrefix(PREFIX)) }
                .getOrNull() ?: return null
            val code = words.firstOrNull()?.toIntOrNull() ?: return null
            val tail = words.drop(1)
            return when (code) {
                AREA -> if (tail.isEmpty()) SplitBinderCall(code, emptyList(), repliesInt = true) else null
                ROOT_BY_AREA -> int32(tail)?.let { SplitBinderCall(code, listOf(it), repliesInt = true) }
                SUPPORTS_SPLIT -> utf16(tail)?.let { SplitBinderCall(code, listOf(it), repliesInt = true) }
                PERSISTENT_APP -> utf16(tail)?.let { SplitBinderCall(code, listOf(it), repliesInt = false) }
                GATE -> int32(tail)
                    ?.takeIf { it.value == 0 || it.value == 1 }
                    ?.let { SplitBinderCall(code, listOf(it), repliesInt = false) }
                else -> null
            }
        }

        private fun int32(tail: List<String>): SplitBinderArgument.Int32? {
            if (tail.size != 2 || tail[0] != "i32") return null
            return tail[1].toIntOrNull()?.let(SplitBinderArgument::Int32)
        }

        private fun utf16(tail: List<String>): SplitBinderArgument.Utf16? {
            if (tail.size != 2 || tail[0] != "s16" || tail[1].isEmpty()) return null
            return SplitBinderArgument.Utf16(tail[1])
        }

        private const val PREFIX = "service call activity_task "
        const val AREA = 30
        const val SUPPORTS_SPLIT = 112
        const val ROOT_BY_AREA = 118
        const val PERSISTENT_APP = 125
        const val GATE = 126
    }
}

/** The `activity_task` binder of this process; throws when a call did not go through. */
internal interface SplitBinderTransport {
    fun callInt(code: Int, arguments: List<SplitBinderArgument>): Int

    fun callVoid(code: Int, arguments: List<SplitBinderArgument>)
}

/**
 * Whether the BYD transactions this process sends itself go through: the last one's result, or
 * `null` before the first.
 *
 * A failed call costs nothing a user can see - the command goes on down the funnel to the shell -
 * which is exactly why it has to be written down somewhere. The five codes were proven on one
 * firmware; on another the binder may refuse them all, every open then pays the shell's round trips
 * instead, and the service's technical page is the only place that can say so from a photo.
 */
internal class SplitInProcessHealth {
    @Volatile
    var lastCallOk: Boolean? = null
        private set

    fun record(ok: Boolean) {
        lastCallOk = ok
    }

    companion object {
        /** The process's own: what the product's coordinator sends, and what the page reads. */
        val process = SplitInProcessHealth()
    }
}

/** [SplitInProcessCalls] over a [SplitBinderTransport], answering in `service call`'s own words. */
internal class SplitInProcessFirmware(
    private val transport: SplitBinderTransport,
    private val health: SplitInProcessHealth = SplitInProcessHealth.process,
) : SplitInProcessCalls {
    override fun answer(command: String): String? {
        val call = SplitBinderCall.of(command) ?: return null
        val reply = runCatching {
            if (call.repliesInt) {
                SplitTaskProxyMain.parcelInt(transport.callInt(call.code, call.arguments))
            } else {
                transport.callVoid(call.code, call.arguments)
                VOID_REPLY
            }
        }
        // Only a call this process actually sent: a command it does not recognise says nothing
        // about the binder.
        health.record(reply.isSuccess)
        return reply.getOrNull()
    }

    private companion object {
        /** What `service call` prints for a reply that carries no value. */
        const val VOID_REPLY = "Result: Parcel(00000000 '....')"
    }
}
