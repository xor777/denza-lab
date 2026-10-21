package dev.denza.apps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.warning.WarningLogState
import dev.denza.apps.feature.warning.WarningNames
import dev.denza.apps.feature.warning.WarningRecorder
import dev.denza.apps.feature.warning.WarningText
import dev.denza.apps.feature.warning.WarningTone
import dev.denza.apps.ui.components.DenzaChoiceGroup
import dev.denza.apps.ui.components.DenzaInfoRow
import dev.denza.apps.ui.components.DenzaPairRow
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaStatusLine
import dev.denza.apps.ui.components.DenzaSwitchRow
import dev.denza.apps.ui.components.DenzaTileTone
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * What the warnings page and its row on «Сервис» are drawn from: the recorder's record, the car's
 * names for its codes, and the clock its times are said from. A board hands in its own.
 */
internal data class WarningsView(
    val state: WarningLogState,
    val names: WarningNames,
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    val exported: String? = null,
    val onSetEnabled: (Boolean) -> Unit = {},
    val onExport: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/** The live record, for as long as «Сервис» stands; opening it reads the files and the names. */
@Composable
internal fun rememberWarnings(): WarningsView {
    val context = LocalContext.current
    val recorder = remember { WarningRecorder.of(context) }
    LaunchedEffect(recorder) { recorder.load() }
    val state by recorder.state.collectAsState()
    val names by recorder.names.collectAsState()
    val exported by recorder.exported.collectAsState()
    return WarningsView(
        state = state,
        names = names,
        nowMs = System.currentTimeMillis(),
        exported = exported,
        onSetEnabled = recorder::setEnabled,
        onExport = recorder::export,
        onClear = recorder::clear,
    )
}

/**
 * «Предупреждения машины»: whether the record runs and since when, the switch, every code seen with
 * when and how often, the record's two buttons, and its newest events.
 *
 * The codes lead because they are the answer - a `1DD` from three weeks ago is there after its
 * events have rotated out. «Очистить» throws the whole record away, codes included, so it asks for
 * a second press within a few seconds rather than a dialog.
 */
@Composable
internal fun ServiceWarningsPage(view: WarningsView) {
    val (status, tone) = WarningText.status(view.state, view.nowMs, view.zone)
    DenzaStatusLine(status, if (tone == WarningTone.ATTENTION) DenzaTileTone.ATTENTION else DenzaTileTone.IDLE)
    DenzaSwitchRow(
        title = WarningText.SWITCH,
        subtitle = WarningText.SWITCH_SUMMARY,
        checked = view.state.enabled,
        onCheckedChange = view.onSetEnabled,
    )
    DenzaSection(WarningText.CODES) {
        val sightings = view.state.sightings
        if (sightings.isEmpty()) {
            DenzaChoiceGroup(listOf(WarningText.NONE)) { DenzaInfoRow(title = it) }
        } else {
            DenzaChoiceGroup(sightings) { sighting ->
                DenzaInfoRow(
                    title = WarningText.sightingTitle(sighting, view.nowMs, view.zone),
                    summary = WarningText.sightingSummary(sighting, view.names),
                )
            }
        }
    }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(CLEAR_WINDOW_MS)
            armed = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BUTTON_GAP.dp)) {
        DenzaSecondaryButton(text = WarningText.EXPORT, onClick = view.onExport, modifier = Modifier.fillMaxWidth())
        DenzaSecondaryButton(
            text = if (armed) WarningText.CLEAR_AGAIN else WarningText.CLEAR,
            onClick = {
                if (armed) view.onClear()
                armed = !armed
            },
            modifier = Modifier.fillMaxWidth(),
            attention = armed,
        )
    }
    view.exported?.let { DenzaStatusLine(it, DenzaTileTone.IDLE) }
    val events = view.state.events
    if (events.isNotEmpty()) {
        DenzaSection(WarningText.EVENTS) {
            DenzaChoiceGroup(events) { event ->
                DenzaPairRow(WarningText.eventTime(event.atMs, view.zone), WarningText.eventText(event, view.names))
            }
        }
    }
}

/** Between the two buttons, as the board's stack draws them. */
private const val BUTTON_GAP = 12f

/** How long «Очистить» waits for its second press. */
private const val CLEAR_WINDOW_MS = 5_000L
