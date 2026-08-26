package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф3 волны 16: фоновый шум больше не может вытеснить строки операции.
 *
 * Приёмка v32 потеряла на этом два круга замеров: сверка ходит к машине каждые несколько секунд и
 * пишет о себе, а показывается ограниченное число строк, поэтому за минуту от операции не
 * оставалось ничего. Ринг читается с экрана поддержки, то есть места в нём столько, сколько
 * помещается на экран, - значит вопрос не в объёме, а в том, кто у кого отнимает слоты.
 */
class SplitDiagnosticRingTest {

    @Test
    fun backgroundWorkCannotEvictASingleLineOfAnOperation() {
        val ring = SplitDiagnosticRing()

        ring.record(1, "open: обращений 28", background = false)
        repeat(1_000) { at -> ring.record(2L + at, "reconcile сверка $at", background = true) }

        val shown = ring.recent(operationLimit = 48, backgroundLimit = 12)
        assertEquals(listOf("1 open: обращений 28"), shown.take(1))
        assertEquals("the background lane keeps its own size", 13, shown.size)
    }

    /** A world that has not changed says the same thing; that is one line and a count. */
    @Test
    fun aBackgroundLineRepeatingItselfCollapsesIntoOneWithACount() {
        val ring = SplitDiagnosticRing()

        repeat(5) { at -> ring.record(10L + at, "reconcile: мир не изменился", background = true) }
        ring.record(20, "reconcile: панель опустела", background = true)
        ring.record(21, "reconcile: мир не изменился", background = true)

        assertEquals(
            listOf(
                "10 reconcile: мир не изменился (x5)",
                "20 reconcile: панель опустела",
                "21 reconcile: мир не изменился",
            ),
            ring.recent(operationLimit = 10, backgroundLimit = 10),
        )
    }

    /** Operations never collapse: two identical steps of one run are two facts, not one. */
    @Test
    fun anOperationRepeatingItselfIsKeptLineByLine() {
        val ring = SplitDiagnosticRing()

        repeat(3) { at -> ring.record(30L + at, "open +100ms scene-read", background = false) }

        assertEquals(3, ring.recent(operationLimit = 10, backgroundLimit = 0).size)
    }

    @Test
    fun theOperationLaneKeepsTheNewestAndDropsTheOldest() {
        val ring = SplitDiagnosticRing(operationCapacity = 3, backgroundCapacity = 2)

        (1..5).forEach { at -> ring.record(at.toLong(), "step $at", background = false) }

        assertEquals(
            listOf("3 step 3", "4 step 4", "5 step 5"),
            ring.recent(operationLimit = 10, backgroundLimit = 0),
        )
    }

    /**
     * And the lane is decided by what the operation is, not by what its line happens to say.
     *
     * The oracle is the fake car's own log seam: every line the coordinator writes arrives there
     * with the lane it was written into.
     */
    @Test
    fun aBackgroundReconcileWritesIntoTheBackgroundLaneAndAUserOpenDoesNot() {
        val car = SplitCarFixture(FakeShell().apply { liveProductScene() })
        try {
            val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR))
            core.initialize {}
            car.barrier()
            car.diagnostics.clear()
            car.backgroundDiagnostics.clear()

            core.dividerResized()
            car.barrier()

            assertTrue("the reconcile said nothing at all", car.backgroundDiagnostics.isNotEmpty())
            assertEquals(
                "every line of it belongs to the background lane",
                car.diagnostics.toList(),
                car.backgroundDiagnostics.toList(),
            )

            car.diagnostics.clear()
            car.backgroundDiagnostics.clear()
            core.openPickerSession()
            car.barrier()

            assertTrue(
                "the open wrote nothing",
                car.diagnostics.any { line -> line.startsWith("open") },
            )
            assertEquals(
                "and none of it into the lane background work may fill",
                emptyList<String>(),
                car.backgroundDiagnostics.toList(),
            )
        } finally {
            car.close()
        }
    }

    /** The screen shows the run first and the background after it, never interleaved. */
    @Test
    fun theOperationsComeFirstAndTheBackgroundAfterThem() {
        val ring = SplitDiagnosticRing()

        ring.record(1, "reconcile ранняя", background = true)
        ring.record(2, "open начался", background = false)
        ring.record(3, "reconcile поздняя", background = true)
        ring.record(4, "open кончился", background = false)

        val shown = ring.recent(operationLimit = 10, backgroundLimit = 10)
        assertEquals(listOf("2 open начался", "4 open кончился"), shown.take(2))
        assertTrue(shown.drop(2).all { line -> line.contains("reconcile") })
    }
}
