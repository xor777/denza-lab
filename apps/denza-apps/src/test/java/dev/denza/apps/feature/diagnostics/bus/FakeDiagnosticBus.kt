package dev.denza.apps.feature.diagnostics.bus

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [DiagnosticBus] that answers from a script, for the engine and the screens.
 *
 * Script a request by its address and UDS in hex - `on(0x7E0, "190109").answer("5901FF000000")` -
 * with the bytes the research runs recorded (the JSON files in `research/vehicle-diagnostics/`). Steps of one
 * request are used in order and the last one repeats, so `answer(...).answer(...)` is a value that
 * changes once and a single `answer` is a stream that never does. A request nobody scripted gets
 * what an absent address gets on the car: the gateway's 3.
 *
 * A session failure is a step too ([Script.fail]), or [failNext] for whatever the next request is.
 * Every exchange is logged with what came of it; [requests] is the short form for asserting order.
 *
 * Timing is a fake car clock: each reply's [DiagReply.receivedNanos] is the previous one plus the
 * [gapMs] and the step's latency, so a stream chart built on it has the gaps it would have on the
 * car. [delayMs] makes each exchange take virtual time under `runTest`.
 *
 * [setGap] is the helper's `g <ms>`: it is recorded in [gaps] and becomes [gapMs], so the fake car
 * clock runs at the gap the engine asked for from the next exchange on.
 */
internal class FakeDiagnosticBus(
    var gapMs: Int = 100,
    var latencyMs: Int = 30,
    var delayMs: Long = 0,
    private val wallClock: () -> Long = { 0L },
) : DiagnosticBus {
    /** One exchange: the request and either the reply or the failure. */
    class Exchange(val request: DiagRequest, val reply: DiagReply?, val failure: DiagFailure?) {
        override fun toString() = "$request → ${reply ?: failure}"
    }

    sealed interface Step {
        class Answer(val uds: String, val frames: Int, val latencyMs: Int?) : Step
        class NoReply(val gatewayResult: Int, val latencyMs: Int?) : Step
        class Busy(val latencyMs: Int?) : Step
        class Fail(val failure: DiagFailure) : Step
    }

    inner class Script internal constructor(private val steps: ArrayDeque<Step>) {
        fun answer(uds: String, frames: Int = 1, latencyMs: Int? = null) = add(Step.Answer(uds.uppercase(), frames, latencyMs))
        /** The gateway's 3 (no response), 4 (diagnostic timeout) or 0 (the helper's own wait ran out). */
        fun noReply(gatewayResult: Int = 3, latencyMs: Int? = null) = add(Step.NoReply(gatewayResult, latencyMs))
        fun busy(latencyMs: Int? = null) = add(Step.Busy(latencyMs))
        fun fail(failure: DiagFailure) = add(Step.Fail(failure))

        private fun add(step: Step): Script {
            synchronized(this@FakeDiagnosticBus) { steps.addLast(step) }
            return this
        }
    }

    private val mutex = Mutex()
    private val scripts = mutableMapOf<String, ArrayDeque<Step>>()
    private val failures = ArrayDeque<DiagFailure>()
    private val log = mutableListOf<Exchange>()
    private var carNanos = 1_000_000_000L

    /** What a request nobody scripted gets. */
    var unscripted: (DiagRequest) -> Step = { Step.NoReply(3, null) }

    var closed = false
        private set

    /** Every [setGap] in order. */
    val gaps: List<Int> get() = synchronized(this) { gapLog.toList() }
    private val gapLog = mutableListOf<Int>()

    val exchanges: List<Exchange> get() = synchronized(this) { log.toList() }

    /** `7E0 190109` per exchange, in order. */
    val requests: List<String> get() = exchanges.map { it.request.toString() }

    fun on(address: Int, uds: String): Script = synchronized(this) {
        Script(scripts.getOrPut(key(address, uds)) { ArrayDeque() })
    }

    /** The next exchange, whatever its request, fails the session with [failure]. */
    fun failNext(failure: DiagFailure) = synchronized(this) { failures.addLast(failure) }

    override suspend fun exchange(request: DiagRequest): DiagReply = mutex.withLock {
        if (delayMs > 0) delay(delayMs)
        val outcome = synchronized(this) {
            val step = when {
                closed -> Step.Fail(DiagFailure.ChannelLost(IllegalStateException("the fake bus is closed")))
                else -> failures.removeFirstOrNull()?.let(Step::Fail) ?: next(request)
            }
            reply(request, step).also { log += Exchange(request, it.getOrNull(), it.exceptionOrNull() as DiagFailure?) }
        }
        outcome.getOrThrow()
    }

    override fun setGap(ms: Int) = synchronized(this) {
        require(ms in 0..ResidentDiagnosticBus.MAX_GAP_MS) { "gap outside 0..${ResidentDiagnosticBus.MAX_GAP_MS} ms" }
        gapLog += ms
        gapMs = ms
    }

    override fun close() = synchronized(this) { closed = true }

    private fun next(request: DiagRequest): Step {
        val steps = scripts[key(request.address, hex(request.uds))] ?: return unscripted(request)
        return if (steps.size > 1) steps.removeFirst() else steps.firstOrNull() ?: unscripted(request)
    }

    private fun reply(request: DiagRequest, step: Step): Result<DiagReply> {
        fun received(latency: Int?): Pair<Int, Long> {
            val ms = latency ?: latencyMs
            carNanos += (gapMs + ms) * 1_000_000L
            return ms to carNanos
        }
        return when (step) {
            is Step.Fail -> Result.failure(step.failure)
            is Step.Answer -> received(step.latencyMs).let { (ms, at) ->
                Result.success(DiagReply.Answer(request, bytes(step.uds), step.frames, wallClock(), ms, at))
            }
            is Step.NoReply -> received(step.latencyMs).let { (ms, at) ->
                Result.success(DiagReply.NoReply(request, step.gatewayResult, wallClock(), ms, at))
            }
            is Step.Busy -> received(step.latencyMs).let { (ms, at) ->
                Result.success(DiagReply.GatewayBusy(request, wallClock(), ms, at))
            }
        }
    }

    private fun key(address: Int, uds: String) = "%03X %s".format(address, uds.uppercase())

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
