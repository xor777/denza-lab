package dev.denza.apps.feature.vehicle.signal

import android.os.SystemClock
import android.util.Log
import dev.denza.disharebridge.LocalAdbClient
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal interface TurnSignalEventChannel : AutoCloseable {
    fun start()
    fun next(waitMs: Int): String
    override fun close()
}

internal fun interface TurnSignalEventChannelFactory {
    fun open(
        nonce: String,
        requestedKeys: Set<VehicleSignalKey<*>>,
    ): TurnSignalEventChannel
}

/** Dedicated push-oriented lane for the two live-proven BYD light events. */
internal class TargetedBydLightEventSource(
    private val channels: TurnSignalEventChannelFactory,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) : VehicleSignalSource {
    override val id = VehicleSignalSourceId("targeted-byd-light-events")
    override val keys: Set<VehicleSignalKey<*>> = setOf(
        VehicleSignalKeys.TurnIndicatorMode,
        VehicleSignalKeys.TurnSwitchPhase,
    )

    private val lock = Any()
    private val sourceEpoch = AtomicLong()
    private var activation = 0L
    private var worker: ExecutorService? = null
    @Volatile private var activeChannel: TurnSignalEventChannel? = null

    override fun start(
        requestedKeys: Set<VehicleSignalKey<*>>,
        publish: (VehicleSignalSourceUpdate) -> Unit,
    ) {
        require(requestedKeys.isNotEmpty() && keys.containsAll(requestedKeys)) {
            "targeted BYD light source received unsupported signal demand"
        }
        val requested = requestedKeys.toSet()
        val token: Long
        val executor: ExecutorService
        synchronized(lock) {
            check(worker == null) { "targeted BYD light source is already active" }
            token = ++activation
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "denza-turn-signal-source").apply { isDaemon = true }
            }
            worker = executor
        }
        executor.execute { run(token, requested, publish) }
    }

    override fun stop() {
        val channel: TurnSignalEventChannel?
        val executor: ExecutorService?
        synchronized(lock) {
            activation += 1L
            channel = activeChannel
            activeChannel = null
            executor = worker
            worker = null
        }
        // LocalAdbClient closes the socket before waiting for its request lock, which interrupts a
        // long-poll immediately instead of making a service teardown wait for the heartbeat.
        try {
            channel?.close()
        } catch (error: Exception) {
            log("targeted light listener close failed: ${shortError(error)}")
        }
        executor?.shutdownNow()
    }

    private fun run(
        token: Long,
        requestedKeys: Set<VehicleSignalKey<*>>,
        publish: (VehicleSignalSourceUpdate) -> Unit,
    ) {
        var backoffMs = INITIAL_BACKOFF_MS
        try {
            while (isActive(token)) {
                val epoch = sourceEpoch.incrementAndGet()
                publish(VehicleSignalSourceUpdate.ConnectionStarting(epoch))
                var channel: TurnSignalEventChannel? = null
                var failure: VehicleSignalSourceUpdate.Missing? = null
                var terminal = false
                try {
                    val nonce = java.lang.Long.toUnsignedString(
                        System.nanoTime() xor epoch,
                        16,
                    )
                    channel = channels.open(nonce, requestedKeys)
                    if (!claimChannel(token, channel)) return
                    channel.start()
                    val connectedAtElapsedMs = clock()
                    log("targeted light listener connected; epoch=$epoch")
                    val decoder = TurnSignalBatchDecoder()
                    while (isActive(token)) {
                        val response = channel.next(HEARTBEAT_MS)
                        val publishedAtElapsedMs = clock()
                        when (
                            val batch = decoder.decode(
                                response,
                                sourceEpoch = epoch,
                                publishedAtElapsedMs = publishedAtElapsedMs,
                            )
                        ) {
                            is TurnSignalBatchResult.Updates -> {
                                batch.values.forEach(publish)
                                if (
                                    batch.values.any {
                                        it is VehicleSignalSourceUpdate.Sample<*>
                                    } &&
                                    publishedAtElapsedMs - connectedAtElapsedMs >=
                                    STABLE_CONNECTION_MS
                                ) {
                                    backoffMs = INITIAL_BACKOFF_MS
                                }
                            }
                            is TurnSignalBatchResult.Reconnect -> {
                                failure = VehicleSignalSourceUpdate.Missing(
                                    epoch,
                                    VehicleSignalMissingReason.AMBIGUOUS,
                                    batch.reason,
                                )
                                break
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (isActive(token)) {
                        failure = missingFor(epoch, error)
                        terminal = failure.reason == VehicleSignalMissingReason.AUTH_REQUIRED
                    }
                } finally {
                    clearChannel(channel)
                    try {
                        channel?.close()
                    } catch (error: Exception) {
                        log("targeted light listener close failed: ${shortError(error)}")
                    }
                }
                if (!isActive(token)) return
                failure?.let {
                    publish(it)
                    log("targeted light listener unavailable: ${it.details}")
                }
                if (terminal) return
                if (!pause(token, backoffMs)) return
                backoffMs = (backoffMs * 2L).coerceAtMost(MAX_BACKOFF_MS)
            }
        } finally {
            synchronized(lock) {
                if (activation == token) worker = null
            }
        }
    }

    private fun claimChannel(token: Long, channel: TurnSignalEventChannel): Boolean =
        synchronized(lock) {
            if (activation != token || worker == null) {
                false
            } else {
                activeChannel = channel
                true
            }
        }

    private fun clearChannel(channel: TurnSignalEventChannel?) = synchronized(lock) {
        if (activeChannel === channel) activeChannel = null
    }

    private fun isActive(token: Long): Boolean = synchronized(lock) {
        activation == token && worker != null
    }

    private fun pause(token: Long, millis: Long): Boolean {
        if (!isActive(token)) return false
        return try {
            sleep(millis)
            isActive(token)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun missingFor(epoch: Long, error: Throwable): VehicleSignalSourceUpdate.Missing {
        val cause = generateSequence(error) { it.cause }.last()
        val reason = if (
            error is LocalAdbClient.AuthorizationRequiredException ||
            cause is LocalAdbClient.AuthorizationRequiredException
        ) {
            VehicleSignalMissingReason.AUTH_REQUIRED
        } else {
            VehicleSignalMissingReason.SOURCE_DOWN
        }
        val detail = (cause.message ?: cause::class.java.simpleName).take(MAX_DETAIL_LENGTH)
        return VehicleSignalSourceUpdate.Missing(epoch, reason, detail)
    }

    private fun shortError(error: Exception): String =
        (error.message ?: error::class.java.simpleName).take(MAX_DETAIL_LENGTH)

    private companion object {
        const val TAG = "DenzaVehicleSignals"
        const val HEARTBEAT_MS = 5_000
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 60_000L
        const val STABLE_CONNECTION_MS = 15_000L
        const val MAX_DETAIL_LENGTH = 160
    }
}

/** A LocalAdbClient resident channel with one fixed helper and one fixed request. */
internal class AdbTurnSignalEventChannel(
    private val session: LocalAdbClient.ResidentSession,
    private val bootstrap: () -> LocalAdbClient.PersistentShellSession,
    private val nonce: String,
    private val classpath: TurnSignalProxyClasspath,
    requestedKeys: Set<VehicleSignalKey<*>>,
) : TurnSignalEventChannel {
    @Volatile private var closed = false
    @Volatile private var activeBootstrap: LocalAdbClient.PersistentShellSession? = null
    private val signalMask = requestedKeys.fold(0) { mask, key ->
        mask or when (key) {
            VehicleSignalKeys.TurnSwitchPhase -> WATCH_SWITCH
            VehicleSignalKeys.TurnIndicatorMode -> WATCH_MODE
        }
    }.also { require(it in WATCH_SWITCH..WATCH_ALL) }

    override fun start() {
        if (closed) throw IOException("turn-signal event channel is closed")
        val bootstrapSession = bootstrap()
        activeBootstrap = bootstrapSession
        if (closed) {
            bootstrapSession.close()
            activeBootstrap = null
            throw IOException("turn-signal event channel closed during bootstrap")
        }
        val entry = try {
            classpath.entry(bootstrapSession::shell)
        } finally {
            if (activeBootstrap === bootstrapSession) activeBootstrap = null
            bootstrapSession.close()
        }
        if (closed) throw IOException("turn-signal event channel closed during bootstrap")
        session.start(
            "CLASSPATH='${entry.replace("'", "'\\''")}' exec app_process /system/bin " +
                "--nice-name=denza_vehicle_signals " +
                "${TargetedBydLightEventProxyMain::class.java.name} serve $nonce $signalMask",
            START_TIMEOUT_MS,
        )
    }

    override fun next(waitMs: Int): String =
        session.request("next $waitMs", waitMs + RESPONSE_GRACE_MS)

    override fun close() {
        closed = true
        activeBootstrap?.close()
        session.close()
    }

    private companion object {
        const val START_TIMEOUT_MS = 3_500
        const val RESPONSE_GRACE_MS = 1_500
        const val WATCH_SWITCH = 1
        const val WATCH_MODE = 2
        const val WATCH_ALL = WATCH_SWITCH or WATCH_MODE
    }
}
