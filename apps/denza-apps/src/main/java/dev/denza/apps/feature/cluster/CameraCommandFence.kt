package dev.denza.apps.feature.cluster

import java.util.concurrent.atomic.AtomicLong

/** Monotonic fence preventing an already-dispatched Show intent from surviving a later hide. */
internal class CameraCommandFence {
    private val generation = AtomicLong()

    fun issueShow(): Long = generation.incrementAndGet()

    fun invalidate(): Long = generation.incrementAndGet()

    fun current(): Long = generation.get()

    fun isCurrent(candidate: Long): Boolean = candidate == generation.get()
}
