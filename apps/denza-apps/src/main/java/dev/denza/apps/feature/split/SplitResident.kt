package dev.denza.apps.feature.split

/**
 * The long-lived shell-UID helper, and the rule for what it is allowed to answer.
 *
 * Every command of a recipe already goes through one funnel ([SplitOperationWorkspace.shell]).
 * The helper sits behind that funnel and nowhere else: a command it may serve is answered in the
 * shell's own words, everything else is sent exactly as before, and any failure at all - the
 * helper not starting, dying in the car's sleep, answering something unexpected, answering late -
 * ends in the command being sent. No recipe knows it exists, no postcondition changes, no parser
 * changes, and the product never has anything to say to the user about it (U5).
 *
 * What it buys is measured on this vehicle: one `app_process` start is 506-681 ms and a recipe
 * that clears tasks pays it every time, while the same removal inside a helper that is already up
 * is a binder call. A world read is 18.7 ms of `am stack list` against 3-9 ms of work.
 */

/** One request the helper may serve, and the command it stands in for. */
internal class SplitResidentRequest private constructor(
    val line: String,
    /**
     * Whether this request is worth starting a helper that is not up yet.
     *
     * Starting one costs about 0.4 s, so only a command that costs more than that on the shell may
     * pay for it: that is the `app_process` one-shot and nothing else. A read never starts a
     * helper - it uses one that a removal already stood up, or it is simply sent.
     */
    val worthStarting: Boolean,
) {
    companion object {
        /**
         * @return the request that stands in for [command], or `null` when nothing may.
         *
         * Deny by default and matched exactly, the same discipline the topology cache uses: a
         * command this does not recognise letter for letter is sent, and a command it does
         * recognise means precisely what it meant on the shell. The helper splits a request line
         * by the one quoting rule a POSIX shell has, which is the exact inverse of how the recipes
         * quote their arguments, so a package or an activity name with any character in it
         * arrives as the same argv the one-shot command line would have built.
         */
        fun of(command: String): SplitResidentRequest? {
            if (command == WORLD_COMMAND) return SplitResidentRequest("world", false)
            if (command.startsWith(TRANSACTION_PREFIX)) {
                val call = command.removePrefix(TRANSACTION_PREFIX)
                val code = call.substringBefore(' ')
                if (code in READ_TRANSACTIONS) return SplitResidentRequest("call-int $call", false)
                return null
            }
            if (command.startsWith(CLASSPATH_PREFIX) && REMOVE_MARKER in command) {
                val tail = command.substringAfter(REMOVE_MARKER)
                if (tail.isNotBlank()) return SplitResidentRequest("remove-task $tail", true)
            }
            return null
        }

        private const val WORLD_COMMAND = "am stack list"
        private const val TRANSACTION_PREFIX = "service call activity_task "
        private const val CLASSPATH_PREFIX = "CLASSPATH="
        private val REMOVE_MARKER =
            "--nice-name=denza_split_cmd ${SplitTaskProxyMain::class.java.name} remove-task "

        /**
         * The transactions that only look: the split area, is-this-package-splittable, and the
         * root of an area. The two that write - 125 extends the firmware allowlist, 126 moves the
         * gate - are deliberately absent. They are a handful of calls per operation, they change
         * state that outlives the session (contract 1.12), and the whole worth of this helper is
         * that it may be wrong without anything being lost.
         */
        private val READ_TRANSACTIONS = setOf("30", "112", "118")
    }
}

/** What one attempt to have the helper answer ended as. */
internal sealed interface SplitResidentAnswer {
    /** Nothing was sent: there is no helper, or this command is not one it may serve. */
    object NotServed : SplitResidentAnswer

    /** The helper answered, in the words the command it replaced would have used. */
    class Served(val output: String) : SplitResidentAnswer

    /** Something was sent to the car and it did not work out. The command still has to be run. */
    object Failed : SplitResidentAnswer
}

