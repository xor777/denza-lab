package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulcastWindowReconcilerTest {
    private val host = RecordingHost()
    private val reconciler = SimulcastWindowReconciler(host)

    @Test
    fun `geometry-only change updates existing windows without rebuild`() {
        reconciler.apply(plan(slot("one", 0), slot("two", 100)))
        host.clear()

        val result = reconciler.apply(plan(slot("one", 2), slot("two", 102)))

        assertEquals(listOf("update:slot:one", "update:slot:two"), host.operations)
        assertEquals(2, result.relayouts)
        assertFalse(result.semanticRebuild)
    }

    @Test
    fun `receiver generation alone does not relayout`() {
        val initial = plan(slot("one", 0))
        reconciler.apply(initial)
        host.clear()

        val result = reconciler.apply(initial)

        assertTrue(host.operations.isEmpty())
        assertEquals(0, result.relayouts)
        assertFalse(result.semanticRebuild)
    }

    @Test
    fun `changing one slot only removes and adds affected windows`() {
        reconciler.apply(plan(slot("one", 0), slot("two", 100)))
        host.clear()

        val result = reconciler.apply(plan(slot("one", 0), slot("three", 100)))

        assertEquals(
            listOf("remove:slot:two", "add:slot:three", "raise"),
            host.operations,
        )
        assertEquals(0, result.relayouts)
        assertTrue(result.semanticRebuild)
    }

    @Test
    fun `closing dialog removes all windows once`() {
        reconciler.apply(plan(slot("one", 0), slot("two", 100)))
        host.clear()

        val result = reconciler.apply(emptyList())

        assertEquals(
            listOf("remove:slot:one", "remove:slot:two", "remove:row"),
            host.operations,
        )
        assertTrue(result.semanticRebuild)
        assertFalse(result.pendingOperations)
        assertFalse(reconciler.hasAppliedWindows())

        host.clear()
        reconciler.apply(emptyList())
        assertTrue(host.operations.isEmpty())
    }

    @Test
    fun `failed add is retried with the same plan`() {
        host.failAdds += "slot:one"

        val failed = reconciler.apply(listOf(slot("one", 0)))

        assertEquals(listOf("add:slot:one"), host.operations)
        assertFalse(failed.semanticRebuild)
        assertTrue(failed.pendingOperations)

        host.clear()
        val retried = reconciler.apply(listOf(slot("one", 0)))

        assertEquals(listOf("add:slot:one", "raise"), host.operations)
        assertTrue(retried.semanticRebuild)
        assertFalse(retried.pendingOperations)
    }

    @Test
    fun `failed update is retried with the same geometry`() {
        reconciler.apply(listOf(slot("one", 0)))
        host.clear()
        host.failUpdates += "slot:one"

        val failed = reconciler.apply(listOf(slot("one", 10)))

        assertEquals(listOf("update:slot:one"), host.operations)
        assertEquals(0, failed.relayouts)
        assertTrue(failed.pendingOperations)

        host.clear()
        val retried = reconciler.apply(listOf(slot("one", 10)))

        assertEquals(listOf("update:slot:one"), host.operations)
        assertEquals(1, retried.relayouts)
        assertFalse(retried.pendingOperations)
    }

    @Test
    fun `failed remove remains pending until the host succeeds`() {
        reconciler.apply(listOf(slot("one", 0)))
        host.clear()
        host.failRemoves += "slot:one"

        val failed = reconciler.apply(emptyList())

        assertEquals(listOf("remove:slot:one"), host.operations)
        assertFalse(failed.semanticRebuild)
        assertTrue(failed.pendingOperations)
        assertTrue(reconciler.hasAppliedWindows())

        host.clear()
        val retried = reconciler.apply(emptyList())

        assertEquals(listOf("remove:slot:one"), host.operations)
        assertTrue(retried.semanticRebuild)
        assertFalse(retried.pendingOperations)
        assertFalse(reconciler.hasAppliedWindows())
    }

    @Test
    fun `failed draw layer raise is retried without a semantic rebuild`() {
        host.failRaise = true

        val failed = reconciler.apply(listOf(slot("one", 0)))

        assertEquals(listOf("add:slot:one", "raise"), host.operations)
        assertTrue(failed.semanticRebuild)
        assertTrue(failed.pendingOperations)

        host.clear()
        val retried = reconciler.apply(listOf(slot("one", 0)))

        assertEquals(listOf("raise"), host.operations)
        assertFalse(retried.semanticRebuild)
        assertFalse(retried.pendingOperations)
    }

    private fun slot(packageName: String, left: Int) =
        SimulcastWindowReconciler.WindowSpec(
            "slot:$packageName",
            SimulcastWindowReconciler.Kind.SLOT,
            left,
            0,
            80,
            80,
        )

    private fun plan(vararg slots: SimulcastWindowReconciler.WindowSpec) =
        slots.toList() + SimulcastWindowReconciler.WindowSpec(
            "row",
            SimulcastWindowReconciler.Kind.ROW_PLATE,
            0,
            0,
            300,
            100,
        )

    private class RecordingHost : SimulcastWindowReconciler.Host {
        val operations = mutableListOf<String>()
        val failAdds = mutableSetOf<String>()
        val failUpdates = mutableSetOf<String>()
        val failRemoves = mutableSetOf<String>()
        var failRaise = false

        override fun add(spec: SimulcastWindowReconciler.WindowSpec): Boolean {
            operations += "add:${spec.id}"
            return !failAdds.remove(spec.id)
        }

        override fun update(spec: SimulcastWindowReconciler.WindowSpec): Boolean {
            operations += "update:${spec.id}"
            return !failUpdates.remove(spec.id)
        }

        override fun remove(spec: SimulcastWindowReconciler.WindowSpec): Boolean {
            operations += "remove:${spec.id}"
            return !failRemoves.remove(spec.id)
        }

        override fun raiseDrawLayer(): Boolean {
            operations += "raise"
            if (failRaise) {
                failRaise = false
                return false
            }
            return true
        }

        fun clear() {
            operations.clear()
        }
    }
}
