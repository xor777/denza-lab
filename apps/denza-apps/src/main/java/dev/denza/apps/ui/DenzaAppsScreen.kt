package dev.denza.apps.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.foundation.Image
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import dev.denza.apps.DenzaUiState
import dev.denza.apps.feature.trip.TripPanelFlag
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.DenzaTheme
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.adb.AdbRescueCoordinator
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbStartupGatePolicy
import dev.denza.apps.feature.adb.AdbStartupOverlayModel
import dev.denza.apps.feature.adb.AdbStartupPrimaryAction
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.ui.components.DenzaKeyValueRow
import dev.denza.apps.ui.components.DenzaSwitchRow
import dev.denza.apps.ui.dashboard.DashboardActions
import dev.denza.apps.ui.dashboard.DashboardGrid
import dev.denza.apps.ui.dashboard.FeatureSheet
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private const val SHOW_NAVIGATION_AUTOMATIC = false

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
    onNavigationAutomatic: (Boolean) -> Unit,
    onNavigationSteeringWheelButton: (Boolean) -> Unit,
    onNavigationPlacement: (ClusterMapPlacement) -> Unit,
    onChooseNavigationApp: () -> Unit,
    onCloseNavigationPicker: () -> Unit,
    onSelectNavigationApp: (String) -> Unit,
    onToggleSplitScreen: (Boolean) -> Unit,
    onToggleHudGuidance: (Boolean) -> Unit,
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
    onChooseFseApp: () -> Unit,
    onCloseFseInstallerPicker: () -> Unit,
    onInstallFseApp: (String) -> Unit,
) {
    val uiState by state.collectAsState()
    var showClusterPicker by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showAdbRecovery by remember { mutableStateOf(false) }
    var settingsFor by remember { mutableStateOf<FeatureId?>(null) }
    // Hidden diagnostics entry: 7 quick taps, each within 3 s of the previous, opens the
    // diagnostics dialog. No affordance. It used to live on the Трансляция card header, which no
    // longer exists - a tile spends both its gestures on the feature it names - so it moved to the
    // spectrum strip, the one large surface on this screen that does nothing else when touched.
    var diagnosticsTaps by remember { mutableIntStateOf(0) }
    var lastDiagnosticsTapMs by remember { mutableLongStateOf(0L) }
    val onDiagnosticsTap = {
        val now = System.currentTimeMillis()
        diagnosticsTaps = if (now - lastDiagnosticsTapMs <= 3000L) diagnosticsTaps + 1 else 1
        lastDiagnosticsTapMs = now
        if (diagnosticsTaps >= 7) {
            diagnosticsTaps = 0
            onRefreshScreenDiagnostics()
            onRefreshStockRussianLocale()
            showDiagnostics = true
        }
    }
    val adbStartupOverlay = AdbStartupGatePolicy.overlay(uiState.adbRescue)
    val adbStartupBlocked = uiState.adbRescue.phase != AdbRescuePhase.TRUSTED
    val openClusterPicker = {
        onRefreshScreenDiagnostics()
        showClusterPicker = true
    }
    // The twenty-nine callbacks this function still takes, gathered once so a tile and its settings
    // sheet can be handed the whole vocabulary instead of a hand-picked subset each.
    val dashboardActions = DashboardActions(
        onToggleSimulcast = onToggleSimulcast,
        onLaunchSimulcast = onLaunchSimulcast,
        onRepairSimulcast = onRepairSimulcast,
        onChooseApps = onChooseApps,
        onToggleMirrors = onToggleMirrors,
        onMirrorsPosition = onMirrorsPosition,
        onMirrorsProcessing = onMirrorsProcessing,
        onPreviewMirrors = onPreviewMirrors,
        onNavigationAction = onNavigationAction,
        onNavigationPlacement = onNavigationPlacement,
        onNavigationSteeringWheelButton = onNavigationSteeringWheelButton,
        onChooseNavigationApp = onChooseNavigationApp,
        onToggleSplitScreen = onToggleSplitScreen,
        onToggleHudGuidance = onToggleHudGuidance,
        onChooseFseApp = onChooseFseApp,
        onOpenClusterPicker = openClusterPicker,
        onOpenSettings = { settingsFor = it },
    )

    // Правка W6 (волна 7): ширина берётся из фактического constraint корневого layout.
    // LocalWindowInfo.containerSize обновляется только с configuration change, которого
    // reveal/promote-путь прошивки не шлёт (collapse его шлёт - тот путь и работал), и панель
    // залипала в чужой ширине до следующего пересоздания окна.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dashboardLayout = DashboardLayoutPolicy.resolve(maxWidth.value.roundToInt())
        val compactLayout = dashboardLayout == DashboardLayoutMode.NARROW

        DenzaTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = DenzaColors.Background) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Правка W8: дашборд всегда вписывается в ширину своего окна. Панельные ширины
                    // (узкая 1/3 и средняя 2/3) перекомпоновывают карточки и скроллят по вертикали;
                    // горизонтального скролла с холстом 1280 dp больше нет - в панели 828 dp он
                    // прятал ~904 px дашборда за краем.
                    Column(
                        modifier = when (dashboardLayout) {
                            DashboardLayoutMode.WIDE -> Modifier.fillMaxSize()
                            DashboardLayoutMode.MEDIUM,
                            DashboardLayoutMode.NARROW,
                            -> Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        }
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(
                                horizontal = if (compactLayout) {
                                    DenzaMetrics.Space.L
                                } else {
                                    DenzaMetrics.Space.XXL
                                },
                                vertical = DenzaMetrics.Space.L,
                            ),
                    ) {
                        DashboardGrid(
                            state = uiState,
                            actions = dashboardActions,
                            columns = when (dashboardLayout) {
                                DashboardLayoutMode.WIDE ->
                                    DenzaMetrics.Component.TILE_COLUMNS_WIDE
                                DashboardLayoutMode.MEDIUM ->
                                    DenzaMetrics.Component.TILE_COLUMNS_MEDIUM
                                DashboardLayoutMode.NARROW -> 1
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !adbStartupBlocked,
                        )
                        Spacer(Modifier.height(DenzaMetrics.Space.M))
                        // Only the spectrum, for now. This strip used to page between three
                        // instrument panels; the other two are being redrawn against the design
                        // boards, and a pager holding one page is a pager the finger fights.
                        if (TripPanelFlag.ENABLED && !adbStartupBlocked) {
                            SpectrumPanel(
                                compactLayout = compactLayout,
                                onHiddenTap = onDiagnosticsTap,
                                modifier = when (dashboardLayout) {
                                    DashboardLayoutMode.WIDE ->
                                        Modifier.fillMaxWidth().weight(1f)
                                    DashboardLayoutMode.MEDIUM ->
                                        Modifier.fillMaxWidth().height(MEDIUM_TRIP_PANEL_HEIGHT)
                                    DashboardLayoutMode.NARROW ->
                                        Modifier.fillMaxWidth().height(NARROW_TRIP_PANEL_HEIGHT)
                                },
                            )
                        } else {
                            if (dashboardLayout == DashboardLayoutMode.WIDE) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                Spacer(Modifier.height(DenzaMetrics.Space.M))
                            }
                        }
                    }
                }
            }
        }

        settingsFor?.let { id ->
            FeatureSheet(
                id = id,
                state = uiState,
                actions = dashboardActions,
                compact = compactLayout,
                onDismiss = { settingsFor = null },
            )
        }
        if (showDiagnostics) {
            DiagnosticsDialog(
                state = uiState,
                compactLayout = compactLayout,
                onSelectClusterDisplay = onSelectClusterDisplay,
                onCheckAdbAccess = onCheckAdbAccess,
                onRequestAdbAuthorizationOnce = onRequestAdbAuthorizationOnce,
                onAllowNewAdbAuthorizationAttempt = onAllowNewAdbAuthorizationAttempt,
                onSetStockRussianLocaleEnabled = onSetStockRussianLocaleEnabled,
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
                message = uiState.appPickerMessage,
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
                message = uiState.fseInstallerMessage,
                onInstall = onInstallFseApp,
                onDismiss = onCloseFseInstallerPicker,
            )
        }
        if (adbStartupOverlay.visible) {
            AdbStartupOverlay(
                model = adbStartupOverlay,
                onPrimaryAction = {
                    when (adbStartupOverlay.primaryAction) {
                        AdbStartupPrimaryAction.NONE -> Unit
                        AdbStartupPrimaryAction.CHECK_ACCESS -> onCheckAdbAccess()
                        AdbStartupPrimaryAction.REQUEST_AUTHORIZATION ->
                            onRequestAdbAuthorizationOnce()
                    }
                },
                onOpenRecovery = { showAdbRecovery = true },
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
                onCheckAdbAccess = onCheckAdbAccess,
                onRequestAdbAuthorizationOnce = onRequestAdbAuthorizationOnce,
                onAllowNewAdbAuthorizationAttempt = onAllowNewAdbAuthorizationAttempt,
                onDismiss = { showAdbRecovery = false },
            )
        }
    }
}

