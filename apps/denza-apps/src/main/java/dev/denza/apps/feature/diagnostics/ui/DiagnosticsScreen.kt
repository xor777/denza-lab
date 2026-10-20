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
import dev.denza.apps.feature.diagnostics.scan.SystemScreen
import dev.denza.apps.feature.diagnostics.scan.Target

/** Where inside the section the driver is. */
internal sealed interface ScannerRoute {
    /** «Автоскан»: the list, or the same ECUs on the bus. */
    data class Scan(val topology: Boolean = false) : ScannerRoute

    /** One ECU's screen, at a tab of [moduleTabs]. */
    data class Module(val request: Int, val tab: Int = 0) : ScannerRoute

    /** A system screen, at a tab of [Systems.tabs]. */
    data class System(val screen: SystemScreen, val tab: Int = Systems.firstTab(screen)) : ScannerRoute
}

/**
 * «Сервис → Диагностика», the scanner (architecture, 7): the autoscan, an ECU's screen and the
 * system screens, laid out from the Luminofor `scanner-*` boards. Everything shown comes from
 * [model]; everything asked goes to [actions]. Inside the section a row, a tile or a box leads by
 * its [Target]; «‹ Автоскан», the crumb and Back return to the autoscan, and the cross or Back
 * there leaves through [onClose].
 *
 * [start] and [sample] are for the debug fixtures: the board's screen, and what a fixture says
 * that the session never would (its values are illustrative; its charts already scaled).
 */
@Composable
internal fun DiagnosticsScreen(
    model: DiagnosticsModel,
    actions: DiagnosticsActions,
    onClose: () -> Unit,
    start: ScannerRoute = ScannerRoute.Scan(),
    sample: ScannerSample? = null,
) {
    var route by remember { mutableStateOf(start) }
    var onlyDtc by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val toScan = { route = ScannerRoute.Scan((route as? ScannerRoute.Scan)?.topology ?: false) }
    val open = { t: Target ->
        route = when (t) {
            is Target.Module -> ScannerRoute.Module(t.request)
            is Target.System -> ScannerRoute.System(t.screen)
        }
    }
    BackHandler { if (route is ScannerRoute.Scan) onClose() else toScan() }

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
    val header: @Composable (String?) -> Unit = { title ->
        val crumbs = buildList {
            add(Crumb("Диагностика") { toScan() })
            add(Crumb("Автоскан", if (title != null) toScan else null))
            if (title != null) add(Crumb(title))
        }
        ScanHeader(crumbs, link.text, link.dot, link.glow, model.header.car, model.header.clock, onClose)
    }

    when (r) {
        is ScannerRoute.Scan -> if (r.topology) {
            TopologyPage(model, { header(null) }, { route = ScannerRoute.Scan(it == 1) }, open, actions::rescan)
        } else {
            ScanListPage(model, { header(null) }, { route = ScannerRoute.Scan(it == 1) }, listState, onlyDtc, { onlyDtc = it }, open, actions::rescan)
        }
        is ScannerRoute.Module -> {
            val module = moduleOf(model, r.request)
            if (module == null) {
                LaunchedEffect(r) { toScan() }
            } else {
                ModulePage(module, r.tab, { header(module.name) }, { route = r.copy(tab = it) }, toScan, actions, sample)
            }
        }
        is ScannerRoute.System -> SystemPage(
            model, r.screen, r.tab, { header(Systems.crumb(r.screen)) }, { route = r.copy(tab = it) }, toScan, open, actions,
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
