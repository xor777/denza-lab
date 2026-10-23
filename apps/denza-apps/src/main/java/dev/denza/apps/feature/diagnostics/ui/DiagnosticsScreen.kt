package dev.denza.apps.feature.diagnostics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.BlocksFilter
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.ModuleView
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.RailPlace
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.Target

/**
 * The session's run: [DiagnosticsSection] counts each start. A stop drops what the screens asked for
 * (a stream, an open block, a parameter, the cells, a page's GETs, the journal, a code), so every such
 * ask is keyed on the run and is made again when the section comes back to a started screen.
 */
internal val LocalScannerRun = staticCompositionLocalOf { 0 }

/** Where inside the section the driver is (architecture, 7.1, 7.3). */
internal sealed interface ScannerRoute {
    /** «Сводка», the section's entry. */
    data object Summary : ScannerRoute

    /** «Блоки», at one of its filters. */
    data class Blocks(val filter: BlocksFilter = BlocksFilter.ALL) : ScannerRoute

    /** «Журнал» at a tab of [JOURNAL_TABS]. */
    data class Journal(val tab: Int = 0) : ScannerRoute

    /** A system's page at one of its [SystemTab]s (null: its first), its live data as a table or as [graphs]. */
    data class System(val system: CarSystem, val tab: SystemTab? = null, val graphs: Boolean = false) : ScannerRoute

    /** One ECU's screen, at a tab of [moduleTabs]. */
    data class Module(val request: Int, val tab: Int = 0) : ScannerRoute

    /** One parameter on its own page, over the live data it was opened from (architecture, 6.2). */
    data class Param(val key: ParamKey) : ScannerRoute

    /** One record of [request]'s memory on its own screen (7.3). */
    data class Code(val request: Int, val code: String) : ScannerRoute

    /** «Отчёт» (7.4). */
    data object Report : ScannerRoute
}

internal val JOURNAL_TABS = listOf("Хронология", "Сравнение", "Коды")

/**
 * Where the driver has been inside the section, «Сводка» at the bottom (architecture, 7.3). The rail
 * picks a place - «Сводка», «Блоки», a system, «Журнал» - so the stack is `[Summary]` or
 * `[Summary, place]`; a block, a code, a parameter or «Отчёт» opened from a screen goes on top, and
 * Back and «‹ …» take it off again, to the screen and the tab it was opened from. A tab or a filter is
 * not a step: switching it changes the top screen in place.
 */
internal object ScannerStack {
    const val BACK_TO_SUMMARY = "‹ Сводка"

    /** A parameter's page always goes back to the live data it was opened from. */
    const val BACK_TO_LIVE = "‹ Потоковые данные"

    val START: List<ScannerRoute> = listOf(ScannerRoute.Summary)

    /** A place of the rail, or what stands in its place in the crumbs: «Отчёт» opened from «Сводка». */
    private fun standsAlone(r: ScannerRoute?): Boolean =
        r is ScannerRoute.Blocks || r is ScannerRoute.Journal || r is ScannerRoute.System || r is ScannerRoute.Report

    /** The rail's place a route is, or null when it is no place of the rail. */
    fun place(r: ScannerRoute?): RailPlace? = when (r) {
        ScannerRoute.Summary -> RailPlace.Summary
        is ScannerRoute.Blocks -> RailPlace.Blocks
        is ScannerRoute.Journal -> RailPlace.Journal
        is ScannerRoute.System -> RailPlace.System(r.system)
        else -> null
    }

    fun route(place: RailPlace): ScannerRoute = when (place) {
        RailPlace.Summary -> ScannerRoute.Summary
        RailPlace.Blocks -> ScannerRoute.Blocks()
        RailPlace.Journal -> ScannerRoute.Journal()
        is RailPlace.System -> ScannerRoute.System(place.system)
    }

    fun open(stack: List<ScannerRoute>, target: Target): List<ScannerRoute> = when (target) {
        is Target.Module -> stack + ScannerRoute.Module(target.request)
        is Target.System -> rail(stack, RailPlace.System(target.screen))
    }

    /** A rail tap: «Сводка» alone, or the place over it. */
    @Suppress("UNUSED_PARAMETER")
    fun rail(stack: List<ScannerRoute>, place: RailPlace): List<ScannerRoute> =
        START + listOfNotNull(route(place).takeIf { place != RailPlace.Summary })

    /** A row of the live data or a lane of the graphs: its parameter alone, a step over the page. */
    fun param(stack: List<ScannerRoute>, key: ParamKey): List<ScannerRoute> = stack + ScannerRoute.Param(key)

    /** A record: its own screen, a step over the screen it was picked on. */
    fun code(stack: List<ScannerRoute>, request: Int, code: String): List<ScannerRoute> = stack + ScannerRoute.Code(request, code)

    fun report(stack: List<ScannerRoute>): List<ScannerRoute> = stack + ScannerRoute.Report

