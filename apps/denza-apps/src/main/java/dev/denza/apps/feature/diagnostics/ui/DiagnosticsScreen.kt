package dev.denza.apps.feature.diagnostics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsActions
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.ModuleView
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.Target

/** Where inside the section the driver is. */
internal sealed interface ScannerRoute {
    /** «Автоскан»: the list, or the same ECUs on the bus. */
    data class Scan(val topology: Boolean = false) : ScannerRoute

    /** One ECU's screen, at a tab of [moduleTabs]. */
    data class Module(val request: Int, val tab: Int = 0) : ScannerRoute

    /** A system screen, at a tab of [Systems.tabs]. */
    data class System(val screen: CarSystem, val tab: Int = Systems.firstTab(screen)) : ScannerRoute
}

/**
 * Where the driver has been inside the section, the autoscan at the bottom: a row, a tile or a box
 * puts its screen on top, Back and «‹ …» take the top one off, so a module opened from the
 * topology goes back to the topology and one opened from «Электропривод» goes back there. A tab is
 * not a step: switching it changes the top screen in place.
 */
internal object ScannerStack {
    const val BACK_TO_SCAN = "‹ Автоскан"

    fun open(stack: List<ScannerRoute>, target: Target): List<ScannerRoute> = stack + when (target) {
        is Target.Module -> ScannerRoute.Module(target.request)
        is Target.System -> ScannerRoute.System(target.screen)
    }

    /** One step back; null when the autoscan itself is left, which closes the section. */
    fun back(stack: List<ScannerRoute>): List<ScannerRoute>? = if (stack.size > 1) stack.dropLast(1) else null

    /** The crumb «Автоскан»: everything over the autoscan goes, its own tab stays. */
    fun toScan(stack: List<ScannerRoute>): List<ScannerRoute> = stack.take(1)

    /** Down to the screen at [index] of the crumbs. */
    fun toLevel(stack: List<ScannerRoute>, index: Int): List<ScannerRoute> = stack.take(index + 1)

    fun retab(stack: List<ScannerRoute>, route: ScannerRoute): List<ScannerRoute> = stack.dropLast(1) + route

    /** The name of a screen in the crumbs and on «‹ …»: the module's or the system's. */
    fun title(route: ScannerRoute, moduleName: (Int) -> String?): String = when (route) {
        is ScannerRoute.Scan -> "Автоскан"
        is ScannerRoute.Module -> moduleName(route.request) ?: "Блок"
        is ScannerRoute.System -> Systems.crumb(route.screen)
    }

    fun backLabel(stack: List<ScannerRoute>, moduleName: (Int) -> String?): String =
        stack.getOrNull(stack.size - 2)?.let { "‹ " + title(it, moduleName) } ?: BACK_TO_SCAN
}

/**
 * «Сервис → Диагностика», the scanner (architecture, 7): the autoscan, an ECU's screen and the
 * system screens, laid out from the Luminofor `scanner-*` boards. Everything shown comes from
 * [model]; everything asked goes to [actions]. Inside the section a row, a tile or a box leads by
 * its [Target]; Back and «‹ …» return to the screen it was opened from ([ScannerStack]), a crumb to its screen, and the cross or Back
 * there leaves through [onClose].
 *
 * [start] and [sample] are for the debug fixtures: the board's screen, and the scale its live
 * traces are already in.
 */
@Composable
internal fun DiagnosticsScreen(
    model: DiagnosticsModel,
    actions: DiagnosticsActions,
    onClose: () -> Unit,
    start: ScannerRoute = ScannerRoute.Scan(),
    sample: ScannerSample? = null,
) {
    // the autoscan at the bottom; a fixture that opens on another screen has the autoscan under it
    var stack by remember { mutableStateOf(if (start is ScannerRoute.Scan) listOf(start) else listOf(ScannerRoute.Scan(), start)) }
    var onlyDtc by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val route = stack.last()
    val toScan = { stack = ScannerStack.toScan(stack) }
    val back = { ScannerStack.back(stack)?.let { stack = it } ?: onClose() }
    val open = { t: Target -> stack = ScannerStack.open(stack, t) }
    val retab = { next: ScannerRoute -> stack = ScannerStack.retab(stack, next) }
    val moduleName = { request: Int -> moduleOf(model, request)?.name }
    val backLabel = ScannerStack.backLabel(stack, moduleName)
    BackHandler { back() }

    // An ECU's screen asks for its identification and readiness once while open; the stream runs
    // only while «Потоковые данные» is in front.
    val current = rememberUpdatedState(actions)
    val r = route
    if (r is ScannerRoute.Module) {
        DisposableEffect(r.request) {
            current.value.setModuleOpen(r.request, true)
            onDispose { current.value.setModuleOpen(r.request, false) }
        }
        val live = model.modules[r.request]?.obd != null && r.tab == 1
        DisposableEffect(r.request, live) {
            if (live) current.value.setLiveVisible(r.request, true)
            onDispose { if (live) current.value.setLiveVisible(r.request, false) }
        }
    }

    val link = ScannerWords.link(model.header.link)
    // the crumbs are the stack: each one but the last takes the screens above it off
    val header: @Composable () -> Unit = {
        val crumbs = buildList {
            add(Crumb("Диагностика") { toScan() })
            stack.forEachIndexed { i, step ->
                val here = i == stack.lastIndex
                add(Crumb(ScannerStack.title(step, moduleName), if (here) null else ({ stack = ScannerStack.toLevel(stack, i) })))
            }
        }
        ScanHeader(crumbs, link.text, link.dot, link.glow, model.header.car, model.header.clock, onClose)
    }

    when (r) {
        is ScannerRoute.Scan -> if (r.topology) {
            TopologyPage(model, header, { retab(ScannerRoute.Scan(it == 1)) }, open, actions::rescan)
        } else {
            ScanListPage(model, header, { retab(ScannerRoute.Scan(it == 1)) }, listState, onlyDtc, { onlyDtc = it }, open, actions::rescan)
        }
        is ScannerRoute.Module -> {
            val module = moduleOf(model, r.request)
            if (module == null) {
                LaunchedEffect(r) { back() }
            } else {
                ModulePage(module, r.tab, header, { retab(r.copy(tab = it)) }, back, actions, sample, backLabel)
            }
        }
        is ScannerRoute.System -> SystemPage(
            model, r.screen, r.tab, header, { retab(r.copy(tab = it)) }, back, open, actions, backLabel,
        ) { moduleOf(model, it) }
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
        ident = IdentView(emptyList(), emptyList(), matched = null, pids = emptyList()),
        obd = null,
    )
}
