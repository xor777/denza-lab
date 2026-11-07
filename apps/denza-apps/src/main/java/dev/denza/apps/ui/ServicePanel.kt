package dev.denza.apps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.R
import dev.denza.apps.core.AppLanguage
import dev.denza.apps.core.AppText
import dev.denza.apps.BuildConfig
import dev.denza.apps.DenzaUiState
import dev.denza.apps.SupportDiagnostics
import dev.denza.apps.TechnicalReadings
import dev.denza.apps.TechnicalSection
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbRestoreReport
import dev.denza.apps.feature.adb.AdbRestoreSnapshot
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.network.TrafficMonitorSnapshot
import dev.denza.apps.feature.network.TrafficMonitorText
import dev.denza.apps.feature.warning.WarningText
import dev.denza.apps.ui.components.DenzaChoiceGroup
import dev.denza.apps.ui.components.DenzaChoiceRow
import dev.denza.apps.ui.components.DenzaChosenRow
import dev.denza.apps.ui.components.DenzaInfoRow
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPairRow
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSegmentedRow
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetFootnote
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.components.DenzaStatusLine
import dev.denza.apps.ui.components.DenzaSwitchRow
import dev.denza.apps.ui.components.DenzaTileTone
import dev.denza.apps.ui.dashboard.DashboardTile
import dev.denza.apps.ui.dashboard.DashboardTiles
import dev.denza.apps.ui.dashboard.TileId

/**
 * Where in the service panel the driver is: the answer, one of the two pages behind it, or the
 * split's journal behind the technical page.
 */
internal enum class ServicePage {
    MAIN,
    SCREEN,
    TECHNICAL,
    JOURNAL,
    RESTORE,
    MOBILE_INTERNET,
    WARNINGS,
    DIAGNOSTICS,
    ;

