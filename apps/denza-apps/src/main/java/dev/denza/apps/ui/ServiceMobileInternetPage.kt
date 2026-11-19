package dev.denza.apps.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.denza.apps.feature.network.TrafficMonitor
import dev.denza.apps.feature.network.TrafficMonitorSnapshot
import dev.denza.apps.feature.network.TrafficMonitorState
import dev.denza.apps.feature.network.TrafficMonitorText
import dev.denza.apps.ui.components.DenzaChoiceGroup
import dev.denza.apps.ui.components.DenzaInfoRow
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaStatusLine
import dev.denza.apps.ui.components.DenzaTileTone

/**
 * The page's monitor and what can be done to it: read by the panel, because the page's body and the
 * panel's foot both draw from it - the row in the body, the button at the foot, where every panel
 * keeps its one action.
 */
internal class TrafficMonitorView(
    val snapshot: TrafficMonitorSnapshot,
    val onDisable: () -> Unit,
    val onRefresh: () -> Unit,
)

/** A supplied fixture never constructs the controller or reads a car. */
@Composable
internal fun rememberTrafficMonitor(fixture: TrafficMonitorSnapshot? = null): TrafficMonitorView {
    if (fixture != null) return TrafficMonitorView(fixture, {}, {})
    val context = LocalContext.current
    val controller = remember { TrafficMonitor.of(context) }
    val snapshot by controller.state.collectAsState()
    LifecycleStartEffect(controller) {
        controller.refresh()
        onStopOrDispose { /* An explicit operation finishes its readback even after closing. */ }
    }
    return TrafficMonitorView(snapshot, controller::disable, controller::refresh)
}

/**
 * The monitor's row and, under it, the one line an operation leaves. A monitor still on after the
 * press is the row's own state in amber - «Остался включён» - not a red sentence: the driver sees
 * what the monitor is, and the button at the foot is still there to try again.
 */
@Composable
internal fun ServiceMobileInternetPage(view: TrafficMonitorView) {
    val snapshot = view.snapshot
    DenzaChoiceGroup(listOf(snapshot.summary)) { summary ->
        DenzaInfoRow(
            title = TrafficMonitorText.MONITOR,
            summary = summary,
            tone = if (snapshot.stayedOn && !snapshot.busy) DenzaTileTone.ATTENTION else null,
        )
    }
    if (snapshot.error != null) DenzaStatusLine(snapshot.error, DenzaTileTone.BROKEN)
    else snapshot.result?.let { DenzaStatusLine(it, DenzaTileTone.IDLE) }
}

/** The page's button, at the panel's foot: switch the monitor off, or check it when unread. */
@Composable
internal fun ServiceMobileInternetFooter(view: TrafficMonitorView) {
    val snapshot = view.snapshot
    if (snapshot.known) {
        DenzaSecondaryButton(
            text = TrafficMonitorText.DISABLE,
            enabled = snapshot.canDisable,
            onClick = view.onDisable,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (snapshot.state == TrafficMonitorState.UNKNOWN && !snapshot.busy) {
        DenzaSecondaryButton(text = TrafficMonitorText.CHECK, onClick = view.onRefresh, modifier = Modifier.fillMaxWidth())
    }
}