/** One channel to one helper: it is started once, asked many times, and closed by its owner. */
internal interface SplitResidentChannel : AutoCloseable {
    /**
     * Runs the command [launch] builds for this channel's own nonce, and waits for the helper.
     *
     * The nonce belongs to the channel because the channel is what has to recognise the answers:
     * every one of them is wrapped in it, so a late answer, or half of one, can never be read as
     * the reply to a later request.
     */
    fun start(launch: (nonce: String) -> String)

    fun request(line: String): String

    override fun close()
}

/**
 * The single helper of the process, and the policy that keeps it from ever being a problem.
 *
 * - It is started lazily, and only by a command that costs more on the shell than starting it.
 * - A car that will not run it says so once: after [MAX_STARTS] failed starts nothing is tried
 *   again for the life of the process, so a broken car does not pay for the attempt over and over.
 * - A helper that stops answering - which is what the car's sleep leaves behind - is dropped, and
 *   the very next expensive command may stand a new one up.
 * - It is closed by its owner: the toggle going off and the coordinator shutting down both end it,
 *   and closing the channel closes the ADB stream, which is what kills the process on the car.
 */
internal class SplitResidentProxy(
    private val open: () -> SplitResidentChannel,
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    private val lock = Any()
    private var channel: SplitResidentChannel? = null
    private var starts = 0
    private var refused = false

    fun answer(
        request: SplitResidentRequest,
        launch: (nonce: String) -> String,
    ): SplitResidentAnswer = synchronized(lock) {
        val live = channel ?: return start(request, launch)
        runCatching { SplitResidentAnswer.Served(live.request(request.line)) as SplitResidentAnswer }
            .getOrElse { error ->
                // The usual reason is that the car slept and took the helper with it. The command
                // this stood in for is sent for real right after, so there is nothing to report.
                drop()
                log("split helper stopped answering: ${error.message ?: error}")
                SplitResidentAnswer.Failed
            }
    }

    /** Ф4: the helper lives exactly as long as its owner lets it, and not one moment longer. */
    override fun close() = synchronized(lock) { drop() }

    /**
     * Ф4: lets go of a helper that nothing has needed for a while.
     *
     * Measured on this car, the helper is about 60 MB of PSS, which is too much to leave standing
     * beside a split the user is simply looking at. Its owner arms this after every operation and
     * disarms it when the next one starts, so "unused" here means no operation has run at all in
     * that window. Standing a new one up costs about 0.5 s, once, on the next command that is
     * worth it - which is the same price the one-shot it replaces charges every single time.
     */
    fun releaseIfUnused() = synchronized(lock) {
        if (channel == null) return@synchronized
        log("split helper released after idling")
        drop()
    }

    /**
     * Stands a helper up, if this request is one that may pay for it.
     *
     * A start that failed is [SplitResidentAnswer.Failed] rather than [SplitResidentAnswer.NotServed]
     * because the car really was spoken to and really did cost the operation time: the budget line
     * has to say so.
     */
    private fun start(
        request: SplitResidentRequest,
        launch: (nonce: String) -> String,
    ): SplitResidentAnswer {
        if (refused || !request.worthStarting) return SplitResidentAnswer.NotServed
        starts += 1
        val fresh = runCatching {
            open().also { opened ->
                runCatching { opened.start(launch) }.onFailure { error ->
                    runCatching(opened::close)
                    throw error
                }
            }
        }.getOrElse { error ->
            if (starts >= MAX_STARTS) refused = true
            log("split helper did not start: ${error.message ?: error}")
            return SplitResidentAnswer.Failed
        }
        channel = fresh
        log("split helper is up")
        return runCatching { SplitResidentAnswer.Served(fresh.request(request.line)) as SplitResidentAnswer }
            .getOrElse { error ->
                drop()
                log("split helper answered nothing: ${error.message ?: error}")
                SplitResidentAnswer.Failed
            }
    }

    private fun drop() {
        channel?.let { live -> runCatching(live::close) }
        channel = null
    }

    private companion object {
        /**
         * After this many, the car has answered. Three rather than one because the first failure
         * is as likely to be the car asleep as the car unable.
         */
        const val MAX_STARTS = 3
    }
}
