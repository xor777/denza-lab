package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Back goes where the driver came from (on the car 2026-10-20 it always went to the list). */
class ScannerStackTest {

    private val names = mapOf(0x720 to "Кузовной контроллер левый", 0x7E3 to "Контроллер переднего мотора")
    private fun name(request: Int) = names[request]

    @Test
    fun aModuleOpenedFromTheTopologyGoesBackToTheTopology() {
        val topology = listOf<ScannerRoute>(ScannerRoute.Scan(topology = true))
        val opened = ScannerStack.open(topology, Target.Module(0x720))
        assertEquals("‹ Автоскан", ScannerStack.backLabel(opened, ::name))
        assertEquals(topology, ScannerStack.back(opened))
        assertNull(ScannerStack.back(topology))
    }

    @Test
    fun aModuleOpenedFromASystemGoesBackToTheSystemAndItsTab() {
        val drives = ScannerStack.retab(
            ScannerStack.open(listOf(ScannerRoute.Scan()), Target.System(CarSystem.DRIVES)),
            ScannerRoute.System(CarSystem.DRIVES, tab = 1),
        )
        val module = ScannerStack.open(drives, Target.Module(0x7E3))
        assertEquals("‹ Электропривод", ScannerStack.backLabel(module, ::name))
        assertEquals(drives, ScannerStack.back(module))
        // a tab is not a step
        val live = ScannerStack.retab(module, ScannerRoute.Module(0x7E3, tab = 1))
        assertEquals(drives, ScannerStack.back(live))
    }

    @Test
    fun theCrumbsTakeTheScreensAboveThem() {
        val stack = ScannerStack.open(
            ScannerStack.open(listOf(ScannerRoute.Scan(topology = true)), Target.System(CarSystem.HV)),
            Target.Module(0x7E3),
        )
        assertEquals(listOf("Автоскан", "Высоковольтная система", "Контроллер переднего мотора"), stack.map { ScannerStack.title(it, ::name) })
        assertEquals(stack.take(2), ScannerStack.toLevel(stack, 1))
        assertEquals(listOf(ScannerRoute.Scan(topology = true)), ScannerStack.toScan(stack))
    }
}
