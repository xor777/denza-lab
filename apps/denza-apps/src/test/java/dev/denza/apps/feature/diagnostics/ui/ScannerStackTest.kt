package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.feature.diagnostics.scan.BlocksFilter
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.RailPlace
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Back goes where the driver came from (on the car 2026-10-20 it always went to the list), and the
 * rail picks a place - «Сводка», «Блоки», a system, «Журнал» - as a tab of the section, not a step
 * (architecture, 7.2, 7.3).
 */
class ScannerStackTest {

    private val names = mapOf(0x720 to "Кузовной контроллер левый", 0x7E3 to "Контроллер переднего мотора")
    private fun name(request: Int) = names[request]
    private val start = ScannerStack.START

    @Test
    fun theSectionOpensOnTheSummaryAndBackFromItCloses() {
        assertEquals(listOf<ScannerRoute>(ScannerRoute.Summary), start)
        assertNull(ScannerStack.back(start))
        assertEquals(RailPlace.Summary, ScannerStack.railPlace(start))
        assertEquals(listOf("Сводка"), ScannerStack.crumbs(start).map { ScannerStack.title(it.second, ::name) })
    }

    @Test
    fun aModuleOpenedFromTheBlocksGoesBackToTheBlocksAtTheirFilter() {
        val blocks = ScannerStack.retab(ScannerStack.rail(start, RailPlace.Blocks), ScannerRoute.Blocks(BlocksFilter.WITH_RECORDS))
        val opened = ScannerStack.open(blocks, Target.Module(0x720))
        assertEquals("‹ Блоки", ScannerStack.backLabel(opened, ::name))
        assertEquals(blocks, ScannerStack.back(opened))
        // «Блоки» stands in «Сводка»'s place in the crumbs, as the board has it
        assertEquals(listOf("Блоки", "Кузовной контроллер левый"), ScannerStack.crumbs(opened).map { ScannerStack.title(it.second, ::name) })
        assertEquals(RailPlace.Blocks, ScannerStack.railPlace(opened))
        assertEquals(start, ScannerStack.back(blocks))
    }

    @Test
    fun aModuleOpenedFromASystemGoesBackToTheSystemAndItsTab() {
        val drives = ScannerStack.retab(ScannerStack.rail(start, RailPlace.System(CarSystem.DRIVES)), ScannerRoute.System(CarSystem.DRIVES, SystemTab.COMPARE))
        val module = ScannerStack.open(drives, Target.Module(0x7E3))
        assertEquals("‹ Электропривод", ScannerStack.backLabel(module, ::name))
        assertEquals(drives, ScannerStack.back(module))
        // a tab is not a step
        val live = ScannerStack.retab(module, ScannerRoute.Module(0x7E3, tab = 1))
        assertEquals(drives, ScannerStack.back(live))
    }

    @Test
    fun theRailMakesTheSummaryOrOnePlaceOverIt() {
        val deep = ScannerStack.open(ScannerStack.rail(start, RailPlace.System(CarSystem.HV)), Target.Module(0x7E3))
        // a place from the rail replaces whatever stood over «Сводка»
        assertEquals(start + ScannerRoute.System(CarSystem.BODY), ScannerStack.rail(deep, RailPlace.System(CarSystem.BODY)))
        assertEquals(start, ScannerStack.rail(deep, RailPlace.Summary))
        assertEquals(start + ScannerRoute.Journal(), ScannerStack.rail(deep, RailPlace.Journal))
        // a box that leads to another system is the rail's tap, not a step
        val hv = ScannerStack.rail(start, RailPlace.System(CarSystem.HV))
        assertEquals(start + ScannerRoute.System(CarSystem.PACK), ScannerStack.open(hv, Target.System(CarSystem.PACK)))
        assertEquals(RailPlace.System(CarSystem.HV), ScannerStack.railPlace(hv))
        assertEquals(RailPlace.System(CarSystem.HV), ScannerStack.railPlace(deep))
        // Back from a place goes to «Сводка», and from «Сводка» out
        assertEquals(start, ScannerStack.back(hv))
    }