    /** The screen a parameter's page stands on: the system's live tab, or the block's. */
    fun under(stack: List<ScannerRoute>): ScannerRoute? =
        if (stack.lastOrNull() is ScannerRoute.Param) stack.getOrNull(stack.size - 2) else stack.lastOrNull()

    /** The place the rail shows lit: the one over «Сводка», or «Сводка». */
    fun railPlace(stack: List<ScannerRoute>): RailPlace = place(stack.getOrNull(1)) ?: RailPlace.Summary

    /** One step back; null when «Сводка» itself is left, which closes the section. */
    fun back(stack: List<ScannerRoute>): List<ScannerRoute>? = if (stack.size > 1) stack.dropLast(1) else null

    /** The crumb «Диагностика»: everything over «Сводка» goes. */
    fun toSummary(@Suppress("UNUSED_PARAMETER") stack: List<ScannerRoute>): List<ScannerRoute> = START

    /** Down to the screen at [index] of the stack. */
    fun toLevel(stack: List<ScannerRoute>, index: Int): List<ScannerRoute> = stack.take(index + 1)

    fun retab(stack: List<ScannerRoute>, route: ScannerRoute): List<ScannerRoute> = stack.dropLast(1) + route

    /**
     * The crumbs after «Диагностика», each with its index in the stack: a place of the rail stands in
     * «Сводка»'s - «Шасси › Тормозная система», not «Сводка › Шасси › …». A parameter's page is no crumb:
     * its head names it, and the screen under it is the last crumb.
     */
    fun crumbs(stack: List<ScannerRoute>): List<Pair<Int, ScannerRoute>> =
        stack.withIndex()
            .filter { (i, r) -> !(i == 0 && standsAlone(stack.getOrNull(1))) && r !is ScannerRoute.Param }
            .map { it.index to it.value }

    /** The name of a screen in the crumbs and on «‹ …». */
    fun title(route: ScannerRoute, moduleName: (Int) -> String?): String = when (route) {
        ScannerRoute.Summary -> "Сводка"
        is ScannerRoute.Blocks -> "Блоки"
        is ScannerRoute.Journal -> "Журнал"
        is ScannerRoute.System -> route.system.railName
        is ScannerRoute.Module -> moduleName(route.request) ?: "Блок"
        is ScannerRoute.Param -> BACK_TO_LIVE.removePrefix("‹ ")
        is ScannerRoute.Code -> route.code
        ScannerRoute.Report -> "Отчёт"
    }

    fun backLabel(stack: List<ScannerRoute>, moduleName: (Int) -> String?): String = when {
        stack.lastOrNull() is ScannerRoute.Param -> BACK_TO_LIVE
        else -> stack.getOrNull(stack.size - 2)?.let { "‹ " + title(it, moduleName) } ?: BACK_TO_SUMMARY
    }
}

/**
 * «Сервис → Диагностика», the scanner (architecture, 7): the rail down the left - «Сводка», «Блоки», the
 * systems, «Журнал» - and the chosen place beside it; a block's, a code's and the report's screens over
 * the whole window. Laid out from the Luminofor `scanner-*` boards. Everything shown comes from
 * [model]; everything asked goes to [actions]. Every page reads what it shows as it opens; its buttons
 * start the read again. Back and «‹ …» return to the screen a step was taken from ([ScannerStack]), a
 * crumb to its screen, and the cross or Back on «Сводка» leaves through [onClose].
 *
 * [start] and [sample] are for the debug fixtures: the board's screens and what a still needs that a
 * session never says - its charts' scale, the graphs' cursor.
 */
