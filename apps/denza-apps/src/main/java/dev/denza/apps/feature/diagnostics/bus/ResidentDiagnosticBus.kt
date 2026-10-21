package dev.denza.apps.feature.diagnostics.bus

import android.content.Context
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.ShellProxyJar
import dev.denza.apps.platform.shell.ShellProxyStager
import dev.denza.apps.platform.shell.shellQuote
import dev.denza.disharebridge.LocalAdbClient
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One helper's stream: started once, then one line at a time. */
internal interface DiagnosticBusLink : AutoCloseable {
    /** Runs [command] and returns once the helper said READY. */
    fun start(command: String, readyTimeoutMs: Int)

    /** The answer between `BEGIN` and `END ok`; [HelperRefusal] for `END err`, another [IOException] when the stream is gone. */
    fun request(line: String, readTimeoutMs: Int): String

    /**
     * The lines the helper pushed between answers since the last call, `PUSH ` prefix off; waits
     * up to [waitMs] when none is there and returns none if none comes. [IOException] when the
     * stream is gone. A link that cannot carry them says so as a lost stream.
     */
    fun pushed(waitMs: Int): List<String> = throw IOException("this link carries no pushed lines")

    override fun close()
}

/** [DiagnosticBusLink] over the ADB stream of [LocalAdbClient.ResidentSession]. */
internal class ResidentSessionLink(private val session: LocalAdbClient.ResidentSession) : DiagnosticBusLink {
    override fun start(command: String, readyTimeoutMs: Int) = session.start(command, readyTimeoutMs)

    override fun request(line: String, readTimeoutMs: Int): String = try {
        session.request(line, readTimeoutMs)
    } catch (error: IOException) {
        throw DiagnosticBusLines.refusal(error) ?: error
    }

    override fun pushed(waitMs: Int): List<String> = session.pushed(waitMs)

    override fun close() = session.close()
}

/**
 * The diagnostic channel through one resident shell-UID helper, [DiagnosticBusMain]
 * (docs/vehicle-diagnostics-architecture.md, 3.2 and 3.5).
 *
 * The helper is opened at the first exchange, not with the section: opening it costs two seconds
 * of listening and takes the shared reader lock, and a section that only shows its last scan
 * should cost neither. One exchange at a time, by [mutex]; the helper would refuse a second line
 * anyway, and the order of the queue is the engine's to decide.
 *
 * Nothing is retried in a loop. When the link is lost - the helper went idle, the stream dropped -
 * the exchange opens a new helper once and sends the request again; every request is a read, so
 * sending one twice changes nothing on the car. A second loss, a busy channel, a car that is not
 * parked: those reach the caller, and the next exchange is the caller's decision. Any `END err`
 * closes the stream ([LocalAdbClient.ResidentSession.request] closes on every failure), so the
 * exchange after it starts a new helper.
 *
 * The gap between exchanges starts as [gapMs], the launch parameter of every helper. [setGap] asks
 * for another from the next exchange on: before that exchange the link is sent `g <ms>` whenever the
 * helper's gap is not the one asked for - so a helper opened after a loss is told again - and the
 * answer must be the same `g <ms>`.
 *
 * A cancelled exchange closes its link at once: the socket's close is what interrupts a blocked
 * read, and the helper ends with its stream. [close] does the same from any thread.
 *
 * [batteryPages] (architecture 6.3) asks the helper for `s gb` and then reads its pushed lines,
 * [PUSH_WAIT_MS] at most at a time, under the same [mutex] as the exchanges - so an exchange waits
 * for one short read at most, and the pages never wait for more than one exchange. The pages are
 * the helper's: a lost link ends them, and a new helper opened by an exchange after a loss is not
 * subscribed, so the flow ends with [DiagFailure.ChannelLost] and asking again is the caller's
 * decision. Reading the pages counts as using the helper, which keeps its idle clock stopped
 * while subscribed; leaving sends `u gb`.
 */
