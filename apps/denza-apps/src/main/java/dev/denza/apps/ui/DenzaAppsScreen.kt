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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import dev.denza.apps.DenzaUiState
import dev.denza.apps.feature.split.SplitScreenFlag
import dev.denza.apps.feature.trip.TripPanelFlag
import dev.denza.apps.feature.trip.TripPanelView
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.feature.mirrors.MirrorsPosition
import kotlinx.coroutines.flow.StateFlow

private val Background = Color(0xFF080B0D)
private val SurfaceColor = Color(0xFF12171B)
private val Elevated = Color(0xFF192126)
private val Ink = Color(0xFFF3F7F8)
private val Muted = Color(0xFF9AA7AD)
private val Accent = Color(0xFF73E0BD)
private val Warning = Color(0xFFF2C46D)
private val Danger = Color(0xFFFF8B91)
private val DisabledSurface = Color(0xFF181A1B)
private val DisabledElevated = Color(0xFF222426)
private val DisabledInk = Color(0xFFB7BCBE)
private val DisabledMuted = Color(0xFF7D8487)
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
    // Hidden diagnostics entry: 7 quick taps on the Трансляция card header, each
    // within 3 s of the previous, opens the diagnostics dialog. No affordance.
    var diagnosticsTaps by remember { mutableIntStateOf(0) }
    var lastDiagnosticsTapMs by remember { mutableLongStateOf(0L) }
    val onTransmissionHeaderTap = {
        val now = System.currentTimeMillis()
        diagnosticsTaps = if (now - lastDiagnosticsTapMs <= 3000L) diagnosticsTaps + 1 else 1
        lastDiagnosticsTapMs = now
        if (diagnosticsTaps >= 7) {
            diagnosticsTaps = 0
            onRefreshScreenDiagnostics()
            showDiagnostics = true
        }
    }
    val selectedNavigationApp = uiState.navigationAppChoices.firstOrNull { it.selected }
    val navigationActions = FeatureActionPolicy.navigation(
        uiState.navigation,
        uiState.navigationButtonLabel,
    )
    val simulcastActions = FeatureActionPolicy.simulcast(uiState.simulcast)
    val mirrorAction = FeatureActionPolicy.mirrors(uiState.mirrors)
    val openClusterPicker = {
        onRefreshScreenDiagnostics()
        showClusterPicker = true
    }

    DenzaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 48.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                    FeatureCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Map,
                        title = "Навигация",
                        subtitle = uiState.navigationAppLabel,
                        subtitleIcon = selectedNavigationApp?.icon,
                        subtitleIconKey = selectedNavigationApp?.packageName,
                        snapshot = uiState.navigation,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            StandardSegmentedChoiceRow(
                                modifier = Modifier.fillMaxWidth(),
                                labels = ClusterMapPlacement.entries.map { placement ->
                                    when (placement) {
                                        ClusterMapPlacement.FULL -> "Полный"
                                        ClusterMapPlacement.LEFT -> "Слева"
                                        ClusterMapPlacement.CENTER -> "Центр"
                                        ClusterMapPlacement.RIGHT -> "Справа"
                                    }
                                },
                                selectedIndex = ClusterMapPlacement.entries.indexOf(
                                    uiState.navigationPlacement,
                                ),
                                enabled = uiState.navigation.status != FeatureStatus.STARTING &&
                                    uiState.navigation.status != FeatureStatus.RECOVERING,
                                onSelect = { index ->
                                    onNavigationPlacement(ClusterMapPlacement.entries[index])
                                },
                            )
                            if (SHOW_NAVIGATION_AUTOMATIC) {
                                Spacer(Modifier.height(10.dp))
                                SettingsSwitchRow(
                                    title = "Авто",
                                    subtitle = "По режиму приборки",
                                    checked = uiState.navigationAutomatic,
                                    onCheckedChange = onNavigationAutomatic,
                                    controlEnabled = uiState.navigation.status != FeatureStatus.STARTING &&
                                        uiState.navigation.status != FeatureStatus.RECOVERING,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            SettingsSwitchRow(
                                title = "Кнопка ★ на руле",
                                subtitle = "1× навигация · 2× передняя камера",
                                checked = uiState.navigationSteeringWheelButton,
                                onCheckedChange = onNavigationSteeringWheelButton,
                                controlEnabled = uiState.navigation.status != FeatureStatus.STARTING &&
                                    uiState.navigation.status != FeatureStatus.RECOVERING,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FeatureChooserButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = onChooseNavigationApp,
                                    emphasized = navigationActions.chooserEmphasized,
                                    enabled = uiState.navigation.status != FeatureStatus.STARTING &&
                                        uiState.navigation.status != FeatureStatus.RECOVERING &&
                                        uiState.navigation.status != FeatureStatus.ACTIVE,
                                    text = "Выбрать",
                                )
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = when (navigationActions.primaryTarget) {
                                        FeatureActionTarget.SELECT_CLUSTER_DISPLAY -> openClusterPicker
                                        else -> onNavigationAction
                                    },
                                    enabled = navigationActions.primaryEnabled &&
                                        uiState.navigation.status != FeatureStatus.STARTING &&
                                        uiState.navigation.status != FeatureStatus.RECOVERING,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Accent,
                                        contentColor = Color(0xFF06251C),
                                    ),
                                ) {
                                    Text(navigationActions.primaryLabel, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    FeatureCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Apps,
                        title = "Трансляция",
                        subtitle = "Приложения на экранах",
                        snapshot = uiState.simulcast,
                        switchValue = uiState.simulcast.desiredEnabled,
                        onSwitch = onToggleSimulcast,
                        actionsFillRemaining = true,
                        onHeaderTap = onTransmissionHeaderTap,
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            SelectedSimulcastApps(uiState.selectedApps)
                            Spacer(Modifier.weight(1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FeatureChooserButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = onChooseApps,
                                    emphasized = simulcastActions.chooserEmphasized,
                                    text = "Выбрать",
                                )
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = when (simulcastActions.primaryTarget) {
                                        FeatureActionTarget.RETRY -> onRepairSimulcast
                                        else -> onLaunchSimulcast
                                    },
                                    enabled = simulcastActions.primaryEnabled,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (uiState.simulcast.desiredEnabled) {
                                            Accent
                                        } else {
                                            DisabledElevated
                                        },
                                        contentColor = if (uiState.simulcast.desiredEnabled) {
                                            Color(0xFF06251C)
                                        } else {
                                            DisabledInk
                                        },
                                    ),
                                ) {
                                    Text(
                                        simulcastActions.primaryLabel,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                    FeatureCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Visibility,
                        title = "Зеркала",
                        subtitle = "Камеры поворотников",
                        snapshot = uiState.mirrors,
                        switchValue = uiState.mirrors.desiredEnabled,
                        onSwitch = onToggleMirrors,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            StandardSegmentedChoiceRow(
                                modifier = Modifier.fillMaxWidth(),
                                labels = listOf("По сторонам", "По центру"),
                                selectedIndex = when (uiState.mirrorsPosition) {
                                    MirrorsPosition.SIDES -> 0
                                    MirrorsPosition.CENTER -> 1
                                },
                                contentActive = uiState.mirrors.desiredEnabled,
                                onSelect = { index ->
                                    onMirrorsPosition(
                                        if (index == 0) MirrorsPosition.SIDES
                                        else MirrorsPosition.CENTER,
                                    )
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            SettingsSwitchRow(
                                title = "Улучшение изображения",
                                subtitle = "Ярче и контрастнее",
                                checked = uiState.mirrorsProcessing,
                                onCheckedChange = onMirrorsProcessing,
                                contentActive = uiState.mirrors.desiredEnabled,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = when (mirrorAction.target) {
                                        FeatureActionTarget.SELECT_CLUSTER_DISPLAY -> openClusterPicker
                                        else -> onPreviewMirrors
                                    },
                                    enabled = mirrorAction.enabled,
                                ) {
                                    Text(mirrorAction.label, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        SplitScreenCard(
                            modifier = Modifier.weight(1f),
                            snapshot = uiState.splitScreen,
                            onToggle = onToggleSplitScreen,
                        )
                        CompactToggleCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Map,
                            title = "HUD-подсказки",
                            subtitle = "Указания на проекции",
                            snapshot = uiState.hudGuidance,
                            onToggle = onToggleHudGuidance,
                            onRetry = { onToggleHudGuidance(true) },
                        )
                        CompactActionCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.InstallMobile,
                            title = "Установить приложение",
                            subtitle = uiState.fseInstaller.message.ifBlank {
                                "На пассажирский экран"
                            },
                            snapshot = uiState.fseInstaller,
                            onClick = onChooseFseApp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // Trip panel lives in the free zone below the cards, drawn
                    // straight on the background with no card/border/frame. Gated
                    // by the compile-time TripPanelFlag: when off, the space is
                    // simply empty and no sensors/location run.
                    if (TripPanelFlag.ENABLED) {
                        AndroidView(
                            factory = { ctx -> TripPanelView(ctx) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            state = uiState,
            onSelectClusterDisplay = onSelectClusterDisplay,
            onDismiss = { showDiagnostics = false },
        )
    }
    if (showClusterPicker) {
        ClusterDisplayPickerDialog(
            displays = uiState.clusterCandidates,
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
            selectedCount = uiState.selectedAppCount,
            message = uiState.appPickerMessage,
            onToggle = onToggleApp,
            onDismiss = onCloseAppPicker,
        )
    }
    if (uiState.navigationPickerVisible) {
        NavigationPickerDialog(
            apps = uiState.navigationAppChoices,
            onSelect = onSelectNavigationApp,
            onDismiss = onCloseNavigationPicker,
        )
    }
    if (uiState.fseInstallerPickerVisible) {
        FseInstallerPickerDialog(
            apps = uiState.fseInstallApps,
            message = uiState.fseInstallerMessage,
            onInstall = onInstallFseApp,
            onDismiss = onCloseFseInstallerPicker,
        )
    }
}

private const val SPLIT_UNAVAILABLE = "Недоступно в этой прошивке"

@Composable
private fun SplitScreenCard(
    modifier: Modifier,
    snapshot: FeatureSnapshot,
    onToggle: (Boolean) -> Unit,
) {
    if (!SplitScreenFlag.ENABLED) {
        // Shown, off and not operable: the feature is gone from the firmware,
        // not from the app, and a card that simply vanished would read as a bug.
        CompactToggleCard(
            modifier = modifier,
            icon = Icons.Outlined.VerticalSplit,
            title = "Split screen",
            subtitle = SPLIT_UNAVAILABLE,
            snapshot = snapshot.copy(
                desiredEnabled = false,
                status = FeatureStatus.UNAVAILABLE,
                message = SPLIT_UNAVAILABLE,
            ),
            onToggle = {},
            toggleEnabled = false,
        )
        return
    }
    val subtitle = if (snapshot.status == FeatureStatus.ERROR) "Ошибка запуска" else "Управление окнами"
    CompactToggleCard(
        modifier = modifier,
        icon = Icons.Outlined.VerticalSplit,
        title = "Split screen",
        subtitle = subtitle,
        snapshot = snapshot,
        onToggle = onToggle,
    )
}

@Composable
private fun CompactToggleCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    snapshot: FeatureSnapshot,
    onToggle: (Boolean) -> Unit,
    onRetry: (() -> Unit)? = null,
    toggleEnabled: Boolean = true,
) {
    val enabled = snapshot.desiredEnabled
    val attentionSubtitle = when (snapshot.status) {
        FeatureStatus.NEEDS_ACTION,
        FeatureStatus.UNAVAILABLE,
        FeatureStatus.ERROR,
        -> snapshot.message.ifBlank { subtitle }
        else -> subtitle
    }
    val subtitleColor = when (snapshot.status) {
        FeatureStatus.NEEDS_ACTION -> Warning
        FeatureStatus.ERROR -> Danger
        FeatureStatus.UNAVAILABLE -> Muted
        else -> if (enabled) Muted else DisabledMuted
    }
    val retryLabel = FeatureActionPolicy.compactRetryLabel(snapshot)
    Card(
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) SurfaceColor else DisabledSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (enabled) Elevated else DisabledElevated,
                        RoundedCornerShape(14.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    null,
                    tint = if (enabled) Accent else DisabledInk,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (enabled) Ink else DisabledInk,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    attentionSubtitle,
                    color = subtitleColor,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snapshot.status == FeatureStatus.STARTING || snapshot.status == FeatureStatus.RECOVERING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Warning,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (retryLabel != null && onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(retryLabel, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(4.dp))
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = toggleEnabled &&
                    snapshot.status != FeatureStatus.STARTING &&
                    snapshot.status != FeatureStatus.RECOVERING,
            )
        }
    }
}

@Composable
private fun FeatureChooserButton(
    modifier: Modifier,
    text: String,
    emphasized: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (emphasized) {
        Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color(0xFF06251C),
            ),
        ) {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CompactActionCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    snapshot: FeatureSnapshot,
    onClick: () -> Unit,
) {
    val busy = snapshot.status == FeatureStatus.STARTING ||
        snapshot.status == FeatureStatus.RECOVERING
    val failed = snapshot.status == FeatureStatus.ERROR
    Card(
        modifier = modifier
            .height(96.dp)
            .clickable(enabled = !busy, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Elevated, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (failed) Warning else Accent)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    subtitle,
                    color = if (failed) Warning else Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Warning,
                )
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<SimulcastAppChoice>,
    selectedCount: Int,
    message: String,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = SurfaceColor,
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            "Приложения на экранах",
                            color = Ink,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Можно выбрать до 6 · выбрано $selectedCount", color = Muted, fontSize = 14.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                    ) {
                        Text("Готово")
                    }
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = Warning, fontSize = 14.sp)
                }
                Spacer(Modifier.height(18.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        AppChoiceTile(
                            app = app,
                            onClick = { onToggle(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppChoiceTile(app: SimulcastAppChoice, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val bitmap = remember(app.packageName, app.icon) {
        app.icon?.toBitmap(128, 128)?.asImageBitmap()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(114.dp)
            .then(if (app.selected) Modifier.border(2.dp, Accent, shape) else Modifier)
            .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Elevated),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(Icons.Outlined.Apps, null, modifier = Modifier.size(50.dp), tint = Muted)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                app.label,
                color = if (app.selected) Accent else Ink,
                fontSize = 12.sp,
                fontWeight = if (app.selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun NavigationPickerDialog(
    apps: List<NavigationAppChoice>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.68f),
            color = SurfaceColor,
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            "Навигация на приборке",
                            color = Ink,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Установленные приложения", color = Muted, fontSize = 14.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                Spacer(Modifier.height(18.dp))
                if (apps.isEmpty()) {
                    Text("Поддерживаемые навигаторы не найдены", color = Warning, fontSize = 15.sp)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            NavigationChoiceTile(app = app) { onSelect(app.packageName) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationChoiceTile(app: NavigationAppChoice, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val bitmap = remember(app.packageName, app.icon) {
        app.icon?.toBitmap(128, 128)?.asImageBitmap()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .then(if (app.selected) Modifier.border(2.dp, Accent, shape) else Modifier)
            .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Elevated),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(Icons.Outlined.Map, null, modifier = Modifier.size(50.dp), tint = Muted)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                app.label,
                color = if (app.selected) Accent else Ink,
                fontSize = 13.sp,
                fontWeight = if (app.selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun FseInstallerPickerDialog(
    apps: List<FseInstallApp>,
    message: String,
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = SurfaceColor,
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            "Установка на пассажирский экран",
                            color = Ink,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Выберите приложение с головного устройства",
                            color = Muted,
                            fontSize = 14.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = Warning, fontSize = 14.sp)
                }
                Spacer(Modifier.height(18.dp))
                if (apps.isEmpty()) {
                    Text("Приложения не найдены", color = Warning, fontSize = 15.sp)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxWidth().height(380.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            FseInstallChoiceTile(app = app) { onInstall(app.packageName) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FseInstallChoiceTile(app: FseInstallApp, onClick: () -> Unit) {
    val bitmap = remember(app.packageName, app.icon) {
        app.icon?.toBitmap(128, 128)?.asImageBitmap()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.installable) Elevated else DisabledSurface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    Icons.Outlined.Apps,
                    null,
                    modifier = Modifier.size(46.dp),
                    tint = DisabledMuted,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                app.label,
                color = if (app.installable) Ink else DisabledInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (app.installable) app.versionName.ifBlank { "Готово к установке" }
                else app.unavailableReason,
                color = if (app.installable) Muted else DisabledMuted,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StandardSegmentedChoiceRow(
    modifier: Modifier,
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    contentActive: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Surface(
        modifier = modifier.height(42.dp),
        color = if (contentActive) Background else DisabledSurface,
        shape = RoundedCornerShape(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxSize().padding(3.dp),
            space = 3.dp,
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                SegmentedButton(
                    modifier = Modifier.weight(1f),
                    selected = selected,
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    shape = RoundedCornerShape(9.dp),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = if (contentActive) Elevated else DisabledElevated,
                        activeContentColor = if (contentActive) Accent else DisabledInk,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = if (contentActive) Ink else DisabledInk,
                    ),
                    border = BorderStroke(0.dp, Color.Transparent),
                    label = {
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    controlEnabled: Boolean = true,
    contentActive: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (contentActive) Elevated else DisabledElevated,
        shape = RoundedCornerShape(14.dp),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    title,
                    color = if (contentActive) Ink else DisabledInk,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            },
            supportingContent = {
                Text(
                    subtitle,
                    color = if (contentActive) Muted else DisabledMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            },
            trailingContent = {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = controlEnabled,
                    colors = if (contentActive) {
                        SwitchDefaults.colors()
                    } else {
                        SwitchDefaults.colors(
                            checkedThumbColor = DisabledInk,
                            checkedTrackColor = DisabledMuted,
                        )
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun DenzaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF06251C),
            background = Background,
            surface = SurfaceColor,
            onSurface = Ink,
            error = Danger,
        ),
        content = content,
    )
}

@Composable
private fun DiagnosticsDialog(
    state: DenzaUiState,
    onSelectClusterDisplay: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.72f),
            color = SurfaceColor,
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Build, null, tint = Accent)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Диагностика",
                        color = Ink,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(22.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.technicalDetails
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            DiagnosticRow(
                                label = line.substringBefore('='),
                                value = line.substringAfter('=', missingDelimiterValue = "—"),
                            )
                        }
                    Spacer(Modifier.height(8.dp))
                    Text("Выбор экрана приборки", color = Ink, fontWeight = FontWeight.SemiBold)
                    state.clusterCandidates
                        .filter { it.id != 0 && !it.isOwnVirtualDisplay }
                        .forEach { display ->
                            OutlinedButton(
                                onClick = { onSelectClusterDisplay(display.id) },
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, Elevated),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            ) {
                                Text("#${display.id} · ${display.width}×${display.height} · ${display.name}")
                            }
                        }
                    TextButton(
                        onClick = { onSelectClusterDisplay(null) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Определять автоматически", color = Accent)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color(0xFF06251C),
                        ),
                    ) {
                        Text("Закрыть", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Elevated,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.weight(0.42f),
            )
            Text(
                value,
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(0.58f),
            )
        }
    }
}

@Composable
private fun FeatureCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    subtitleIcon: Drawable? = null,
    subtitleIconKey: String? = null,
    snapshot: FeatureSnapshot,
    switchValue: Boolean? = null,
    switchEnabled: Boolean = true,
    onSwitch: ((Boolean) -> Unit)? = null,
    actionsFillRemaining: Boolean = false,
    onHeaderTap: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val featureEnabled = switchValue != false
    val headerInteraction = remember { MutableInteractionSource() }
    Card(
        modifier = modifier.height(314.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (featureEnabled) SurfaceColor else DisabledSurface,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (featureEnabled) Elevated else DisabledElevated,
                            RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        null,
                        modifier = Modifier.size(22.dp),
                        tint = if (featureEnabled) Accent else DisabledInk,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    color = if (featureEnabled) Ink else DisabledInk,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (onHeaderTap != null) {
                                Modifier.clickable(
                                    interactionSource = headerInteraction,
                                    indication = null,
                                    onClick = onHeaderTap,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    maxLines = 1,
                )
                if (switchValue != null && onSwitch != null) {
                    Switch(
                        checked = switchValue,
                        onCheckedChange = onSwitch,
                        enabled = switchEnabled,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val showAttentionInstruction =
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank()
            if (showAttentionInstruction) {
                AttentionInstruction(snapshot.message)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subtitleIcon != null) {
                        CompactAppIcon(
                            icon = subtitleIcon,
                            key = subtitleIconKey.orEmpty(),
                            fallback = icon,
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        subtitle,
                        color = if (featureEnabled) Muted else DisabledMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(10.dp))
                    StatusLine(snapshot)
                }
            }
            if (!showAttentionInstruction && snapshot.message.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    snapshot.message,
                    color = when (snapshot.status) {
                        FeatureStatus.ERROR -> Danger
                        else -> Muted
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (actionsFillRemaining) {
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) { actions() }
            } else {
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.fillMaxWidth()) { actions() }
            }
        }
    }
}

@Composable
private fun AttentionInstruction(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Warning, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            modifier = Modifier.weight(1f),
            color = Warning,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectedSimulcastApps(apps: List<SimulcastAppChoice>) {
    if (apps.isEmpty()) {
        Text("Приложения не выбраны", color = Muted, fontSize = 12.sp)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            apps.take(6).forEach { app ->
                Surface(
                    modifier = Modifier.size(32.dp),
                    color = Elevated,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CompactAppIcon(
                            icon = app.icon,
                            key = app.packageName,
                            fallback = Icons.Outlined.Apps,
                            size = 26.dp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "выбрано ${apps.size} из 6",
            color = Muted,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactAppIcon(
    icon: Drawable?,
    key: String,
    fallback: ImageVector,
    size: Dp = 18.dp,
) {
    val bitmap = remember(key, icon) {
        icon?.toBitmap(48, 48)?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    } else {
        Icon(fallback, null, modifier = Modifier.size(size), tint = Muted)
    }
}

@Composable
private fun StatusLine(snapshot: FeatureSnapshot) {
    val color = when (snapshot.status) {
        FeatureStatus.READY, FeatureStatus.ACTIVE -> Accent
        FeatureStatus.STARTING, FeatureStatus.RECOVERING -> Warning
        FeatureStatus.NEEDS_ACTION -> Warning
        FeatureStatus.ERROR -> Danger
        else -> Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (snapshot.status == FeatureStatus.STARTING || snapshot.status == FeatureStatus.RECOVERING) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = color,
            )
        } else {
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            when (snapshot.status) {
                FeatureStatus.OFF -> "Выключено"
                FeatureStatus.STARTING -> "Запускаю"
                FeatureStatus.READY -> "Готово"
                FeatureStatus.ACTIVE -> "Работает"
                FeatureStatus.RECOVERING -> "Восстанавливаю"
                FeatureStatus.NEEDS_ACTION -> "Нужно действие"
                FeatureStatus.UNAVAILABLE -> "Пока недоступно"
                FeatureStatus.ERROR -> "Ошибка"
            },
            color = color,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun ClusterDisplayPickerDialog(
    displays: List<ClusterDisplayDescriptor>,
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
            modifier = Modifier.fillMaxWidth(0.56f),
            color = SurfaceColor,
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Text(
                    "Выберите приборный экран",
                    color = Ink,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "После выбора на экране появится короткая проверка",
                    color = Muted,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(20.dp))
                if (choices.isEmpty()) {
                    Text(
                        "Приборные экраны пока не найдены",
                        color = Warning,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color(0xFF06251C),
                        ),
                    ) {
                        Text("Повторить поиск", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        choices.forEachIndexed { index, display ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onSelect(display.id) },
                                border = BorderStroke(1.dp, Elevated),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            ) {
                                Text(
                                    "Экран ${index + 1} · ${display.width}×${display.height}",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onSelect(null) }) {
                        Text("Определять автоматически", color = Accent)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Отмена", color = Muted)
                    }
                }
            }
        }
    }
}
