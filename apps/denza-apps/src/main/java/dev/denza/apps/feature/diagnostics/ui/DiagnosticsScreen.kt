package dev.denza.apps.feature.diagnostics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.ModuleView
import dev.denza.apps.feature.diagnostics.scan.ParamKey
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.Target

/** Where inside the section the driver is. */
internal sealed interface ScannerRoute {
    /** «Автоскан»: the list, or the same ECUs on the bus. */
    data class Scan(val topology: Boolean = false) : ScannerRoute

    /** One ECU's screen, at a tab of [moduleTabs]. */
    data class Module(val request: Int, val tab: Int = 0) : ScannerRoute

    /** A system's page at one of its [SystemTab]s; null is the page's first. */
    data class System(val system: CarSystem, val tab: SystemTab? = null) : ScannerRoute

    /** One parameter on its own page, over the live data it was opened from (architecture, 6.2). */
    data class Param(val key: ParamKey) : ScannerRoute
}

/**
 * Where the driver has been inside the section, the autoscan at the bottom (architecture, 7.2).
 * The rail picks the autoscan or one system - a system is a tab of the section, not a step - so
 * the stack is `[Scan]` or `[Scan, System]`; a block opened from either goes on top, and Back and
 * «‹ …» take it off again, to the screen and the tab it was opened from. A tab is not a step:
 * switching it changes the top screen in place.
 */
internal object ScannerStack {
    const val BACK_TO_SCAN = "‹ Автоскан"

    /** A parameter's page always goes back to the live data it was opened from. */
    const val BACK_TO_LIVE = "‹ Потоковые данные"

    fun open(stack: List<ScannerRoute>, target: Target): List<ScannerRoute> = when (target) {
        is Target.Module -> stack + ScannerRoute.Module(target.request)
        is Target.System -> rail(stack, target.screen)
    }

    /** A rail tap: the autoscan with its own tab kept, and the system over it when one was picked. */
    fun rail(stack: List<ScannerRoute>, system: CarSystem?): List<ScannerRoute> =
        stack.take(1) + listOfNotNull(system?.let { ScannerRoute.System(it) })

    /** A row of the live data: its parameter alone, a step over the table. */
    fun param(stack: List<ScannerRoute>, key: ParamKey): List<ScannerRoute> = stack + ScannerRoute.Param(key)

    /** The screen a parameter's page stands on: the system's live tab, or the block's. */
    fun under(stack: List<ScannerRoute>): ScannerRoute? =
        if (stack.lastOrNull() is ScannerRoute.Param) stack.getOrNull(stack.size - 2) else stack.lastOrNull()

    /** The system the rail shows lit: the one under the top screen, or none for the autoscan. */
    fun railSystem(stack: List<ScannerRoute>): CarSystem? = (stack.getOrNull(1) as? ScannerRoute.System)?.system

    /** One step back; null when the autoscan itself is left, which closes the section. */
    fun back(stack: List<ScannerRoute>): List<ScannerRoute>? = if (stack.size > 1) stack.dropLast(1) else null

    /** The crumb «Диагностика»: everything over the autoscan goes, its own tab stays. */
    fun toScan(stack: List<ScannerRoute>): List<ScannerRoute> = stack.take(1)

    /** Down to the screen at [index] of the stack. */
    fun toLevel(stack: List<ScannerRoute>, index: Int): List<ScannerRoute> = stack.take(index + 1)

    fun retab(stack: List<ScannerRoute>, route: ScannerRoute): List<ScannerRoute> = stack.dropLast(1) + route

    /**
     * The crumbs after «Диагностика», each with its index in the stack: a system stands in the
     * autoscan's place, as the rail has it - «Шасси › Тормозная система», not «Автоскан › Шасси › …».
     * A parameter's page is no crumb: its head names it, and the screen under it is the last crumb.
     */
    fun crumbs(stack: List<ScannerRoute>): List<Pair<Int, ScannerRoute>> =
        stack.withIndex()
            .filter { (i, r) -> !(i == 0 && stack.getOrNull(1) is ScannerRoute.System) && r !is ScannerRoute.Param }
            .map { it.index to it.value }

    /** The name of a screen in the crumbs and on «‹ …»: the module's, the system's rail name, «Автоскан». */
    fun title(route: ScannerRoute, moduleName: (Int) -> String?): String = when (route) {
        is ScannerRoute.Scan -> "Автоскан"
        is ScannerRoute.Module -> moduleName(route.request) ?: "Блок"
        is ScannerRoute.System -> route.system.railName
        is ScannerRoute.Param -> BACK_TO_LIVE.removePrefix("‹ ")
    }

    fun backLabel(stack: List<ScannerRoute>, moduleName: (Int) -> String?): String = when {
        stack.lastOrNull() is ScannerRoute.Param -> BACK_TO_LIVE
        else -> stack.getOrNull(stack.size - 2)?.let { "‹ " + title(it, moduleName) } ?: BACK_TO_SCAN
    }
}