internal class ResidentDiagnosticBus(
    private val links: (nonce: String) -> DiagnosticBusLink,
    private val stage: () -> String,
    private val gapMs: Int = DEFAULT_GAP_MS,
    private val nonce: () -> String = ::randomNonce,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val blocking: Executor = Dispatchers.IO.asExecutor(),
    private val log: (String) -> Unit = {},
) : DiagnosticBus {
    private val mutex = Mutex()
    @Volatile private var link: DiagnosticBusLink? = null
    @Volatile private var closed = false
    private var lastUsed = 0L
    /** The gap the caller asked for, and the one the open helper holds. */
    @Volatile private var wantedGap = gapMs
    private var linkGap = gapMs
    private var sequence = 0

    init {
        require(gapMs in 0..MAX_GAP_MS) { "gap outside 0..$MAX_GAP_MS ms" }
    }

    override suspend fun exchange(request: DiagRequest): DiagReply = mutex.withLock {
        try {
            attempt(request)
        } catch (lost: DiagFailure.ChannelLost) {
            if (closed) throw lost
            log("diagnostic helper lost (${lost.cause?.message ?: lost.message}), opening it once more")
            attempt(request)
        }
    }

    override fun setGap(ms: Int) {
        require(ms in 0..MAX_GAP_MS) { "gap outside 0..$MAX_GAP_MS ms" }
        wantedGap = ms
    }

    override fun close() {
        closed = true
        link?.close()
        link = null
    }

    override fun batteryPages(): Flow<BmsPage> = flow {
        val served = mutex.withLock {
            try {
                subscribe()
            } catch (lost: DiagFailure.ChannelLost) {
                if (closed) throw lost
                log("diagnostic helper lost (${lost.cause?.message ?: lost.message}), opening it once more for the pages")
                subscribe()
            }
        }
        try {
            while (true) {
                val lines = mutex.withLock { drain(served) }
                for (line in lines) {
                    when (val pushed = DiagnosticBusLines.pushed(line)) {
                        is DiagnosticBusLines.Pushed.Page -> emit(pushed.page)
                        is DiagnosticBusLines.Pushed.End -> {
                            mutex.withLock { drop(served) }
                            throw pushed.failure
                        }
                        null -> log("diagnostic helper pushed a line that is not a page: ${line.take(80)}")
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { leave(served) } }
        }
    }

    /** `s gb` on the open helper, or a new one. Under [mutex]. */
    private suspend fun subscribe(): DiagnosticBusLink {
        if (closed) throw DiagFailure.ChannelLost(IllegalStateException("the diagnostic bus is closed"))
        val live = reusable() ?: open()
        val answer = call(live) { live.request(DiagnosticBusLines.SUBSCRIBE, RESPONSE_GRACE_MS) }
        try {
            DiagnosticBusLines.pagesTook(DiagnosticBusLines.SUBSCRIBE, answer)
        } catch (malformed: DiagFailure) {
            drop(live)
            throw malformed
        }
        lastUsed = clock()
        log("diagnostic helper subscribed to the BMS pages")
        return live
    }

    /** The pushed lines of [served]; it must still be the open link. Under [mutex]. */
    private suspend fun drain(served: DiagnosticBusLink): List<String> {
        if (closed || link !== served) {
            throw DiagFailure.ChannelLost(IllegalStateException("the helper serving the pages is gone"))
        }
        val lines = call(served) { served.pushed(PUSH_WAIT_MS) }
        lastUsed = clock()
        return lines
    }

    /** `u gb`, when [served] is still the open link; a failure only closes it. Under [mutex]. */
    private suspend fun leave(served: DiagnosticBusLink) {
        if (closed || link !== served) return
        try {
            val answer = call(served) { served.request(DiagnosticBusLines.LEAVE, RESPONSE_GRACE_MS) }
            DiagnosticBusLines.pagesTook(DiagnosticBusLines.LEAVE, answer)
            lastUsed = clock()
            log("diagnostic helper left the BMS pages")
        } catch (failure: DiagFailure) {
            drop(served)
            log("diagnostic helper did not leave the pages cleanly: ${failure.message}")
        }
    }

    private suspend fun attempt(request: DiagRequest): DiagReply {
        if (closed) throw DiagFailure.ChannelLost(IllegalStateException("the diagnostic bus is closed"))
        val live = reusable() ?: open()
        val gap = wantedGap
        if (linkGap != gap) {
            val answer = call(live) { live.request(DiagnosticBusLines.gap(gap), RESPONSE_GRACE_MS) }
            try {
                DiagnosticBusLines.gapTook(gap, answer)
            } catch (malformed: DiagFailure) {
                drop(live)
                throw malformed
            }
            linkGap = gap
        }
        val seq = sequence++
        val payload = call(live) {
            live.request(DiagnosticBusLines.request(seq, request), request.timeoutMs + linkGap + RESPONSE_GRACE_MS)
        }
        lastUsed = clock()
        return try {
            DiagnosticBusLines.reply(request, seq, payload, wallClock())
        } catch (malformed: DiagFailure) {
            drop(live)
            throw malformed
        }
    }

    /** The open link, unless the helper may have left on its own idle clock. */
    private fun reusable(): DiagnosticBusLink? {
        val current = link ?: return null
        if (clock() - lastUsed < IDLE_REUSE_MS) return current
        log("diagnostic helper idle, starting a new one")
        drop(current)
        return null
    }

    private suspend fun open(): DiagnosticBusLink {
        val id = nonce()
        val opened = links(id)
        link = opened
        linkGap = gapMs
        call(opened) { opened.start(launchCommand(stage(), id, gapMs), READY_TIMEOUT_MS) }
        if (closed) {
            drop(opened)
            throw DiagFailure.ChannelLost(IllegalStateException("the diagnostic bus was closed while opening"))
        }
        lastUsed = clock()
        log("diagnostic helper ready")
        return opened
    }

    /** Runs [block] off the caller's thread; a failure of any kind leaves [owner] closed. */
    private suspend fun <T> call(owner: DiagnosticBusLink, block: () -> T): T = try {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { owner.close() }
            blocking.execute { continuation.resumeWith(runCatching(block)) }
        }
    } catch (cancelled: CancellationException) {
        drop(owner)
        throw cancelled
    } catch (refusal: HelperRefusal) {
        drop(owner)
        throw DiagnosticBusLines.failure(refusal)
    } catch (failure: DiagFailure) {
        drop(owner)
        throw failure
    } catch (error: Exception) {
        drop(owner)
        throw if (generateSequence<Throwable>(error) { it.cause }.any { it is LocalAdbClient.AuthorizationRequiredException }) {
            DiagFailure.NoAccess(error)
        } else {
            DiagFailure.ChannelLost(error)
        }
    }

    private fun drop(owner: DiagnosticBusLink) {
        if (link === owner) link = null
        owner.close()
    }

    internal companion object {
        const val DEFAULT_GAP_MS = 100
        const val MAX_GAP_MS = 500
        /** Two seconds of silence and an `app_process` start, with room. */
        const val READY_TIMEOUT_MS = 10_000
        /** The helper's gap, its checks over binder and the stream, beyond the request's own timeout. */
        const val RESPONSE_GRACE_MS = 3_000
        /** The helper leaves after 60 s without a request; the app stops trusting it ten seconds sooner. */
        const val IDLE_REUSE_MS = 50_000L
        /** The longest one read of the pages holds the link: what an exchange may wait for it. */
        const val PUSH_WAIT_MS = 100

        fun launchCommand(entry: String, nonce: String, gapMs: Int): String =
            "CLASSPATH=${shellQuote(entry)} exec app_process /system/bin --nice-name=denza_diag_bus " +
                "${DiagnosticBusMain::class.java.name} serve $nonce $gapMs"

        /** The bus of the main process, on the app's own local ADB and the packed helper jar. */
        fun create(context: Context, gapMs: Int = DEFAULT_GAP_MS): ResidentDiagnosticBus {
            val app = context.applicationContext
            val client = DenzaLocalAdb.client(app)
            // A helper that cannot be staged stays off; it is never loaded from the whole APK.
            val stager = ShellProxyStager(
                helper = ShellProxyJar.DIAGNOSTIC_BUS,
                jar = { app.assets.open(ShellProxyJar.DIAGNOSTIC_BUS.asset).use { it.readBytes() } },
                log = { Log.i(TAG, it) },
            )
            return ResidentDiagnosticBus(
                links = { nonce -> ResidentSessionLink(client.openResidentSession(nonce)) },
                stage = { client.openPersistentShell().use { shell -> stager.stage(shell::shell) } },
                gapMs = gapMs,
                log = { Log.i(TAG, it) },
            )
        }

        private const val TAG = "DenzaDiagnosticBus"
        private val random = SecureRandom()

        private fun randomNonce(): String =
            ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