    /** The page the header's way back returns to. */
    val parent: ServicePage get() = if (this == JOURNAL) TECHNICAL else MAIN
}

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

        val NO_ACCESS: String get() = AppText.s(R.string.service_no_access)
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
 * [firstPage] opens it on a page - the debug build's boards of the pages - [firstPageAtEnd] scrolled
 * to that page's end (the board of the technical page's split section), and [version] is the
 * foot's words, the build's own unless a board says otherwise. [nowMs] is the clock the restore's
 * times are said from («сегодня в 08:11»), a board's own on a board.
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
    firstPageAtEnd: Boolean = false,
    version: String = AppText.s(R.string.service_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    onSetAdbRestoreEnabled: (Boolean) -> Unit = {},
    onOpenService: () -> Unit = {},
    nowMs: () -> Long = System::currentTimeMillis,
    trafficMonitorFixture: TrafficMonitorSnapshot? = null,
    warningsFixture: WarningsView? = null,
    onSelectLanguage: (AppLanguage) -> Unit = {},
) {
    LaunchedEffect(Unit) { onOpenService() }
    val warnings = warningsFixture ?: rememberWarnings()
    var page by rememberSaveable { mutableStateOf(firstPage) }
    val back = { page = page.parent }
    // One place a page: the journal is opened from the bottom of a long report, and the report has
    // to come back at its split section - and the journal must not open at the report's offset.
    val scrolls = ServicePage.entries.associateWith { each ->
        rememberScrollState(if (each == firstPage && firstPageAtEnd) Int.MAX_VALUE else 0)
    }
    if (page == ServicePage.DIAGNOSTICS) {
        dev.denza.apps.feature.diagnostics.ui.DiagnosticsSection { page = ServicePage.MAIN }
        return
    }
    DenzaSheet(
        onDismiss = { if (page == ServicePage.MAIN) onDismiss() else back() },
        compact = compactLayout,
        scrollState = scrolls.getValue(page),
        footer = { if (page == ServicePage.MAIN) DenzaSheetFootnote(version) },
    ) {
        when (page) {
            ServicePage.DIAGNOSTICS -> Unit
            ServicePage.MAIN -> {
                DenzaSheetHeader(title = AppText.s(R.string.tile_service_name), subtitle = "", onDismiss = onDismiss, glyph = DenzaIcons.ServiceGlyph)
                ServiceMain(
                    state = state,
                    onOpenFeature = onOpenFeature,
                    onCheckAdbAccess = onCheckAdbAccess,
                    onRequestAdbAuthorizationOnce = onRequestAdbAuthorizationOnce,
                    onAllowNewAdbAuthorizationAttempt = onAllowNewAdbAuthorizationAttempt,
                    onPage = { page = it },
                    nowMs = nowMs,
                    warnings = warnings,
                    onSelectLanguage = onSelectLanguage,
                )
            }
            ServicePage.SCREEN -> {
                DenzaSheetHeader(title = AppText.s(R.string.cluster_display_title), subtitle = "", onDismiss = onDismiss, onBack = back)
                ServiceScreenPage(state) { id ->
                    onSelectClusterDisplay(id)
                    back()
                }
            }
            ServicePage.TECHNICAL -> {
                DenzaSheetHeader(title = AppText.s(R.string.service_technical), subtitle = version, onDismiss = onDismiss, onBack = back)
                ServiceTechnicalPage(state.technicalDetails) { page = ServicePage.JOURNAL }
            }
            ServicePage.JOURNAL -> {
                // Whose journal the page is: the photo of it has to say so without the page before it.
                DenzaSheetHeader(title = AppText.s(R.string.service_journal), subtitle = AppText.s(R.string.service_split_section), onDismiss = onDismiss, onBack = back)
                ReadingsPage(state.splitJournal)
            }
            ServicePage.RESTORE -> {
                DenzaSheetHeader(title = AdbRestoreReport.TITLE, subtitle = "", onDismiss = onDismiss, onBack = back)
                ServiceRestorePage(state.adbRestore, nowMs(), onSetAdbRestoreEnabled)
            }
            ServicePage.MOBILE_INTERNET -> {
                DenzaSheetHeader(title = TrafficMonitorText.TITLE, subtitle = "", onDismiss = onDismiss, onBack = back)
                ServiceMobileInternetPage(trafficMonitorFixture)
            }
            ServicePage.WARNINGS -> {
                DenzaSheetHeader(title = WarningText.TITLE, subtitle = "", onDismiss = onDismiss, onBack = back)
                ServiceWarningsPage(warnings)
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
    nowMs: () -> Long,
    warnings: WarningsView,
    onSelectLanguage: (AppLanguage) -> Unit,
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
            ALL_WORKING -> DenzaInfoRow(title = AppText.s(R.string.service_all_working))
            else -> DenzaInfoRow(title = AppText.s(R.string.service_access), summary = adb.message, tone = model.accessTone)
        }
    }
    if (model.accessActions) {
        adb.details?.let { DenzaNote(it) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BUTTON_GAP.dp)) {
            if (adb.canRequest) {
                DenzaPrimaryButton(
                    text = AppText.s(R.string.adb_request_once),
                    onClick = onRequestAdbAuthorizationOnce,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !model.accessBusy,
                )
            }
            DenzaSecondaryButton(
                text = AppText.s(R.string.adb_check_access),
                onClick = onCheckAdbAccess,
                modifier = Modifier.fillMaxWidth(),
                enabled = !model.accessBusy,
            )
            if (adb.canResetAttempt) {
                DenzaSecondaryButton(
                    text = AppText.s(R.string.adb_allow_new_attempt),
                    onClick = onAllowNewAdbAuthorizationAttempt,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !model.accessBusy,
                )
            }
        }
    }
    LanguageRow(onSelectLanguage)
    // The diagnostic section is parked outside a `-Pdiagnostics` build (build.gradle.kts) until the
    // owner accepts it on the car (docs/vehicle-diagnostics-architecture.md, 1).
    val pages = listOfNotNull(
        ServicePage.DIAGNOSTICS.takeIf { BuildConfig.DIAGNOSTICS },
        ServicePage.RESTORE, ServicePage.MOBILE_INTERNET, ServicePage.WARNINGS, ServicePage.SCREEN,
        ServicePage.TECHNICAL,
    )
    DenzaChoiceGroup(pages) { target ->
        when (target) {
            ServicePage.DIAGNOSTICS -> DenzaChoiceRow(
                title = AppText.s(R.string.service_diagnostics),
                value = AppText.s(R.string.service_diagnostics_value),
                onClick = { onPage(target) },
            )
            ServicePage.RESTORE -> {
                val restore = AdbRestoreReport.summary(state.adbRestore, nowMs())
                DenzaChoiceRow(
                    title = AdbRestoreReport.TITLE,
                    value = restore.text,
                    onClick = { onPage(target) },
                    tone = restore.tone.tile,
                )
            }
            ServicePage.WARNINGS -> DenzaChoiceRow(
                title = WarningText.TITLE,
                value = WarningText.row(warnings.state, warnings.nowMs, warnings.zone),
                onClick = { onPage(target) },
            )
            ServicePage.SCREEN -> DenzaChoiceRow(
                title = AppText.s(R.string.cluster_display_title),
                value = state.clusterDisplayLabel,
                onClick = { onPage(target) },
            )
            ServicePage.MOBILE_INTERNET -> DenzaChoiceRow(
                title = TrafficMonitorText.TITLE,
                value = TrafficMonitorText.MONITOR,
                onClick = { onPage(target) },
            )
            else -> DenzaChoiceRow(
                title = AppText.s(R.string.service_technical),
                value = AppText.s(R.string.service_technical_value),
                onClick = { onPage(target) },
            )
        }
    }
}

