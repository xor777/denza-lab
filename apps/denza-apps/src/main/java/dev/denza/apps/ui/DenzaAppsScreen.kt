package dev.denza.apps.ui

import dev.denza.apps.ui.components.ParagraphText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.ui.components.glyphStroke
import dev.denza.apps.ui.components.WorkingRing
import dev.denza.apps.ui.components.NamedGlyph
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet
import dev.denza.apps.ui.components.SheetInk
import dev.denza.apps.DenzaUiState
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.DenzaTheme
import dev.denza.apps.feature.adb.AdbExplainer
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbStartupGatePolicy
import dev.denza.apps.feature.adb.AdbStartupOverlayModel
import dev.denza.apps.feature.adb.AdbStartupPrimaryAction
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.ui.components.DenzaModalCard
import dev.denza.apps.ui.components.DenzaModalDialog
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.dashboard.DashboardActions
import dev.denza.apps.ui.dashboard.DashboardPress
import dev.denza.apps.ui.dashboard.DashboardTiles
import dev.denza.apps.ui.dashboard.DefaultAppsSheet
import dev.denza.apps.ui.dashboard.FeatureSheet
import dev.denza.apps.ui.dashboard.TileId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
fun DenzaAppsRoot(
    state: StateFlow<DenzaUiState>,
    onToggleSimulcast: (Boolean) -> Unit,
    onLaunchSimulcast: () -> Unit,
    onRepairSimulcast: () -> Unit,
    onToggleMirrors: (Boolean) -> Unit,
    onMirrorsPosition: (MirrorsPosition) -> Unit,
    onMirrorsProcessing: (Boolean) -> Unit,
    onPreviewMirrors: () -> Unit,
    onNavigationAction: () -> Unit,
    onNavigationSteeringWheelButton: (Boolean) -> Unit,
    onNavigationPlacement: (ClusterMapPlacement) -> Unit,
    onChooseNavigationApp: () -> Unit,
    onLoadNavigationAppChoices: () -> Unit,
    onCloseNavigationPicker: () -> Unit,
    onSelectNavigationApp: (String) -> Unit,
    onToggleSplitScreen: (Boolean) -> Unit,
    onLaunchSplitScreen: () -> Unit,
    onSetWeatherEnabled: (Boolean) -> Unit,
    onToggleHudGuidance: (Boolean) -> Unit,
    onToggleSpeakerCovers: (Boolean) -> Unit,
    onRaiseSpeakerCovers: () -> Unit,
    onToggleCloudLink: (Boolean) -> Unit,
    onSetCloudWifiRetained: (Boolean) -> Unit,
    onSelectClusterDisplay: (Int?) -> Unit,
    onRefreshScreenDiagnostics: () -> Unit,
    onCheckAdbAccess: () -> Unit,
    onRequestAdbAuthorizationOnce: () -> Unit,
    onAllowNewAdbAuthorizationAttempt: () -> Unit,
    onRefreshSystemLanguage: () -> Unit,
    onOpenSystemLanguage: () -> Unit,
    onChooseApps: () -> Unit,
    onLoadAppChoices: () -> Unit,
    onCloseAppPicker: () -> Unit,
    onToggleApp: (String) -> Unit,
    onRefreshDefaultApps: (Boolean) -> Unit,
    onSetDefaultAppsEnabled: (Boolean) -> Unit,
    onSelectDefaultApp: (DefaultAppRole, String) -> Unit,
    onChooseFseApp: () -> Unit,
    onCloseFseInstallerPicker: () -> Unit,
    onInstallFseApp: (String) -> Unit,
) {
    val uiState by state.collectAsState()
    // Saved rather than merely remembered. The split path of this firmware recreates the activity
    // when a pane is promoted or collapsed, and every open panel used to vanish with it - so a
    // driver who widened the window to read a setting arrived back on the dashboard instead.
    var showClusterPicker by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showAdbRecovery by rememberSaveable { mutableStateOf(false) }
    var showAdbExplainer by rememberSaveable { mutableStateOf(false) }
    var settingsFor by rememberSaveable { mutableStateOf<TileId?>(null) }
    // Service used to be seven quick taps on an undisclosed part of the screen, with no affordance
    // and nothing to tell you it had happened. A live run found the other half of that bargain: a
    // tap that misses the secret door now lands on a tile, and an odd number of them switched the
    // mirrors off in silence. It is a tile of its own, and the strip below is only a strip again.
    //
    // The taps came back for one case and only one: the ADB gate covers the dashboard, so it covers
    // the service tile, and that is precisely when the readings are wanted. They live on the title
    // of [AdbExplainerSheet], which is a window with no other controls in it - a tap that is not the
    // seventh has nothing to hit.
    val openService = remember(onRefreshScreenDiagnostics, onRefreshSystemLanguage) {
        {
            onRefreshScreenDiagnostics()
            onRefreshSystemLanguage()
            showDiagnostics = true
        }
    }
    val adbStartupOverlay = AdbStartupGatePolicy.overlay(uiState.adbRescue)
    val adbStartupBlocked = uiState.adbRescue.phase != AdbRescuePhase.TRUSTED
    // The recovery window belongs to the gate and cannot outlive it. Latched, it reopened itself:
    // the car answers, the gate goes, the flag stays true, and the next thing to block the app
    // arrived with a recovery dialog already on top of it that nobody had asked for.
    LaunchedEffect(adbStartupOverlay.visible) {
        if (!adbStartupOverlay.visible) showAdbRecovery = false
    }
    val openClusterPicker = remember(onRefreshScreenDiagnostics) {
        {
            onRefreshScreenDiagnostics()
            showClusterPicker = true
        }
    }
    val openSettings = remember(onRefreshDefaultApps, onChooseFseApp, openService) {
        { id: TileId ->
            when (id) {
                // «Сервис» had a panel of one sentence and a blue «Открыть сервис» in front of
                // the service itself - a door to a door. Both gestures open the service now.
                TileId.SERVICE -> openService()
                // Opening the tile asks the car only if the last read has gone stale.
                TileId.DEFAULT_APPS -> {
                    onRefreshDefaultApps(false)
                    settingsFor = id
                }
                // «Экран справа» has no settings. Its panel held one sentence and a button that
                // opened the chooser the tile's own press opens, so a long press and a short
                // press on one tile led to two screens, one of them empty. Both open the chooser
                // now, and the sentence went with it - see [FseInstallerPickerDialog].
                TileId.PASSENGER -> onChooseFseApp()
                else -> {
                    settingsFor = id
                }
            }
        }
    }
    // The callbacks this function still takes, gathered once so a tile and its settings
    // sheet can be handed the whole vocabulary instead of a hand-picked subset each.
    //
    // Held across frames, and the three local lambdas above are held with it. A data class of
    // twenty-six lambdas is a new object on every recomposition, so every tile, every chip and
    // every settings panel was being handed a parameter that had changed - which is the one thing
    // that makes Compose redraw a subtree it did not need to touch. The keys are the callbacks
    // themselves: this rebuilds when the activity hands down a different one, and not otherwise.
    val dashboardActions = remember(
        onToggleSimulcast,
        onLaunchSimulcast,
        onRepairSimulcast,
        onChooseApps,
        onLoadAppChoices,
        onToggleApp,
        onToggleMirrors,
        onMirrorsPosition,
        onMirrorsProcessing,
        onPreviewMirrors,
        onNavigationAction,
        onNavigationPlacement,
        onNavigationSteeringWheelButton,
        onChooseNavigationApp,
        onLoadNavigationAppChoices,
        onSelectNavigationApp,
        onToggleSplitScreen,
        onLaunchSplitScreen,
        onSetWeatherEnabled,
        onToggleHudGuidance,
        onToggleSpeakerCovers,
        onRaiseSpeakerCovers,
        onToggleCloudLink,
        onSetCloudWifiRetained,
        onOpenSystemLanguage,
        onSetDefaultAppsEnabled,
        onChooseFseApp,
        openClusterPicker,
        openService,
        openSettings,
    ) {
        DashboardActions(
            onToggleSimulcast = onToggleSimulcast,
            onLaunchSimulcast = onLaunchSimulcast,
            onRepairSimulcast = onRepairSimulcast,
            onChooseApps = onChooseApps,
            onLoadAppChoices = onLoadAppChoices,
            onToggleApp = onToggleApp,
            onToggleMirrors = onToggleMirrors,
            onMirrorsPosition = onMirrorsPosition,
            onMirrorsProcessing = onMirrorsProcessing,
            onPreviewMirrors = onPreviewMirrors,
            onNavigationAction = onNavigationAction,
            onNavigationPlacement = onNavigationPlacement,
            onNavigationSteeringWheelButton = onNavigationSteeringWheelButton,
            onChooseNavigationApp = onChooseNavigationApp,
            onLoadNavigationAppChoices = onLoadNavigationAppChoices,
            onSelectNavigationApp = onSelectNavigationApp,
            onToggleSplitScreen = onToggleSplitScreen,
            onLaunchSplitScreen = onLaunchSplitScreen,
            onSetWeatherEnabled = onSetWeatherEnabled,
            onToggleHudGuidance = onToggleHudGuidance,
            onToggleSpeakerCovers = onToggleSpeakerCovers,
            onRaiseSpeakerCovers = onRaiseSpeakerCovers,
            onToggleCloudLink = onToggleCloudLink,
            onSetCloudWifiRetained = onSetCloudWifiRetained,
            onOpenSystemLanguage = onOpenSystemLanguage,
            onSetDefaultAppsEnabled = onSetDefaultAppsEnabled,
            onChooseFseApp = onChooseFseApp,
            onOpenClusterPicker = openClusterPicker,
            onOpenService = openService,
            onOpenSettings = openSettings,
        )
    }

    // Правка W6 (волна 7): ширина берётся из фактического constraint корневого layout.
    // LocalWindowInfo.containerSize обновляется только с configuration change, которого
    // reveal/promote-путь прошивки не шлёт (collapse его шлёт - тот путь и работал), и панель
    // залипала в чужой ширине до следующего пересоздания окна.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dashboardLayout = DashboardLayoutPolicy.resolve(maxWidth.value.roundToInt())
        val compactLayout = dashboardLayout == DashboardLayoutMode.NARROW
        // Eleven tiles decided from scratch on every recomposition, and this one recomposes on every
        // state publication the runtime makes.
        val tiles = remember(uiState) { DashboardTiles.of(uiState) }

        DenzaTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = DenzaColors.Ground) {
                // Правка W8: дашборд всегда вписывается в ширину своего окна. Панельные ширины
                // (узкая 1/3 и средняя 2/3) перекомпоновывают карточки; горизонтального скролла с
                // холстом 1280 dp больше нет - в панели 828 dp он прятал ~904 px дашборда за краем.
                //
                // The window the app is handed is not the box it may draw in. In a pane the car
                // keeps the top 24 dp for its own freeform caption bar, and `safeDrawing` is what
                // reports it - so the page is padded by exactly those insets and measures itself
                // inside them. The layout itself, and the arithmetic for when it does not fit, is
                // [DashboardBody]'s: the debug fixture harness hosts the same body in a fixed frame.
                DashboardBody(
                    tiles = tiles,
                    layout = dashboardLayout,
                    enabled = !adbStartupBlocked,
                    onPress = { tile -> DashboardPress.perform(tile, uiState, dashboardActions) },
                    onHold = { tile -> dashboardActions.onOpenSettings(tile.id) },
                    strip = { box ->
                        if (!adbStartupBlocked) {
                            SpectrumPanel(
                                layout = DashboardLayoutPolicy.panel(dashboardLayout),
                                modifier = box,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
            // Every dialog on this screen lives inside the theme, which is not where they
            // started. The first cut closed DenzaTheme around the dashboard alone, so the
            // whole dialog layer fell through to Material's own defaults and drew its
            // buttons in Material purple - on a screen whose entire point was that there is
            // one palette. A theme that wraps only the easy half is not a theme.

            settingsFor?.let { id ->
                if (id == TileId.DEFAULT_APPS) {
                    DefaultAppsSheet(
                        state = uiState.defaultApps,
                        compact = compactLayout,
                        onRefresh = { onRefreshDefaultApps(true) },
                        onSelect = onSelectDefaultApp,
                        onSetEnabled = onSetDefaultAppsEnabled,
                        onDismiss = { settingsFor = null },
                    )
                } else {
                    FeatureSheet(
                        id = id,
                        state = uiState,
                        actions = dashboardActions,
                        compact = compactLayout,
                        onDismiss = { settingsFor = null },
                    )
                }
            }
            if (showDiagnostics) {
                ServicePanel(
                    state = uiState,
                    compactLayout = compactLayout,
                    // A row that names a feature in trouble opens that feature's panel, as a
                    // long press on its tile would: the service says what, the panel fixes it.
                    onOpenFeature = { id ->
                        showDiagnostics = false
                        openSettings(id)
                    },
                    onSelectClusterDisplay = onSelectClusterDisplay,
                    onCheckAdbAccess = onCheckAdbAccess,
                    onRequestAdbAuthorizationOnce = onRequestAdbAuthorizationOnce,
                    onAllowNewAdbAuthorizationAttempt = onAllowNewAdbAuthorizationAttempt,
                    onDismiss = { showDiagnostics = false },
                )
            }
            if (showClusterPicker) {
                ClusterDisplayPickerDialog(
                    displays = uiState.clusterCandidates,
                    compactLayout = compactLayout,
                    onSelect = { displayId ->
                        onSelectClusterDisplay(displayId)
                        showClusterPicker = false
                    },
                    onRefresh = onRefreshScreenDiagnostics,
                    onDismiss = { showClusterPicker = false },
                )
            }
            if (uiState.appPickerVisible) {
                AppPickerDialog(
                    apps = uiState.appChoices,
                    compactLayout = compactLayout,
                    selectedCount = uiState.selectedAppCount,
                    onToggle = onToggleApp,
                    onDismiss = onCloseAppPicker,
                )
            }
            if (uiState.navigationPickerVisible) {
                NavigationPickerDialog(
                    apps = uiState.navigationAppChoices,
                    compactLayout = compactLayout,
                    onSelect = onSelectNavigationApp,
                    onDismiss = onCloseNavigationPicker,
                )
            }
            if (uiState.fseInstallerPickerVisible) {
                FseInstallerPickerDialog(
                    apps = uiState.fseInstallApps,
                    compactLayout = compactLayout,
                    onInstall = onInstallFseApp,
                    onDismiss = onCloseFseInstallerPicker,
                )
            }
            if (adbStartupOverlay.visible) {
                AdbStartupOverlay(
                    model = adbStartupOverlay,
                    compact = compactLayout,
                    onPrimaryAction = {
                        when (adbStartupOverlay.primaryAction) {
                            AdbStartupPrimaryAction.NONE -> Unit
                            AdbStartupPrimaryAction.CHECK_ACCESS -> onCheckAdbAccess()
                            AdbStartupPrimaryAction.REQUEST_AUTHORIZATION ->
                                onRequestAdbAuthorizationOnce()
                        }
                    },
                    onOpenRecovery = { showAdbRecovery = true },
                    onOpenExplainer = { showAdbExplainer = true },
                )
            }
            // The explainer outlives the gate's own visibility check on purpose: it is a window of
            // its own, and closing it is the owner's to do, not a side effect of the phase changing
            // underneath it. It is also the only way to the service screen while the gate is up -
            // the dashboard, and with it the service tile, is behind the shield.
            if (showAdbExplainer) {
                AdbExplainerSheet(
                    compact = compactLayout,
                    onOpenService = openService,
                    onDismiss = { showAdbExplainer = false },
                )
            }
            if (adbStartupBlocked && !adbStartupOverlay.visible) {
                // The normal passive check is intentionally invisible, but no control can race it and
                // start feature work before the global prerequisite has been proven.
                val startupInteractionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = startupInteractionSource,
                            indication = null,
                            onClick = {},
                        ),
                )
            }
            if (showAdbRecovery && adbStartupOverlay.visible) {
                AdbRecoveryDialog(
                    state = uiState,
                    compact = compactLayout,
                    onCheckAdbAccess = onCheckAdbAccess,
                    onRequestAdbAuthorizationOnce = onRequestAdbAuthorizationOnce,
                    onAllowNewAdbAuthorizationAttempt = onAllowNewAdbAuthorizationAttempt,
                    onDismiss = { showAdbRecovery = false },
                )
            }
        }
    }
}