    @Test
    fun aCodeIsAStepOverTheScreenItWasPickedOn() {
        val code = ScannerStack.code(start, 0x720, "U0121-87")
        assertEquals(ScannerRoute.Code(0x720, "U0121-87"), code.last())
        assertEquals("‹ Сводка", ScannerStack.backLabel(code, ::name))
        assertEquals(listOf("Сводка", "U0121-87"), ScannerStack.crumbs(code).map { ScannerStack.title(it.second, ::name) })
        // its block opens over it and goes back to it
        val block = ScannerStack.open(code, Target.Module(0x720))
        assertEquals("‹ U0121-87", ScannerStack.backLabel(block, ::name))
        assertEquals(code, ScannerStack.back(block))
        // the report stands alone in the crumbs
        val report = ScannerStack.report(start)
        assertEquals(listOf("Отчёт"), ScannerStack.crumbs(report).map { ScannerStack.title(it.second, ::name) })
        assertEquals(start, ScannerStack.back(report))
    }

    @Test
    fun aParameterIsAStepOverTheLiveDataItWasOpenedFrom() {
        val rpm = ParamKey.Pid(0x7E0, 0x0C)
        val live = ScannerStack.retab(ScannerStack.rail(start, RailPlace.System(CarSystem.ENGINE)), ScannerRoute.System(CarSystem.ENGINE, SystemTab.LIVE))
        val page = ScannerStack.param(live, rpm)
        assertEquals(ScannerRoute.Param(rpm), page.last())
        assertEquals(ScannerRoute.System(CarSystem.ENGINE, SystemTab.LIVE), ScannerStack.under(page))
        // Back and «‹ Потоковые данные» return to the live tab, the rail keeps the system lit
        assertEquals("‹ Потоковые данные", ScannerStack.backLabel(page, ::name))
        assertEquals(live, ScannerStack.back(page))
        assertEquals(RailPlace.System(CarSystem.ENGINE), ScannerStack.railPlace(page))
        // the head names the parameter, so the crumbs end at the system, which takes the page off
        assertEquals(listOf("Двигатель"), ScannerStack.crumbs(page).map { ScannerStack.title(it.second, ::name) })
        assertEquals(live, ScannerStack.toLevel(page, ScannerStack.crumbs(page).last().first))
        // the rail picks another system: the page goes with the system under it
        assertEquals(start + ScannerRoute.System(CarSystem.PACK), ScannerStack.rail(page, RailPlace.System(CarSystem.PACK)))
        // the graphs are the live tab's other view, not a step
        val graphs = ScannerStack.retab(live, ScannerRoute.System(CarSystem.ENGINE, SystemTab.LIVE, graphs = true))
        assertEquals(graphs, ScannerStack.back(ScannerStack.param(graphs, rpm)))
        assertEquals(ScannerStack.back(live), ScannerStack.back(graphs))

        // on a block's live tab the block stays under the page
        val block = ScannerStack.retab(ScannerStack.open(ScannerStack.rail(start, RailPlace.Blocks), Target.Module(0x7E0)), ScannerRoute.Module(0x7E0, tab = 1))
        val overBlock = ScannerStack.param(block, rpm)
        assertEquals(ScannerRoute.Module(0x7E0, tab = 1), ScannerStack.under(overBlock))
        assertEquals(block, ScannerStack.back(overBlock))
    }

    @Test
    fun theWindowsAndTheirTicks() {
        assertEquals(listOf("10 с", "60 с", "5 мин"), ScannerSpec.Param.WINDOWS.map(ScannerWords::window))
        assertEquals(listOf("−60 с", "−45 с", "−30 с", "−15 с", "сейчас"), ScannerWords.ticks(60).map { it.second })
        assertEquals(listOf(60f, 45f, 30f, 15f, 0f), ScannerWords.ticks(60).map { it.first })
        // the other windows in round steps, not quarters («−7,5 с», «−225 с» read badly)
        assertEquals(listOf("−10 с", "−8 с", "−6 с", "−4 с", "−2 с", "сейчас"), ScannerWords.ticks(10).map { it.second })
        assertEquals(listOf("−5 мин", "−4 мин", "−3 мин", "−2 мин", "−1 мин", "сейчас"), ScannerWords.ticks(300).map { it.second })
        assertEquals(listOf(300f, 240f, 180f, 120f, 60f, 0f), ScannerWords.ticks(300).map { it.first })
    }

    @Test
    fun theCrumbsNameThePlaceInTheSummarysPlace() {
        val stack = ScannerStack.open(ScannerStack.rail(start, RailPlace.System(CarSystem.HV)), Target.Module(0x7E3))
        assertEquals(listOf("ВВ-система", "Контроллер переднего мотора"), ScannerStack.crumbs(stack).map { ScannerStack.title(it.second, ::name) })
        assertEquals(listOf(1, 2), ScannerStack.crumbs(stack).map { it.first })
        assertEquals(stack.take(2), ScannerStack.toLevel(stack, 1))
        assertEquals(start, ScannerStack.toSummary(stack))
    }
}