/**
 * «Сервис → Диагностика», the scanner (architecture, 7): the systems down the left - «Автоскан»
 * first - and the chosen one beside them; a block's screen over the whole window. Laid out from the
 * Luminofor `scanner-*` boards. Everything shown comes from [model]; everything asked goes to
 * [actions]. A row, a tile or a box leads by its [Target]; Back and «‹ …» return to the screen it
 * was opened from ([ScannerStack]), a crumb to its screen, and the cross or Back on the autoscan
 * leaves through [onClose].
 *
 * [start] and [sample] are for the debug fixtures: the board's screens, the autoscan at the
 * bottom, and the scale its live traces are already in.
 */
@Composable
internal fun DiagnosticsScreen(
    model: DiagnosticsModel,
    actions: DiagnosticsActions,
    onClose: () -> Unit,
    start: List<ScannerRoute> = listOf(ScannerRoute.Scan()),
    sample: ScannerSample? = null,
) {
    var stack by remember { mutableStateOf(start) }
    var onlyDtc by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val route = stack.last()
    val back = { ScannerStack.back(stack)?.let { stack = it } ?: onClose() }
    val open = { t: Target -> stack = ScannerStack.open(stack, t) }
    val openParam = { k: ParamKey -> stack = ScannerStack.param(stack, k) }
    val retab = { next: ScannerRoute -> stack = ScannerStack.retab(stack, next) }
    val moduleName = { request: Int -> moduleOf(model, request)?.name }
    val backLabel = ScannerStack.backLabel(stack, moduleName)
    BackHandler { back() }

    // An ECU's screen asks for its identification and readiness once while open - a parameter's page
    // over it keeps it open; the stream runs only while «Потоковые данные» is in front. A parameter's
    // page asks for its parameter alone while it shows (architecture, 6.2).
    val current = rememberUpdatedState(actions)
    val r = route
    val under = ScannerStack.under(stack)
    if (under is ScannerRoute.Module) {
        DisposableEffect(under.request) {
            current.value.setModuleOpen(under.request, true)
            onDispose { current.value.setModuleOpen(under.request, false) }
        }
        val live = r is ScannerRoute.Module && model.modules[r.request]?.obd != null && r.tab == 1
        DisposableEffect(under.request, live) {
            if (live) current.value.setLiveVisible(under.request, true)
            onDispose { if (live) current.value.setLiveVisible(under.request, false) }
        }
    }
    if (r is ScannerRoute.Param) {
        DisposableEffect(r.key) {
            current.value.setParam(r.key)
            onDispose { current.value.setParam(null) }
        }
    }

    val link = ScannerWords.link(model.header.link)
    // the crumbs are the stack: each one but the last takes the screens above it off
    val header: @Composable () -> Unit = {
        val crumbs = buildList {
            add(Crumb("Диагностика") { stack = ScannerStack.toScan(stack) })
            val shown = ScannerStack.crumbs(stack)
            shown.forEach { (i, step) ->
                val here = i == stack.lastIndex
                add(Crumb(ScannerStack.title(step, moduleName), if (here) null else ({ stack = ScannerStack.toLevel(stack, i) })))
            }
        }
        ScanHeader(crumbs, link.text, link.dot, link.glow, model.header.car, model.header.clock, onClose)
    }
    val frame = RailFrame(ScannerWords.rail(model), ScannerStack.railSystem(stack), header) { system ->
        stack = ScannerStack.rail(stack, system)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalScanWidth provides maxWidth.value) {
            when (r) {
                is ScannerRoute.Scan -> if (r.topology) {
                    TopologyPage(model, frame, { retab(ScannerRoute.Scan(it == 1)) }, open, actions::rescan)
                } else {
                    ScanListPage(model, frame, { retab(ScannerRoute.Scan(it == 1)) }, listState, onlyDtc, { onlyDtc = it }, open, actions::rescan)
                }
                is ScannerRoute.Module -> {
                    val module = moduleOf(model, r.request)
                    if (module == null) {
                        LaunchedEffect(r) { back() }
                    } else {
                        ModulePage(module, r.tab, header, { retab(r.copy(tab = it)) }, back, actions, sample, backLabel, openParam)
                    }
                }
                is ScannerRoute.System -> {
                    val page = model.systemPages[r.system]
                    if (page == null) {
                        // a system the session has not described yet: the autoscan instead
                        LaunchedEffect(r) { stack = ScannerStack.rail(stack, null) }
                    } else {
                        SystemPageScreen(model, page, r.tab ?: page.tabs.first(), frame, { retab(r.copy(tab = it)) }, open, actions, sample, openParam)
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
            }
        }
    }
}

/**
 * The ECU's screen as the session built it, or - before the session has opened it - what the
 * autoscan's row knows: the route, the protocol, a memory not read.
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
