package dev.denza.apps.feature.diagnostics

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.denza.apps.feature.vehicle.VehicleSession
import dev.denza.apps.feature.vehicle.VehicleWatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun VehicleDiagnosticsWindow(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = remember { VehicleDiagnostics.of(context) }
    val hub = remember { VehicleSession.hub(context) }
    val status by diagnostics.state.collectAsState()
    val cache = remember { DiagnosticTelemetryCache() }
    val builder = remember { DiagnosticFrameBuilder() }
    var telemetry by remember { mutableStateOf(cache.read(hub.snapshot, SystemClock.elapsedRealtime())) }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        started = true
        hub.setActive(VehicleWatcher.DIAGNOSTICS, true)
        diagnostics.start()
        onStopOrDispose {
            started = false
            diagnostics.stop()
            hub.setActive(VehicleWatcher.DIAGNOSTICS, false)
        }
    }
    LaunchedEffect(started) {
        if (started) while (isActive) {
            elapsed = SystemClock.elapsedRealtime()
            telemetry = cache.read(hub.snapshot, elapsed)
            delay(250)
        }
    }
    val frame = remember(telemetry, status, elapsed) { builder.build(telemetry, status, elapsed) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DiagnosticSurface(frame, Modifier.fillMaxSize(), onDismiss, { diagnostics.read(it) }, { diagnostics.refresh() })
    }
}

/** Also used by the debug fixture activity. Only callers provide data; this surface never reads a car. */
@Composable
internal fun DiagnosticSurface(
    frame: DiagnosticFrame, modifier: Modifier = Modifier, onClose: () -> Unit = {}, onRead: (DtcEcu) -> Unit = {}, onLamps: () -> Unit = {},
    initialTab: String = "overview", initialNode: String? = null, initialCode: Int = 0, initialDetail: Boolean = false,
    initialStatusDetail: Boolean = false, initialPage: Int = 0,
) {
    val context = LocalContext.current
    val renderer = remember { DiagnosticRenderer(context) }
    var tab by remember { mutableStateOf(initialTab) }
    var selected by remember { mutableStateOf(initialNode ?: renderer.defaultNode(initialTab)) }
    var page by remember { mutableIntStateOf(initialPage) }
    var code by remember { mutableIntStateOf(initialCode) }
    var detail by remember { mutableStateOf(initialDetail) }
    var statusDetail by remember { mutableStateOf(initialStatusDetail) }
    val selectedFrame = frame.forEcu(DtcEcu.forNode(selected) ?: DtcEcu.BODY)
    var previousRecords by remember { mutableStateOf(selectedFrame.records) }
    LaunchedEffect(selectedFrame.records) {
        if (previousRecords != selectedFrame.records) { page = 0; code = 0; detail = false; statusDetail = false; previousRecords = selectedFrame.records }
    }
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.background(Color(0xFF08090B))) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val scale = min(w/1024f, h/608f)
        val ox = (w-1024*scale)/2; val oy = (h-608*scale)/2
        Canvas(Modifier.fillMaxSize()) { renderer.draw(drawContext.canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), selectedFrame, tab, selected, page, code, detail, statusDetail) }
        renderer.hits(tab, selected, page, selectedFrame, detail, statusDetail).forEach { hit ->
            Box(Modifier.offset { IntOffset((ox+hit.x*scale).roundToInt(), (oy+hit.y*scale).roundToInt()) }
                .size((hit.w*scale/density).dp, (hit.h*scale/density).dp)
                .semantics { contentDescription = hit.label }
                .clickable(role = Role.Button) {
                    when {
                        hit.id == "close" -> onClose()
                        hit.id == "read" -> DtcEcu.forNode(selected)?.let(onRead)
                        hit.id == "coverage" -> { tab = "dtc"; selected = "unread"; page = 0; detail = false; statusDetail = false }
                        hit.id == "list" -> { detail = false; statusDetail = false }
                        hit.id == "status" -> { statusDetail = !statusDetail }
                        hit.id == "lamps" -> onLamps()
                        hit.id == "prev" -> { page--; code = page * 5 }
                        hit.id == "next" -> { page++; code = page * 5 }
                        hit.id.startsWith("tab:") -> { tab = hit.id.substringAfter(':'); selected = renderer.defaultNode(tab); page = 0; code = 0; detail = false; statusDetail = false }
                        hit.id.startsWith("node:") -> { selected = hit.id.substringAfter(':'); page = 0; code = 0; detail = false; statusDetail = false }
                        hit.id.startsWith("code:") -> { code = hit.id.substringAfter(':').toInt(); detail = true; statusDetail = false }
                    }
                })
        }
    }
}
