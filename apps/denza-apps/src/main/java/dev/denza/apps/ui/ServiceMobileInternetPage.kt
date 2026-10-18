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

/** A supplied fixture never constructs the controller or reads a car. */
@Composable
internal fun ServiceMobileInternetPage(fixture: TrafficMonitorSnapshot? = null) {
    if (fixture != null) {
        ServiceMobileInternetContent(fixture, {}, {})
    } else {
        val context = LocalContext.current
        val controller = remember { TrafficMonitor.of(context) }
        val snapshot by controller.state.collectAsState()
        LifecycleStartEffect(controller) {
            controller.refresh()
            onStopOrDispose { /* An explicit operation finishes its readback even after closing. */ }
        }
        ServiceMobileInternetContent(snapshot, controller::disable, controller::refresh)
    }
}

@Composable
private fun ServiceMobileInternetContent(
    snapshot: TrafficMonitorSnapshot,
    onDisable: () -> Unit,
    onRefresh: () -> Unit,
) {
    DenzaChoiceGroup(listOf(snapshot.summary)) { summary ->
        DenzaInfoRow(title = TrafficMonitorText.MONITOR, summary = summary)
    }
    if (snapshot.known) {
        DenzaSecondaryButton(
            text = TrafficMonitorText.DISABLE,
            enabled = snapshot.canDisable,
            onClick = onDisable,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (snapshot.state == TrafficMonitorState.UNKNOWN && !snapshot.busy) {
        DenzaSecondaryButton(text = TrafficMonitorText.CHECK, onClick = onRefresh, modifier = Modifier.fillMaxWidth())
    }
    if (snapshot.error != null) DenzaStatusLine(snapshot.error, DenzaTileTone.BROKEN)
    else snapshot.result?.let { DenzaStatusLine(it, DenzaTileTone.IDLE) }
}
