package dev.denza.apps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.BuildConfig
import dev.denza.apps.DenzaUiState
import dev.denza.apps.TechnicalReadings
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.ui.components.DenzaChoiceGroup
import dev.denza.apps.ui.components.DenzaChoiceRow
import dev.denza.apps.ui.components.DenzaChosenRow
import dev.denza.apps.ui.components.DenzaInfoRow
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPairRow
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetFootnote
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.components.DenzaStatusLine
import dev.denza.apps.ui.components.DenzaTileTone
import dev.denza.apps.ui.dashboard.DashboardTile
import dev.denza.apps.ui.dashboard.DashboardTiles
import dev.denza.apps.ui.dashboard.TileId

/** Where in the service panel the driver is: the answer, or one of the two pages behind it. */
internal enum class ServicePage { MAIN, SCREEN, TECHNICAL }

/**
 * What the service panel says, decided without Compose so it can be held to its cases.
 *
 * The panel is opened with one question - what is wrong - and it answers only that. A healthy car
 * is two quiet rows. A feature that needs somebody is a row in its tile's own colour and words that
 * opens its panel. The car's access is one row, and grows its buttons only when there is no access
 * to have: a check on a car that trusts us is a button with nothing to do.
 */
internal data class ServiceModel(
    /** The panel's status line, or null on a healthy car. */
    val status: String?,
    val statusTone: DenzaTileTone?,
    /** The tiles that need somebody, as the dashboard shows them. */
    val trouble: List<DashboardTile>,
    /**
     * Whether to say that everything works: nothing needs somebody and the car lets us in. Without
     * access that would be the one false line on the panel - most features cannot run at all.
     */
    val allWorking: Boolean,
    /** The access row's colour: the car's red when there is no access, else the summary grey. */
    val accessTone: DenzaTileTone?,
    /** Whether the access row brings its note and its buttons. */
    val accessActions: Boolean,
    /** A check or a request is under way: the buttons wait for it. */
    val accessBusy: Boolean,
) {
    companion object {
        fun of(state: DenzaUiState): ServiceModel {
            val tiles = DashboardTiles.of(state)
            val trouble = DashboardTiles.attentionTiles(state)
            val phase = state.adbRescue.phase
            val busy = phase == AdbRescuePhase.CHECKING || phase == AdbRescuePhase.REQUESTING
            // Not yet checked is not missing: the app asks at start, and the answer is seconds away.
            val missing = phase != AdbRescuePhase.TRUSTED && phase != AdbRescuePhase.UNKNOWN && !busy
            val (status, tone) = when {
                missing -> NO_ACCESS to DenzaTileTone.BROKEN
                // The service tile's own words: the count the panel's rows add up to.
                trouble.isNotEmpty() -> tiles.first { it.id == TileId.SERVICE }.state to DenzaTileTone.ATTENTION
                else -> null to null
            }
            return ServiceModel(
                status = status,
                statusTone = tone,
                trouble = trouble,
                allWorking = trouble.isEmpty() && !missing,
                accessTone = if (missing) DenzaTileTone.BROKEN else null,
                accessActions = phase != AdbRescuePhase.TRUSTED,
                accessBusy = busy,
            )
        }

        const val NO_ACCESS = "Нет доступа к машине, без него большинство функций не работает"
    }
}

/**
 * «Сервис»: what is wrong, the car's access, and - a row away each - the instruments' screen and
 * the technical readings. Both the tile's press and its long press open it.
 *
 * It was two panels deep and four buttons wide: the long press opened a panel with one sentence
 * and a blue «Открыть сервис», which opened this, which led with «Проверить доступ» on a car that
 * trusted us, a button for every screen the instruments could go to, and «Показать» over forty
 * readings. What the driver opens it for is now all there is on it; the rest is a page.
 *
 * [firstPage] opens it on a page - the debug build's boards of the two pages - and [version] is the
 * foot's words, the build's own unless a board says otherwise.
 */
@Composable
internal fun ServicePanel(
    state: DenzaUiState,
    compactLayout: Boolean,
    onOpenFeature: (TileId) -> Unit,
    onSelectClusterDisplay: (Int?) -> Unit,
    onCheckAdbAccess: () -> Unit,
    onRequestAdbAuthorizationOnce: () -> Unit,
    onAllowNewAdbAuthorizationAttempt: () -> Unit,
    onDismiss: () -> Unit,
    firstPage: ServicePage = ServicePage.MAIN,
    version: String = "Denza Apps ${BuildConfig.VERSION_NAME} · сборка ${BuildConfig.VERSION_CODE}",
) {
    var page by rememberSaveable { mutableStateOf(firstPage) }
    val back = { page = ServicePage.MAIN }
    DenzaSheet(
        onDismiss = { if (page == ServicePage.MAIN) onDismiss() else back() },
        compact = compactLayout,
        footer = { if (page == ServicePage.MAIN) DenzaSheetFootnote(version) },
    ) {
        when (page) {
            ServicePage.MAIN -> {
                DenzaSheetHeader(title = "Сервис", subtitle = "", onDismiss = onDismiss, glyph = DenzaIcons.ServiceGlyph)
                ServiceMain(
                    state = state,
                    onOpenFeature = onOpenFeature,
                    onCheckAdbAccess = onCheckAdbAccess,
                    onRequestAdbAuthorizationOnce = onRequestAdbAuthorizationOnce,
                    onAllowNewAdbAuthorizationAttempt = onAllowNewAdbAuthorizationAttempt,
                    onPage = { page = it },
                )
            }
            ServicePage.SCREEN -> {
                DenzaSheetHeader(title = "Приборный экран", subtitle = "", onDismiss = onDismiss, onBack = back)
                ServiceScreenPage(state) { id ->
                    onSelectClusterDisplay(id)
                    back()
                }
            }
            ServicePage.TECHNICAL -> {
                DenzaSheetHeader(title = "Технические сведения", subtitle = version, onDismiss = onDismiss, onBack = back)
                ServiceTechnicalPage(state.technicalDetails)
            }
        }
    }
}