private val NARROW_TRIP_PANEL_HEIGHT = DenzaMetrics.Component.PANEL_HEIGHT_NARROW

/** Средняя панель 2/3 скроллит по вертикали; нижней панели отдаётся фиксированная высота
 *  порядка свободной зоны полного дашборда (правка W8; живой прогон уточнит). */
private val MEDIUM_TRIP_PANEL_HEIGHT = DenzaMetrics.Component.PANEL_HEIGHT_MEDIUM

@Composable
private fun AdbStartupOverlay(
    model: AdbStartupOverlayModel,
    onPrimaryAction: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(DenzaMetrics.Space.XL),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.72f),
            shape = RoundedCornerShape(DenzaMetrics.Space.XL),
            border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.SurfaceRaised),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = DenzaMetrics.Space.XXL, vertical = DenzaMetrics.Space.XL),
                verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.L),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.L),
                ) {
                    if (model.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(DenzaMetrics.Component.SEGMENT_HEIGHT),
                            color = DenzaColors.Accent,
                            strokeWidth = DenzaMetrics.Space.XS,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Build,
                            contentDescription = null,
                            modifier = Modifier.size(DenzaMetrics.Space.XXL),
                            tint = if (model.recoveryAvailable) DenzaColors.Warning else DenzaColors.Muted,
                        )
                    }
                    Text(
                        model.title,
                        color = DenzaColors.Ink,
                        fontSize = DenzaMetrics.Type.TITLE,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    model.message,
                    color = DenzaColors.Muted,
                    fontSize = DenzaMetrics.Type.LABEL,
                    lineHeight = DenzaMetrics.Type.SECTION,
                )
                if (model.primaryLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M, Alignment.End),
                    ) {
                        if (model.recoveryAvailable) {
                            OutlinedButton(
                                onClick = onOpenRecovery,
                                border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.Warning),
                            ) {
                                Text("Восстановить ADB", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button(
                            onClick = onPrimaryAction,
                        ) {
                            Text(model.primaryLabel, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdbRecoveryDialog(
    state: DenzaUiState,
    onCheckAdbAccess: () -> Unit,
    onRequestAdbAuthorizationOnce: () -> Unit,
    onAllowNewAdbAuthorizationAttempt: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.68f),
            shape = RoundedCornerShape(DenzaMetrics.Space.XL),
            border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.SurfaceRaised),
        ) {
            Column(
                modifier = Modifier.padding(DenzaMetrics.Space.XL),
                verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.L),
            ) {
                Text(
                    "Восстановление ADB",
                    color = DenzaColors.Ink,
                    fontSize = DenzaMetrics.Type.SECTION,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(state.adbRescue.message, color = DenzaColors.Ink, fontSize = DenzaMetrics.Type.LABEL)
                state.adbRescue.details?.let { details ->
                    Text(details, color = DenzaColors.Muted, fontSize = DenzaMetrics.Type.BODY)
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCheckAdbAccess,
                    enabled = state.adbRescue.phase != AdbRescuePhase.CHECKING &&
                        state.adbRescue.phase != AdbRescuePhase.REQUESTING,
                ) {
                    Text("Проверить доступ", fontWeight = FontWeight.SemiBold)
                }
                if (state.adbRescue.canRequest) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRequestAdbAuthorizationOnce,
                    ) {
                        Text("Отправить один запрос", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (state.adbRescue.canResetAttempt) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onAllowNewAdbAuthorizationAttempt,
                    ) {
                        Text("Разрешить новую попытку", color = DenzaColors.Warning)
                    }
                }
                Text(
                    AdbRescueCoordinator.QUEUE_RECOVERY_STATUS,
                    color = DenzaColors.Warning,
                    fontSize = DenzaMetrics.Type.BODY,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Закрыть", color = DenzaColors.Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
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
    onSetStockRussianLocaleEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = if (compactLayout) Modifier.fillMaxWidth(0.96f)
            else Modifier.fillMaxWidth(0.72f),
            color = DenzaColors.Surface,
            shape = RoundedCornerShape(DenzaMetrics.Space.XL),
        ) {
            Column(modifier = Modifier.padding(if (compactLayout) DenzaMetrics.Space.L else DenzaMetrics.Space.XL)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Build, null, tint = DenzaColors.Accent)
                    Spacer(Modifier.width(DenzaMetrics.Space.M))
                    Text(
                        "Диагностика",
                        color = DenzaColors.Ink,
                        fontSize = DenzaMetrics.Type.SECTION,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(DenzaMetrics.Space.L))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = DenzaMetrics.Component.PICKER_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.S),
                ) {
                    val adbBusy = state.adbRescue.phase == AdbRescuePhase.CHECKING ||
                        state.adbRescue.phase == AdbRescuePhase.REQUESTING
                    Text("ADB Rescue", color = DenzaColors.Ink, fontWeight = FontWeight.SemiBold)
                    DenzaKeyValueRow(
                        label = "Состояние",
                        value = state.adbRescue.message,
                        stacked = compactLayout,
                    )
                    state.adbRescue.details?.let { details ->
                        Text(details, color = DenzaColors.Muted, fontSize = DenzaMetrics.Type.BODY)
                    }
                    OutlinedButton(
                        onClick = onCheckAdbAccess,
                        enabled = !adbBusy,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.SurfaceRaised),
                    ) {
                        Text("Проверить доступ")
                    }
                    if (state.adbRescue.canRequest) {
                        Button(
                            onClick = onRequestAdbAuthorizationOnce,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Отправить один запрос", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (state.adbRescue.canResetAttempt) {
                        TextButton(
                            onClick = onAllowNewAdbAuthorizationAttempt,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("Разрешить новую попытку", color = DenzaColors.Warning)
                        }
                    }
                    Text(
                        AdbRescueCoordinator.QUEUE_RECOVERY_STATUS,
                        color = DenzaColors.Warning,
                        fontSize = DenzaMetrics.Type.BODY,
                    )
                    Spacer(Modifier.height(DenzaMetrics.Space.M))
                    Text("Штатный русский", color = DenzaColors.Ink, fontWeight = FontWeight.SemiBold)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = DenzaColors.SurfaceRaised,
                        shape = RoundedCornerShape(DenzaMetrics.Space.M),
                    ) {
                        Column {
                            val localeControlEnabled =
                                state.stockRussianLocale.permissionReady ||
                                    state.adbRescue.phase == AdbRescuePhase.TRUSTED
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "Русский в BYD Настройках",
                                        color = DenzaColors.Ink,
                                        fontWeight = FontWeight.Medium,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        state.stockRussianLocale.message,
                                        color = DenzaColors.Muted,
                                        fontSize = DenzaMetrics.Type.BODY,
                                    )
                                },
                                trailingContent = {
                                    when {
                                        state.stockRussianLocale.running -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(DenzaMetrics.Space.XL),
                                                color = DenzaColors.Accent,
                                                strokeWidth = DenzaMetrics.Space.XS,
                                            )
                                        }
                                        state.stockRussianLocale.enabled != null -> {
                                            Switch(
                                                checked = state.stockRussianLocale.enabled,
                                                onCheckedChange = onSetStockRussianLocaleEnabled,
                                                enabled = localeControlEnabled,
                                            )
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                ),
                            )
                            if (
                                !state.stockRussianLocale.running &&
                                state.stockRussianLocale.enabled == null
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = DenzaMetrics.Space.L, end = DenzaMetrics.Space.L, bottom = DenzaMetrics.Space.M),
                                    horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.S),
                                ) {
                                    OutlinedButton(
                                        onClick = { onSetStockRussianLocaleEnabled(false) },
                                        enabled = localeControlEnabled,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Выкл")
                                    }
                                    Button(
                                        onClick = { onSetStockRussianLocaleEnabled(true) },
                                        enabled = localeControlEnabled,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Вкл", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Только ru-RU для com.byd.carsettings; без словаря и перевода поверх.",
                        color = DenzaColors.Muted,
                        fontSize = DenzaMetrics.Type.BODY,
                    )
                    state.stockRussianLocale.details?.let { details ->
                        Text(details, color = DenzaColors.Warning, fontSize = DenzaMetrics.Type.BODY)
                    }
                    Spacer(Modifier.height(DenzaMetrics.Space.M))
                    Text("Технические сведения", color = DenzaColors.Ink, fontWeight = FontWeight.SemiBold)
                    state.technicalDetails
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            DenzaKeyValueRow(
                                label = line.substringBefore('='),
                                value = line.substringAfter('=', missingDelimiterValue = "—"),
                                stacked = compactLayout,
                            )
                        }
                    Spacer(Modifier.height(DenzaMetrics.Space.S))
                    Text("Выбор экрана приборки", color = DenzaColors.Ink, fontWeight = FontWeight.SemiBold)
                    state.clusterCandidates
                        .filter { it.id != 0 && !it.isOwnVirtualDisplay }
                        .forEach { display ->
                            OutlinedButton(
                                onClick = { onSelectClusterDisplay(display.id) },
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.SurfaceRaised),
                            ) {
                                Text("#${display.id} · ${display.width}×${display.height} · ${display.name}")
                            }
                        }
                    TextButton(
                        onClick = { onSelectClusterDisplay(null) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Определять автоматически", color = DenzaColors.Accent)
                    }
                }
                Spacer(Modifier.height(DenzaMetrics.Space.L))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onDismiss,
                    ) {
                        Text("Закрыть", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterDisplayPickerDialog(
    displays: List<ClusterDisplayDescriptor>,
    compactLayout: Boolean,
    onSelect: (Int?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = displays.filter { it.id != 0 && !it.isOwnVirtualDisplay }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = if (compactLayout) Modifier.fillMaxWidth(0.96f)
            else Modifier.fillMaxWidth(0.56f),
            color = DenzaColors.Surface,
            shape = RoundedCornerShape(DenzaMetrics.Space.XL),
        ) {
            Column(modifier = Modifier.padding(if (compactLayout) DenzaMetrics.Space.L else DenzaMetrics.Space.XL)) {
                Text(
                    "Выберите приборный экран",
                    color = DenzaColors.Ink,
                    fontSize = DenzaMetrics.Type.SECTION,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(DenzaMetrics.Space.S))
                Text(
                    "После выбора на экране появится короткая проверка",
                    color = DenzaColors.Muted,
                    fontSize = DenzaMetrics.Type.BODY,
                )
                Spacer(Modifier.height(DenzaMetrics.Space.L))
                if (choices.isEmpty()) {
                    Text(
                        "Приборные экраны пока не найдены",
                        color = DenzaColors.Warning,
                        fontSize = DenzaMetrics.Type.BODY,
                    )
                    Spacer(Modifier.height(DenzaMetrics.Space.L))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRefresh,
                    ) {
                        Text("Повторить поиск", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = DenzaMetrics.Component.PICKER_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
                    ) {
                        choices.forEachIndexed { index, display ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onSelect(display.id) },
                                border = BorderStroke(DenzaMetrics.Stroke.HAIRLINE, DenzaColors.SurfaceRaised),
                            ) {
                                Text(
                                    "Экран ${index + 1} · ${display.width}×${display.height}",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(DenzaMetrics.Space.M))
                if (compactLayout) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        TextButton(onClick = { onSelect(null) }) {
                            Text("Определять автоматически", color = DenzaColors.Accent)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Отмена", color = DenzaColors.Muted)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onSelect(null) }) {
                            Text("Определять автоматически", color = DenzaColors.Accent)
                        }
                        Spacer(Modifier.width(DenzaMetrics.Space.S))
                        TextButton(onClick = onDismiss) {
                            Text("Отмена", color = DenzaColors.Muted)
                        }
                    }
                }
            }
        }
    }
}