/**
 * The app's language, not the car's. A switch could not say which language «on» means, so this is the
 * tab row: both names are always there, and the title and the names read the same in either language
 * for someone who cannot read the rest of the panel. It selects what the process speaks and nothing
 * else, so a write that failed leaves it as it was; a language that took restarts the app. It stands
 * above the pages, where it is on the first screen without scrolling.
 */
@Composable
private fun LanguageRow(onSelect: (AppLanguage) -> Unit) {
    DenzaSection(AppText.s(R.string.service_language_title)) {
        DenzaSegmentedRow(
            labels = listOf(AppText.s(R.string.service_language_russian), AppText.s(R.string.service_language_english)),
            selectedIndex = if (AppText.language == AppLanguage.RUSSIAN) 0 else 1,
            onSelect = { index -> onSelect(if (index == 0) AppLanguage.RUSSIAN else AppLanguage.ENGLISH) },
            modifier = Modifier.fillMaxWidth(),
        )
        DenzaNote(AppText.s(R.string.service_language_note))
    }
}

/**
 * «Восстановление ADB»: whether there is access, the switch, and what the last boot did to the port
 * and the restore about it - the answer first, in the words of [AdbRestoreReport].
 */
@Composable
private fun ServiceRestorePage(restore: AdbRestoreSnapshot, nowMs: Long, onSetEnabled: (Boolean) -> Unit) {
    AdbRestoreReport.status(restore)?.let { DenzaStatusLine(it.text, it.tone.tile ?: DenzaTileTone.IDLE) }
    DenzaSwitchRow(
        title = AdbRestoreReport.SWITCH,
        subtitle = AdbRestoreReport.SWITCH_SUMMARY,
        checked = restore.enabled,
        onCheckedChange = onSetEnabled,
    )
    val boot = AdbRestoreReport.bootRows(restore, nowMs)
    if (boot.isNotEmpty()) {
        DenzaSection(AdbRestoreReport.BOOT_SECTION) {
            DenzaChoiceGroup(boot) { (key, value) -> DenzaPairRow(key, value) }
        }
    }
}

/** The restore's tone as the tiles' colours: quiet is the summary grey. */
private val AdbRestoreReport.Tone.tile: DenzaTileTone?
    get() = when (this) {
        AdbRestoreReport.Tone.QUIET -> null
        AdbRestoreReport.Tone.ATTENTION -> DenzaTileTone.ATTENTION
        AdbRestoreReport.Tone.BROKEN -> DenzaTileTone.BROKEN
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
                title = AppText.s(R.string.cluster_display_detect_automatically),
                chosen = automatic,
                summary = state.clusterDisplayAutomatic?.takeIf { automatic }?.let { AppText.s(R.string.service_cluster_automatic_now, it) },
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
    DenzaNote(AppText.s(R.string.service_cluster_note))
}

/**
 * The report, one section a feature, one reading a row - the cloud first - and under the split's
 * section the way to its journal.
 */
@Composable
private fun ServiceTechnicalPage(report: String, onJournal: () -> Unit) {
    ReadingsPage(report) { section ->
        if (section.id == SupportDiagnostics.SPLIT_SECTION) {
            // A plate of its own under the readings, a label's gap away, as the main page keeps its
            // two pages' rows apart from what it reports: this one is pressed, those are read.
            DenzaChoiceGroup(listOf(JOURNAL_ROW)) {
                DenzaChoiceRow(title = AppText.s(R.string.service_journal), value = AppText.s(R.string.service_journal_value), onClick = onJournal)
            }
        }
    }
}

/**
 * A page of readings in the report's format ([TechnicalReadings]): a plate of dense key-value rows
 * a section, under the section's words. The technical page and the split's journal are both this,
 * so the journal speaks in no new visual language. [under] adds what belongs beneath a section.
 */
@Composable
private fun ReadingsPage(report: String, under: @Composable (TechnicalSection) -> Unit = {}) {
    val sections = remember(report) { TechnicalReadings.parse(report) }
    sections.forEach { section ->
        val plate: @Composable () -> Unit = {
            DenzaChoiceGroup(section.rows) { row -> DenzaPairRow(row.key, row.value) }
        }
        val title = section.title
        if (title == null) {
            plate()
        } else {
            DenzaSection(title) {
                plate()
                under(section)
            }
        }
    }
}

private const val ALL_WORKING = "all-working"
private const val ACCESS = "access"
private const val JOURNAL_ROW = "journal"

/** Between the access buttons, as the board's stack draws them. */
private const val BUTTON_GAP = 12f