@Composable
private fun ServiceMain(
    state: DenzaUiState,
    onOpenFeature: (TileId) -> Unit,
    onCheckAdbAccess: () -> Unit,
    onRequestAdbAuthorizationOnce: () -> Unit,
    onAllowNewAdbAuthorizationAttempt: () -> Unit,
    onPage: (ServicePage) -> Unit,
) {
    val model = remember(state) { ServiceModel.of(state) }
    val adb = state.adbRescue
    model.status?.let { DenzaStatusLine(it, model.statusTone ?: DenzaTileTone.IDLE) }
    // One plate: what needs somebody - or the line that nothing does - and the access under it.
    val rows: List<Any> = model.trouble + (if (model.allWorking) listOf(ALL_WORKING) else emptyList()) + ACCESS
    DenzaChoiceGroup(rows) { row ->
        when (row) {
            is DashboardTile -> DenzaChoiceRow(
                title = row.name,
                value = row.state,
                onClick = { onOpenFeature(row.id) },
                tone = row.tone,
            )
            ALL_WORKING -> DenzaInfoRow(title = "Все функции работают")
            else -> DenzaInfoRow(title = "Доступ к машине", summary = adb.message, tone = model.accessTone)
        }
    }
    if (model.accessActions) {
        adb.details?.let { DenzaNote(it) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BUTTON_GAP.dp)) {
            if (adb.canRequest) {
                DenzaPrimaryButton(
                    text = "Отправить один запрос",
                    onClick = onRequestAdbAuthorizationOnce,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !model.accessBusy,
                )
            }
            DenzaSecondaryButton(
                text = "Проверить доступ",
                onClick = onCheckAdbAccess,
                modifier = Modifier.fillMaxWidth(),
                enabled = !model.accessBusy,
            )
            if (adb.canResetAttempt) {
                DenzaSecondaryButton(
                    text = "Разрешить новую попытку",
                    onClick = onAllowNewAdbAuthorizationAttempt,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !model.accessBusy,
                )
            }
        }
    }
    DenzaChoiceGroup(listOf(ServicePage.SCREEN, ServicePage.TECHNICAL)) { target ->
        when (target) {
            ServicePage.SCREEN -> DenzaChoiceRow(
                title = "Приборный экран",
                value = state.clusterDisplayLabel,
                onClick = { onPage(target) },
            )
            else -> DenzaChoiceRow(
                title = "Технические сведения",
                value = "Версия, прошивка, состояние функций",
                onClick = { onPage(target) },
            )
        }
    }
}

/**
 * Which screen the instruments go to. The app finds it by itself; this is for the car where it
 * found the wrong one. One answer at a time, so the tap that chooses is the tap that returns.
 */
@Composable
private fun ServiceScreenPage(state: DenzaUiState, onSelect: (Int?) -> Unit) {
    val choices = ClusterDisplayResolver.choices(state.clusterCandidates)
    val automatic = state.clusterDisplayOverride == null
    DenzaChoiceGroup(listOf<Int?>(null) + choices.indices) { index ->
        if (index == null) {
            DenzaChosenRow(
                title = "Определять автоматически",
                chosen = automatic,
                summary = state.clusterDisplayAutomatic?.takeIf { automatic }?.let { "Сейчас: $it" },
                onClick = { onSelect(null) },
            )
        } else {
            val display = choices[index]
            DenzaChosenRow(
                title = ClusterDisplayResolver.choiceName(index, display),
                chosen = state.clusterDisplayOverride == display.id,
                onClick = { onSelect(display.id) },
            )
        }
    }
    DenzaNote("Приложение само находит экран за рулём. Выберите другой, если приборы ушли не туда.")
}

/** The report, one section a feature, one reading a row - the cloud first. */
@Composable
private fun ServiceTechnicalPage(report: String) {
    val sections = remember(report) { TechnicalReadings.parse(report) }
    sections.forEach { section ->
        val plate: @Composable () -> Unit = {
            DenzaChoiceGroup(section.rows) { row -> DenzaPairRow(row.key, row.value) }
        }
        val title = section.title
        if (title == null) plate() else DenzaSection(title) { plate() }
    }
}

private const val ALL_WORKING = "all-working"
private const val ACCESS = "access"

/** Between the access buttons, as the board's stack draws them. */
private const val BUTTON_GAP = 12f