@Composable
internal fun DiagnosticsScreen(
    model: DiagnosticsModel,
    actions: DiagnosticsActions,
    onClose: () -> Unit,
    start: List<ScannerRoute> = ScannerStack.START,
    sample: ScannerSample? = null,
) {
    var stack by remember { mutableStateOf(start) }
    val route = stack.last()
    val back = { ScannerStack.back(stack)?.let { stack = it } ?: onClose() }
    val open = { t: Target -> stack = ScannerStack.open(stack, t) }
    val openParam = { k: ParamKey -> stack = ScannerStack.param(stack, k) }
    val openCode = { request: Int, code: String -> stack = ScannerStack.code(stack, request, code) }
    val openReport = { stack = ScannerStack.report(stack) }
    val retab = { next: ScannerRoute -> stack = ScannerStack.retab(stack, next) }
    val moduleName = { request: Int -> moduleOf(model, request)?.name }
    val backLabel = ScannerStack.backLabel(stack, moduleName)
    BackHandler { back() }

    // An ECU's screen asks for its identification and readiness once while open - a parameter's page
    // over it keeps it open; the stream runs only while «Потоковые данные» is in front. A parameter's
    // page asks for its parameter alone while it shows (architecture, 6.2); a code's screen its
    // snapshot and counters (7.3).
    val current = rememberUpdatedState(actions)
    val run = LocalScannerRun.current
    val r = route
    val under = ScannerStack.under(stack)
    if (under is ScannerRoute.Module) {
        DisposableEffect(under.request, run) {
            current.value.setModuleOpen(under.request, true)
            onDispose { current.value.setModuleOpen(under.request, false) }
        }
        val live = r is ScannerRoute.Module && model.modules[r.request]?.let { it.obd != null && moduleTab(it, r.tab) == ModuleTab.LIVE } == true
        DisposableEffect(under.request, live, run) {
            if (live) current.value.setLiveVisible(under.request, true)
            onDispose { if (live) current.value.setLiveVisible(under.request, false) }
        }
    }
    if (r is ScannerRoute.Param) {
        DisposableEffect(r.key, run) {
            current.value.setParam(r.key)
            onDispose { current.value.setParam(null) }
        }
    }
    if (r is ScannerRoute.Code) {
        DisposableEffect(r, run) {
            current.value.openCode(r.request, r.code)
            onDispose { current.value.openCode(r.request, null) }
        }
    }
    val journal = r is ScannerRoute.Journal
    DisposableEffect(journal, run) {
        if (journal) current.value.setJournalVisible(true)
        onDispose { if (journal) current.value.setJournalVisible(false) }
    }

    // the crumbs are the stack: each one but the last takes the screens above it off
    val header: @Composable () -> Unit = {
        val crumbs = buildList {
            add(Crumb("Диагностика") { stack = ScannerStack.toSummary(stack) })
            ScannerStack.crumbs(stack).forEach { (i, step) ->
                val here = i == stack.lastIndex
                add(Crumb(ScannerStack.title(step, moduleName), if (here) null else ({ stack = ScannerStack.toLevel(stack, i) })))
            }
        }
        ScanHeader(crumbs, model.header, onClose)
    }
    val frame = RailFrame(ScannerWords.railLines(model), ScannerStack.railPlace(stack), header) { place ->
        stack = ScannerStack.rail(stack, place)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalScanWidth provides maxWidth.value) {
            when (r) {
                ScannerRoute.Summary -> SummaryPage(
                    model, frame, actions, onCode = openCode,
                    onBlocks = { stack = ScannerStack.rail(stack, RailPlace.Blocks) },
                    onCompare = {
                        // the newest scan against the full one before it
                        actions.compareScans(null, Long.MAX_VALUE)
                        stack = ScannerStack.rail(stack, RailPlace.Journal).let { s -> ScannerStack.retab(s, ScannerRoute.Journal(1)) }
                    },
                    onReport = openReport,
                )
                is ScannerRoute.Blocks -> BlocksPage(model, r.filter, frame, { retab(r.copy(filter = it)) }, open, actions, openReport)
                is ScannerRoute.Journal -> JournalPage(model, r.tab, frame, { retab(r.copy(tab = it)) }, actions, openReport, openCode)
                is ScannerRoute.Module -> {
                    val module = moduleOf(model, r.request)
                    if (module == null) {
                        LaunchedEffect(r) { back() }
                    } else {
                        ModulePage(module, r.tab, header, { retab(r.copy(tab = it)) }, back, actions, sample, backLabel, openParam, openCode)
                    }
                }
                is ScannerRoute.System -> {
                    val page = model.systemPages[r.system]
                    if (page == null) {
                        // a system the session has not described yet: «Сводка» instead
                        LaunchedEffect(r) { stack = ScannerStack.START }
                    } else {
                        SystemPageScreen(
                            model, page, r.tab ?: page.tabs.first(), r.graphs, frame,
                            { tab, graphs -> retab(r.copy(tab = tab, graphs = graphs)) }, open, actions, sample, openParam, openCode, openReport,
                        )
                    }
                }
                is ScannerRoute.Param -> {
                    val view = ScannerWords.paramOf(model, r.key)
                    if (view == null) {
                        LaunchedEffect(r) { back() }
                    } else {
                        // beside the rail when it stands on a system's page; over the whole window on a block's
                        ParamPage(view, if (under is ScannerRoute.System) frame else null, header, actions, back)
                    }
                }
                is ScannerRoute.Code -> CodePage(model, r, header, backLabel, back, actions, { stack = ScannerStack.open(stack, Target.Module(it)) }, openCode)
                ScannerRoute.Report -> ReportPage(model, header, back, actions)
            }
        }
    }
}

/**
 * The ECU's screen as the session built it, or - before the session has opened it - what the
 * section's rows know: the route, the protocol, a memory not read.
 */
internal fun moduleOf(model: DiagnosticsModel, request: Int): ModuleView? {
    model.modules[request]?.let { return it }
    val row = ScannerWords.row(model, request) ?: return null
    return ModuleView(
        request = request,
        name = row.name,
        unit = row.unit,
        inferred = row.inferred,
        line = "${row.route} · ${row.protocol}",
        status = row.status,
        dtc = DtcView(emptyList(), emptyList(), DataQuality.NOT_POLLED, outsideMask = false, footnote = ""),
        ident = IdentView(emptyList(), emptyList(), pids = emptyList()),
        obd = null,
    )
}
