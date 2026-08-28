package dev.denza.apps.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
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
import dev.denza.apps.ui.components.DenzaKeyValueRow
import dev.denza.apps.ui.components.DenzaModalCard
import dev.denza.apps.ui.components.DenzaModalDialog
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.dashboard.DashboardActions
import dev.denza.apps.ui.dashboard.DashboardGrid
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
    onCloseNavigationPicker: () -> Unit,
    onSelectNavigationApp: (String) -> Unit,
    onToggleSplitScreen: (Boolean) -> Unit,
    onLaunchSplitScreen: () -> Unit,
    onSetWeatherEnabled: (Boolean) -> Unit,
    onToggleHudGuidance: (Boolean) -> Unit,
    onToggleSpeakerCovers: (Boolean) -> Unit,
    onRaiseSpeakerCovers: () -> Unit,
    onLowerSpeakerCovers: () -> Unit,
    onSelectClusterDisplay: (Int?) -> Unit,
    onRefreshScreenDiagnostics: () -> Unit,
    onCheckAdbAccess: () -> Unit,
    onRequestAdbAuthorizationOnce: () -> Unit,
    onAllowNewAdbAuthorizationAttempt: () -> Unit,
    onRefreshStockRussianLocale: () -> Unit,
    onSetStockRussianLocaleEnabled: (Boolean) -> Unit,
    onChooseApps: () -> Unit,
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
    val openService = remember(onRefreshScreenDiagnostics, onRefreshStockRussianLocale) {
        {
            onRefreshScreenDiagnostics()
            onRefreshStockRussianLocale()
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
    val openSettings = remember(onRefreshDefaultApps) {
        { id: TileId ->
            // Opening the tile asks the car only if the last read has gone stale.
            if (id == TileId.DEFAULT_APPS) onRefreshDefaultApps(false)
            settingsFor = id
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
        onToggleApp,
        onToggleMirrors,
        onMirrorsPosition,
        onMirrorsProcessing,
        onPreviewMirrors,
        onNavigationAction,
        onNavigationPlacement,
        onNavigationSteeringWheelButton,
        onChooseNavigationApp,
        onSelectNavigationApp,
        onToggleSplitScreen,
        onLaunchSplitScreen,
        onSetWeatherEnabled,
        onToggleHudGuidance,
        onToggleSpeakerCovers,
        onRaiseSpeakerCovers,
        onLowerSpeakerCovers,
        onSetStockRussianLocaleEnabled,
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
            onToggleApp = onToggleApp,
            onToggleMirrors = onToggleMirrors,
            onMirrorsPosition = onMirrorsPosition,
            onMirrorsProcessing = onMirrorsProcessing,
            onPreviewMirrors = onPreviewMirrors,
            onNavigationAction = onNavigationAction,
            onNavigationPlacement = onNavigationPlacement,
            onNavigationSteeringWheelButton = onNavigationSteeringWheelButton,
            onChooseNavigationApp = onChooseNavigationApp,
            onSelectNavigationApp = onSelectNavigationApp,
            onToggleSplitScreen = onToggleSplitScreen,
            onLaunchSplitScreen = onLaunchSplitScreen,
            onSetWeatherEnabled = onSetWeatherEnabled,
            onToggleHudGuidance = onToggleHudGuidance,
            onToggleSpeakerCovers = onToggleSpeakerCovers,
            onRaiseSpeakerCovers = onRaiseSpeakerCovers,
            onLowerSpeakerCovers = onLowerSpeakerCovers,
            onSetStockRussianLocale = onSetStockRussianLocaleEnabled,
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
        val sideMargin = DashboardLayoutPolicy.sideMargin(dashboardLayout)
        val chips = DashboardLayoutPolicy.chips(dashboardLayout)
        // The window the app is handed is not the box it may draw in. In a pane the car keeps the
        // top 24 dp for its own freeform caption bar, and `safeDrawing` is what reports it - so the
        // page subtracts exactly the insets it pads with, and the width the strip is measured
        // against is the width the strip is given. The old line measured the full window, which is
        // the same class of mistake as laying a pane out against 680.
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val contentWidth = (
            maxWidth - sideMargin * 2 -
                insets.calculateStartPadding(direction) - insets.calculateEndPadding(direction)
            ).value.coerceAtLeast(1f)
        val contentHeight = maxHeight -
            insets.calculateTopPadding() - insets.calculateBottomPadding() -
            DenzaMetrics.Space.L - DenzaMetrics.Space.M
        val features = remember(uiState) { DashboardTiles.of(uiState).size }
        val page = DashboardLayoutPolicy.page(
            mode = dashboardLayout,
            features = features,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )

        DenzaTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = DenzaColors.Background) {
                // Правка W8: дашборд всегда вписывается в ширину своего окна. Панельные ширины
                // (узкая 1/3 и средняя 2/3) перекомпоновывают карточки; горизонтального скролла с
                // холстом 1280 dp больше нет - в панели 828 dp он прятал ~904 px дашборда за краем.
                //
                // Vertically the page degrades instead of overflowing. It used to add up to exactly
                // 680 - 20 + 340 + 12 + 296 + 12 - with no scroll in any of the three widths, so
                // the `Spacer(weight(1f))` under the strip was always handed nothing and any inset
                // at all pushed the foot of the analyser past the bottom edge in silence. Now the
                // strip takes what is left down to a floor, and when even that will not fit - a low
                // window, or a car that keeps more of it than this one - the column scrolls.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .then(
                            if (page.scrolls) {
                                Modifier.verticalScroll(rememberScrollState())
                            } else {
                                Modifier
                            },
                        )
                        // Top and bottom are not the same rung, and every board says so: 20
                        // over the features and 12 under the strip. Both were 20 here, which
                        // put the page 8 dp taller than the window it is laid out for and drew
                        // the foot of the analyser's reflection past the bottom edge.
                        .padding(
                            start = sideMargin,
                            end = sideMargin,
                            top = DenzaMetrics.Space.L,
                            bottom = DenzaMetrics.Space.M,
                        ),
                ) {
                    DashboardGrid(
                        state = uiState,
                        actions = dashboardActions,
                        layout = dashboardLayout,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !adbStartupBlocked,
                    )
                    Spacer(Modifier.height(DashboardLayoutPolicy.bandGap(dashboardLayout)))
                    if (!adbStartupBlocked) {
                        // The strip draws in a virtual space of its own, and the box it is given
                        // has to be that space's shape or the drawing arrives stretched. It used to
                        // get whatever height was left over, which on the full screen was about
                        // twice its own: every stroke came out drawn on a canvas stretched
                        // vertically, which is why the analyser read as a sparse ripple rather than
                        // the columns the board draws.
                        //
                        // So the full screen always asks for a box of the board's shape, and a pane
                        // with room takes the remainder as a `weight(1f)` - which its renderer can
                        // do, because it lays itself out at one unit to one dp in whatever it is
                        // handed, and which is the one arrangement that cannot be wrong. A pane
                        // only names a height when the column is scrolling, because a scrolling
                        // column has no remainder: an infinite height is what a weight would be
                        // measured against there.
                        SpectrumPanel(
                            layout = DashboardLayoutPolicy.panel(dashboardLayout),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (chips && !page.scrolls) {
                                        Modifier.weight(1f)
                                    } else {
                                        Modifier.height(page.panelHeight)
                                    },
                                ),
                        )
                    }
                    // Any slack on the full screen goes under the strip rather than between it and
                    // the tiles. A pane has none: its strip already took it.
                    if (!chips && !page.scrolls) {
                        Spacer(Modifier.weight(1f))
                    }
                }
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
                DiagnosticsDialog(
                    state = uiState,
                    compactLayout = compactLayout,
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
private fun AdbStartupOverlay(
    model: AdbStartupOverlayModel,
    compact: Boolean,
    onPrimaryAction: () -> Unit,
    onOpenRecovery: () -> Unit,
    onOpenExplainer: () -> Unit,
) {
    // No scrim touch to answer: the gate's whole statement is that nothing behind it may be used
    // yet, so the dark swallows the tap rather than dismissing anything. The card's width, its
    // corner and its padding are the modal's now - it used to take 0.72 of the screen behind a
    // 32 dp corner, which is a rung off the spacing ladder standing in for a radius.
    DenzaModalCard(compact = compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.L),
        ) {
            if (model.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(DenzaMetrics.Component.MODAL_SPINNER),
                    color = DenzaColors.Accent,
                    strokeWidth = DenzaMetrics.Component.MODAL_SPINNER_STROKE,
                )
            } else {
                Icon(
                    Icons.Outlined.Build,
                    contentDescription = null,
                    modifier = Modifier.size(DenzaMetrics.Component.MODAL_ICON),
                    tint = if (model.recoveryAvailable) DenzaColors.Warning else DenzaColors.Muted,
                )
            }
            Text(
                model.title,
                style = MaterialTheme.typography.headlineMedium,
                color = DenzaColors.Ink,
            )
        }
        Text(
            model.message,
            style = MaterialTheme.typography.bodyLarge,
            color = DenzaColors.Muted,
        )
        // The cause, under the instruction that is the same for every car in this state. Without it
        // two different "ADB недоступен" gates are the same screen, and the one fact the app
        // actually read about this car - that the switch is off - reaches nobody.
        model.details?.let { details ->
            Text(details, style = MaterialTheme.typography.bodyMedium, color = DenzaColors.Muted)
        }
        // **In a pane the actions stack.** A Row measures its children in order: the outlined
        // action takes the width it asks for and the primary one is handed what is left, so at
        // 416 dp "Я подтвердил - проверить" was drawn into a pill narrower than its own label and
        // the words ran out past both ends of it. That was on the board as well as on the screen,
        // and the note beside it said which button should lose was a product decision nobody had
        // made.
        //
        // Neither loses. A card 312 dp wide has room for one button per line and no room for two,
        // so the narrow gate spends a line each - primary first, because a stack is read from the
        // top and the top is where the thing you came to press belongs.
        if (model.primaryLabel != null) {
            if (compact) {
                DenzaPrimaryButton(
                    text = model.primaryLabel,
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (model.recoveryAvailable) {
                    RecoverButton(onClick = onOpenRecovery, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M, Alignment.End),
                ) {
                    if (model.recoveryAvailable) {
                        RecoverButton(onClick = onOpenRecovery)
                    }
                    DenzaPrimaryButton(text = model.primaryLabel, onClick = onPrimaryAction)
                }
            }
        }
        // On its own line, under whatever action the state has, and on every state that
        // blocks - including the ones with no action at all.
        if (model.explainerAvailable) {
            DenzaSecondaryButton(
                text = AdbExplainer.OPEN_LABEL,
                onClick = onOpenExplainer,
                modifier = Modifier.fillMaxWidth(),
            )
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
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = DenzaMetrics.Component.SEGMENT_HEIGHT),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.Warning),
    ) {
        Text(
            RECOVER_LABEL,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
        Text(
            "Восстановление ADB",
            style = MaterialTheme.typography.titleLarge,
            color = DenzaColors.Ink,
        )
        Text(
            state.adbRescue.message,
            style = MaterialTheme.typography.bodyLarge,
            color = DenzaColors.Ink,
        )
        state.adbRescue.details?.let { details ->
            Text(details, style = MaterialTheme.typography.bodyMedium, color = DenzaColors.Muted)
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
            // The one control here with no border: shaped like the buttons above it rather than
            // like Material's fully rounded default, so its ripple is not a different corner from
            // everything else on the card.
            TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) {
                Text(
                    "Закрыть",
                    style = MaterialTheme.typography.labelLarge,
                    color = DenzaColors.Accent,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    state: DenzaUiState,
    compactLayout: Boolean,
    onSelectClusterDisplay: (Int?) -> Unit,
    onCheckAdbAccess: () -> Unit,
    onRequestAdbAuthorizationOnce: () -> Unit,
    onAllowNewAdbAuthorizationAttempt: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The service panel was the last thing on this screen still drawn as a centred Material dialog
    // with its own width, its own header and a "Закрыть" button in the corner - a different
    // surface for the one door the driver reaches for when something is wrong. It is a panel like
    // the others now: same edge, same width, same way out.
    //
    // What it says has been reordered around the question it is opened with, which is *what is
    // wrong*. It used to open on a wall: forty readings in key=value, then sixty lines of one
    // feature's log, then every split APK file of every installable application. The answer was in
    // there somewhere. Now the panel names the features that need somebody, first and in the words
    // the tiles use, and the readings are behind a button for the session that wants them.
    val adbBusy = state.adbRescue.phase == AdbRescuePhase.CHECKING ||
        state.adbRescue.phase == AdbRescuePhase.REQUESTING
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    val needing = remember(state) { DashboardTiles.attentionTiles(state) }
    // Forty readings in key=value, split apart once per state rather than once per frame.
    val technical = remember(state.technicalDetails) {
        state.technicalDetails
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "—") }
            .toList()
    }
    DenzaSheet(onDismiss = onDismiss, compact = compactLayout) {
        DenzaSheetHeader(
            title = "Сервис",
            subtitle = "",
            onDismiss = onDismiss,
            icon = DenzaIcons.Service,
        )
        DenzaSection(if (needing.isEmpty()) "Состояние" else "Что не так") {
            if (needing.isEmpty()) {
                Text(
                    "Все функции работают.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DenzaColors.Muted,
                )
            } else {
                needing.forEach { tile ->
                    DenzaKeyValueRow(label = tile.name, value = tile.state, stacked = true)
                }
            }
        }
        DenzaSection("Доступ к машине") {
            DenzaKeyValueRow(label = "Состояние", value = state.adbRescue.message, stacked = true)
            state.adbRescue.details?.let { details ->
                Text(details, style = MaterialTheme.typography.bodyMedium, color = DenzaColors.Muted)
            }
            DenzaSecondaryButton(
                text = "Проверить доступ",
                onClick = onCheckAdbAccess,
                modifier = Modifier.fillMaxWidth(),
                enabled = !adbBusy,
            )
            if (state.adbRescue.canRequest) {
                DenzaPrimaryButton(
                    text = "Отправить один запрос",
                    onClick = onRequestAdbAuthorizationOnce,
                    modifier = Modifier.fillMaxWidth()
                        .height(DenzaMetrics.Component.PRIMARY_HEIGHT),
                    enabled = !adbBusy,
                )
            }
            if (state.adbRescue.canResetAttempt) {
                DenzaSecondaryButton(
                    text = "Разрешить новую попытку",
                    onClick = onAllowNewAdbAuthorizationAttempt,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !adbBusy,
                )
            }
        }
        // A list of unlabelled buttons with nothing saying what any of them were for. The owner
        // read it out on the car and said he did not understand what the button was, which is the
        // only review that matters: the panel says which screen is in use and what choosing
        // another one is for, before offering the choice. The choice itself is the picker's, drawn
        // by the picker's own composable.
        DenzaSection("Приборный экран") {
            DenzaKeyValueRow(
                label = "Сейчас",
                value = state.clusterDisplayLabel,
                stacked = true,
            )
            DenzaNote(
                "Приложение само находит экран за рулём. Выберите другой, если приборы ушли не " +
                    "туда.",
            )
            ClusterDisplayChoices(
                displays = state.clusterCandidates,
                onSelect = onSelectClusterDisplay,
            )
        }
        // It only ever opened. "Показать" set a flag nothing could clear, so a session that pressed
        // it once to read one line was left scrolling forty of them past every other group on the
        // panel for as long as the panel stayed open.
        DenzaSection("Технические сведения") {
            DenzaSecondaryButton(
                text = if (showTechnical) "Скрыть" else "Показать",
                onClick = { showTechnical = !showTechnical },
                modifier = Modifier.fillMaxWidth(),
            )
            if (showTechnical) {
                technical.forEach { (label, value) ->
                    DenzaKeyValueRow(label = label, value = value, stacked = true)
                }
            }
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
            icon = DenzaIcons.Cluster,
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
        CircularProgressIndicator(
            modifier = Modifier.size(DenzaMetrics.Component.MODAL_SPINNER),
            color = DenzaColors.Accent,
            strokeWidth = DenzaMetrics.Component.MODAL_SPINNER_STROKE,
        )
        Text(
            "Ищем экраны за рулём",
            style = MaterialTheme.typography.bodyLarge,
            color = DenzaColors.Muted,
        )
    }
}