@Composable
internal fun AdbStartupOverlay(
    model: AdbStartupOverlayModel,
    compact: Boolean,
    onPrimaryAction: () -> Unit,
    onOpenRecovery: () -> Unit,
    onOpenExplainer: () -> Unit,
) {
    // No scrim touch to answer: the gate's whole statement is that nothing behind it may be used
    // yet, so the dark swallows the tap rather than dismissing anything.
    val m = Sheet.Modal
    DenzaModalCard(compact = compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.ICON_GAP.dp),
        ) {
            // Working, the settings' own ring; otherwise the service's glyph, in the car's orange
            // when there is a recovery to offer - the door the orange button below opens.
            if (model.busy) {
                WorkingRing(size = m.ICON.dp, stroke = glyphStroke(m.ICON.dp))
            } else {
                NamedGlyph(
                    glyph = DenzaIcons.ServiceGlyph,
                    size = m.ICON.dp,
                    alpha = if (model.recoveryAvailable) 1f else Sheet.Header.CLOSE_ALPHA,
                    ground = Color(Sheet.Plate.COLOR),
                    tint = if (model.recoveryAvailable) Color(ClusterInk.ORANGE.halo) else Color.White,
                )
            }
            Text(
                model.title,
                style = SheetInk.style(if (compact) m.COMPACT_TITLE_SIZE else m.TITLE_SIZE, 500),
                maxLines = 1,
            )
        }
        ParagraphText(
            model.message,
            style = SheetInk.style(m.TEXT_SIZE, 400, SheetInk.white(m.TEXT_ALPHA), Sheet.Note.LEADING),
            size = m.TEXT_SIZE,
            modifier = Modifier.fillMaxWidth(),
        )
        // The cause, under the instruction that is the same for every car in this state. Without it
        // two different "ADB недоступен" gates are the same screen, and the one fact the app
        // actually read about this car - that the switch is off - reaches nobody.
        model.details?.let { details -> DenzaNote(details) }
        // The stock dialog's actions: the one it exists for across the card, the quiet ones under it
        // side by side at equal widths - or, in a pane, each on a line of its own, primary first,
        // because a card 384 dp wide has room for one button per line and no room for two.
        model.primaryLabel?.let { label ->
            DenzaPrimaryButton(text = label, onClick = onPrimaryAction, modifier = Modifier.fillMaxWidth())
        }
        val quiet = listOfNotNull<@Composable (Modifier) -> Unit>(
            if (model.primaryLabel != null && model.recoveryAvailable) {
                { modifier -> RecoverButton(onClick = onOpenRecovery, modifier = modifier) }
            } else {
                null
            },
            if (model.explainerAvailable) {
                { modifier -> DenzaSecondaryButton(AdbExplainer.OPEN_LABEL, onOpenExplainer, modifier) }
            } else {
                null
            },
        )
        if (quiet.isNotEmpty()) {
            if (compact) {
                quiet.forEach { it(Modifier.fillMaxWidth()) }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Sheet.Footnote.GAP.dp),
                ) {
                    quiet.forEach { it(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * The gate's second action, in one place because two layouts draw it.
 *
 * The only button in the app with an amber edge, and the reason it is written out here rather than
 * taken from [DenzaSecondaryButton]: this is the door to a recovery flow, and amber is what the
 * vehicle itself uses for something waiting on a decision.
 */
@Composable
private fun RecoverButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    DenzaSecondaryButton(text = RECOVER_LABEL, onClick = onClick, modifier = modifier, attention = true)
}

private const val RECOVER_LABEL = "Восстановить ADB"

/**
 * The gate's recovery flow, in a window of its own above it.
 *
 * It was the one surface on this screen with no narrow layout at all: 0.68 of the window behind
 * 32 dp of padding down each side, which in a 416 dp pane is 219 dp of content and three button
 * labels with nowhere to go. Everything about the width, the corner and the padding is now the
 * modal's, and the three actions are the app's own buttons - so they carry a single line with an
 * ellipsis rather than clipping, and they all stop answering while the handshake is in flight, the
 * way the same three do on the service panel.
 */
@Composable
private fun AdbRecoveryDialog(
    state: DenzaUiState,
    compact: Boolean,
    onCheckAdbAccess: () -> Unit,
    onRequestAdbAuthorizationOnce: () -> Unit,
    onAllowNewAdbAuthorizationAttempt: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = state.adbRescue.phase == AdbRescuePhase.CHECKING ||
        state.adbRescue.phase == AdbRescuePhase.REQUESTING
    DenzaModalDialog(compact = compact, onDismiss = onDismiss) {
        Text("Восстановление ADB", style = SheetInk.style(Sheet.Modal.TITLE_SIZE, 500))
        Text(
            state.adbRescue.message,
            style = SheetInk.style(Sheet.Modal.TEXT_SIZE, 400, SheetInk.white(Sheet.Modal.TEXT_ALPHA), Sheet.Note.LEADING),
        )
        state.adbRescue.details?.let { details ->
            DenzaNote(details)
        }
        DenzaSecondaryButton(
            text = "Проверить доступ",
            onClick = onCheckAdbAccess,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        if (state.adbRescue.canRequest) {
            DenzaPrimaryButton(
                text = "Отправить один запрос",
                onClick = onRequestAdbAuthorizationOnce,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
        if (state.adbRescue.canResetAttempt) {
            DenzaSecondaryButton(
                text = "Разрешить новую попытку",
                onClick = onAllowNewAdbAuthorizationAttempt,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            DenzaSecondaryButton(text = "Закрыть", onClick = onDismiss)
        }
    }
}

/**
 * The screens this car offers for the instruments, and the way back to letting the app decide.
 *
 * There were two of these. The service panel listed "#2 · 1920×720 · ClusterDisplay" under a button
 * called "Вернуть автоматический выбор"; the picker listed "Экран 1 · 1920×720" under one called
 * "Определять автоматически" - two names for one screen, two names for one action, and the same
 * `id != 0 && !isOwnVirtualDisplay` written out in both places. A driver who reached this choice
 * through the tile and then through service was shown two different cars.
 *
 * "Экран N" rather than the display id, because an id is a number the platform hands out: it is not
 * stable across boots, it is not written on anything, and there is nothing in the car the driver
 * could count it against. They can count screens.
 */
@Composable
private fun ClusterDisplayChoices(
    displays: List<ClusterDisplayDescriptor>,
    onSelect: (Int?) -> Unit,
) {
    // One child rather than N+1 siblings, so the list keeps a neighbour's gap between its buttons
    // wherever it is dropped - inside a service group, or straight onto a panel whose own children
    // stand a group apart.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
    ) {
        clusterDisplayChoices(displays).forEachIndexed { index, display ->
            DenzaSecondaryButton(
                text = clusterDisplayName(index, display),
                onClick = { onSelect(display.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DenzaSecondaryButton(
            text = CLUSTER_AUTOMATIC_LABEL,
            onClick = { onSelect(null) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// The filter and the wording live on ClusterDisplayResolver, so the service panel's own label
// counts over the same list and cannot call the same screen by a different name.
private fun clusterDisplayChoices(
    displays: List<ClusterDisplayDescriptor>,
): List<ClusterDisplayDescriptor> = ClusterDisplayResolver.choices(displays)

private fun clusterDisplayName(index: Int, display: ClusterDisplayDescriptor): String =
    ClusterDisplayResolver.choiceName(index, display)

private const val CLUSTER_AUTOMATIC_LABEL = "Определять автоматически"

/** How often the picker asks the car again while it has nothing to offer. */
private const val CLUSTER_RESCAN_MS = 1_500L

/**
 * The same choice, reached from a tile instead of from service.
 *
 * A panel like every other panel now. It was a centred dialog at 0.56 of the screen - 0.96 in a
 * pane, which is two guesses about width where the app has one answer - with its title and its
 * subtitle set by hand, its own copy of the display list, and its own words for both. The two
 * records are one composable; the only thing this adds is that it closes when a screen is chosen.
 */
@Composable
private fun ClusterDisplayPickerDialog(
    displays: List<ClusterDisplayDescriptor>,
    compactLayout: Boolean,
    onSelect: (Int?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = clusterDisplayChoices(displays)
    DenzaSheet(onDismiss = onDismiss, compact = compactLayout) {
        DenzaSheetHeader(
            title = "Приборный экран",
            subtitle = "После выбора на экране появится короткая проверка",
            onDismiss = onDismiss,
            glyph = DenzaIcons.ClusterGlyph,
        )
        if (choices.isEmpty()) {
            ClusterDisplaySearch(onRefresh = onRefresh)
        } else {
            ClusterDisplayChoices(displays = displays, onSelect = onSelect)
        }
    }
}

/**
 * What the picker shows while the car has not named a second screen yet.
 *
 * It used to show "Приборные экраны пока не найдены" in amber over a "Повторить поиск" button,
 * which is the shape this app does not have: a failure written out, and the retry handed back to
 * the driver. Nothing had failed. The display list is read once when the picker is opened and the
 * cluster is not always registered by then - so the honest answer is that the app is still looking,
 * and looking is something it can do without being asked twice.
 */
@Composable
private fun ClusterDisplaySearch(onRefresh: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(CLUSTER_RESCAN_MS)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkingRing(size = Sheet.Header.CLOSE.dp, stroke = glyphStroke(Sheet.Header.CLOSE.dp))
        DenzaNote("Ищем экраны за рулём")
    }
}
