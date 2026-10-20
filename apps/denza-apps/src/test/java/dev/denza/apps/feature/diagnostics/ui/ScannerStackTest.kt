package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Back goes where the driver came from (on the car 2026-10-20 it always went to the list), and the
 * rail picks a system as a tab of the section, not a step (architecture, 7.2).
 */
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
            ScannerStack.rail(listOf(ScannerRoute.Scan()), CarSystem.DRIVES),
            ScannerRoute.System(CarSystem.DRIVES, SystemTab.COMPARE),
        )
        val module = ScannerStack.open(drives, Target.Module(0x7E3))
        assertEquals("‹ Электропривод", ScannerStack.backLabel(module, ::name))
        assertEquals(drives, ScannerStack.back(module))
        // a tab is not a step
        val live = ScannerStack.retab(module, ScannerRoute.Module(0x7E3, tab = 1))
        assertEquals(drives, ScannerStack.back(live))
    }

    @Test
    fun theRailMakesTheAutoscanOrOneSystemOverIt() {
        val topology = ScannerRoute.Scan(topology = true)
        val deep = ScannerStack.open(ScannerStack.rail(listOf(topology), CarSystem.HV), Target.Module(0x7E3))
        // a system from the rail replaces whatever stood over the autoscan, whose tab is kept
        assertEquals(listOf(topology, ScannerRoute.System(CarSystem.BODY)), ScannerStack.rail(deep, CarSystem.BODY))
        assertEquals(listOf(topology), ScannerStack.rail(deep, null))
        // a box that leads to another system is the rail's tap, not a step
        val hv = ScannerStack.rail(listOf(topology), CarSystem.HV)
        assertEquals(listOf(topology, ScannerRoute.System(CarSystem.PACK)), ScannerStack.open(hv, Target.System(CarSystem.PACK)))
        assertEquals(CarSystem.HV, ScannerStack.railSystem(hv))
        assertEquals(CarSystem.HV, ScannerStack.railSystem(deep))
        assertNull(ScannerStack.railSystem(listOf(topology)))
        // Back from a system goes to the autoscan, and from the autoscan out
        assertEquals(listOf(topology), ScannerStack.back(hv))
    }

    @Test
    fun theCrumbsNameTheSystemInTheAutoscansPlace() {
        val stack = ScannerStack.open(ScannerStack.rail(listOf(ScannerRoute.Scan(topology = true)), CarSystem.HV), Target.Module(0x7E3))
        assertEquals(listOf("ВВ-система", "Контроллер переднего мотора"),
            ScannerStack.crumbs(stack).map { ScannerStack.title(it.second, ::name) })
        assertEquals(listOf(1, 2), ScannerStack.crumbs(stack).map { it.first })
        assertEquals(stack.take(2), ScannerStack.toLevel(stack, 1))
        assertEquals(listOf(ScannerRoute.Scan(topology = true)), ScannerStack.toScan(stack))
        // from the autoscan the crumbs keep «Автоскан»
        val fromScan = ScannerStack.open(listOf(ScannerRoute.Scan()), Target.Module(0x720))
        assertEquals(listOf("Автоскан", "Кузовной контроллер левый"), ScannerStack.crumbs(fromScan).map { ScannerStack.title(it.second, ::name) })
    }
}
