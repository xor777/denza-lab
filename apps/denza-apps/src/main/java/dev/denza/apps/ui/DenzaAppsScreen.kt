package dev.denza.apps.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import dev.denza.apps.DenzaUiState
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterMapPlacement
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
) {
    val uiState by state.collectAsState()
    var showSupport by remember { mutableStateOf(false) }
    var showTechnical by remember { mutableStateOf(false) }
    var titleTaps by remember { mutableIntStateOf(0) }
    val selectedNavigationApp = uiState.navigationAppChoices.firstOrNull { it.selected }

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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ClusterMapPlacement.entries.forEach { placement ->
                                    NavigationPlacementChoice(
                                        modifier = Modifier.weight(1f),
                                        text = when (placement) {
                                            ClusterMapPlacement.FULL -> "Полный"
                                            ClusterMapPlacement.LEFT -> "Слева"
                                            ClusterMapPlacement.CENTER -> "Центр"
                                            ClusterMapPlacement.RIGHT -> "Справа"
                                        },
                                        selected = uiState.navigationPlacement == placement,
                                        enabled = uiState.navigation.status != FeatureStatus.STARTING &&
                                            uiState.navigation.status != FeatureStatus.RECOVERING,
                                        onClick = { onNavigationPlacement(placement) },
                                    )
                                }
                            }
                            if (SHOW_NAVIGATION_AUTOMATIC) {
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text("Авто", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        Text("По режиму приборки", color = Muted, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = uiState.navigationAutomatic,
                                        onCheckedChange = onNavigationAutomatic,
                                        enabled = uiState.navigation.status != FeatureStatus.STARTING &&
                                            uiState.navigation.status != FeatureStatus.RECOVERING,
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = onChooseNavigationApp,
                                    enabled = uiState.navigation.status != FeatureStatus.STARTING &&
                                        uiState.navigation.status != FeatureStatus.RECOVERING &&
                                        uiState.navigation.status != FeatureStatus.ACTIVE,
                                ) {
                                    Text("Выбрать", fontWeight = FontWeight.SemiBold)
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = onNavigationAction,
                                    enabled = uiState.navigation.status != FeatureStatus.STARTING &&
                                        uiState.navigation.status != FeatureStatus.RECOVERING,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Accent,
                                        contentColor = Color(0xFF06251C),
                                    ),
                                ) {
                                    Text(uiState.navigationButtonLabel, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    FeatureCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Apps,
                        title = "Трансляция",
                        subtitle = "Выбрано: ${uiState.selectedAppCount}",
                        snapshot = uiState.simulcast,
                        switchValue = uiState.simulcast.desiredEnabled,
                        onSwitch = onToggleSimulcast,
                        actionsFillRemaining = true,
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            SelectedSimulcastApps(uiState.selectedApps)
                            Spacer(Modifier.weight(1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = onChooseApps,
                                ) {
                                    Text("Выбрать", fontWeight = FontWeight.SemiBold)
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = if (uiState.simulcast.status == FeatureStatus.NEEDS_ACTION &&
                                        uiState.simulcast.desiredEnabled
                                    ) onRepairSimulcast else onLaunchSimulcast,
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
                                        if (uiState.simulcast.status == FeatureStatus.NEEDS_ACTION &&
                                            uiState.simulcast.desiredEnabled
                                        ) "Исправить" else "Запустить",
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MirrorChoice(
                                    modifier = Modifier.weight(1f),
                                    text = "По сторонам",
                                    selected = uiState.mirrorsPosition == MirrorsPosition.SIDES,
                                    featureEnabled = uiState.mirrors.desiredEnabled,
                                    onClick = { onMirrorsPosition(MirrorsPosition.SIDES) },
                                )
                                MirrorChoice(
                                    modifier = Modifier.weight(1f),
                                    text = "По центру",
                                    selected = uiState.mirrorsPosition == MirrorsPosition.CENTER,
                                    featureEnabled = uiState.mirrors.desiredEnabled,
                                    onClick = { onMirrorsPosition(MirrorsPosition.CENTER) },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = if (uiState.mirrors.desiredEnabled) Elevated else DisabledElevated,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            "Улучшение изображения",
                                            color = if (uiState.mirrors.desiredEnabled) Ink else DisabledInk,
                                            fontSize = 13.sp,
                                        )
                                        Text(
                                            "Ярче и контрастнее",
                                            color = if (uiState.mirrors.desiredEnabled) Muted else DisabledMuted,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = uiState.mirrorsProcessing,
                                        onCheckedChange = onMirrorsProcessing,
                                        colors = if (uiState.mirrors.desiredEnabled) {
                                            SwitchDefaults.colors()
                                        } else {
                                            SwitchDefaults.colors(
                                                checkedThumbColor = DisabledInk,
                                                checkedTrackColor = DisabledMuted,
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = onPreviewMirrors,
                                ) {
                                    Text("Проверить камеры", fontWeight = FontWeight.SemiBold)
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
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.weight(1f))
                }
                CompactHelpButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(end = 40.dp, bottom = 8.dp),
                    onSupport = { showSupport = true },
                )
            }
        }
    }

    if (showSupport) {
        SupportDialog(
            state = uiState,
            showTechnical = showTechnical,
            onSelectClusterDisplay = onSelectClusterDisplay,
            onTitleTap = {
                titleTaps += 1
                if (titleTaps >= 7) {
                    titleTaps = 0
                    showTechnical = true
                    onRefreshScreenDiagnostics()
                }
            },
            onDismiss = {
                showSupport = false
                showTechnical = false
            },
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
}

@Composable
private fun SplitScreenCard(
    modifier: Modifier,
    snapshot: FeatureSnapshot,
    onToggle: (Boolean) -> Unit,
) {
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
) {
    val enabled = snapshot.desiredEnabled
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
            Column {
                Text(
                    title,
                    color = if (enabled) Ink else DisabledInk,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    color = if (enabled) Muted else DisabledMuted,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            if (snapshot.status == FeatureStatus.STARTING || snapshot.status == FeatureStatus.RECOVERING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Warning,
                )
                Spacer(Modifier.width(8.dp))
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = snapshot.status != FeatureStatus.STARTING &&
                    snapshot.status != FeatureStatus.RECOVERING,
            )
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
private fun MirrorChoice(
    modifier: Modifier,
    text: String,
    selected: Boolean,
    featureEnabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            modifier = modifier,
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (featureEnabled) Elevated else DisabledElevated,
                contentColor = if (featureEnabled) Accent else DisabledInk,
            ),
        ) { Text(text, fontSize = 12.sp) }
    } else {
        OutlinedButton(modifier = modifier, onClick = onClick) { Text(text, fontSize = 12.sp) }
    }
}

@Composable
private fun NavigationPlacementChoice(
    modifier: Modifier,
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(38.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Elevated else Color.Transparent,
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> DisabledMuted
                selected -> Accent
                else -> Elevated
            },
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = when {
                    !enabled -> DisabledMuted
                    selected -> Accent
                    else -> Ink
                },
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
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
private fun CompactHelpButton(
    modifier: Modifier,
    onSupport: () -> Unit,
) {
    TextButton(modifier = modifier, onClick = onSupport) {
        Icon(Icons.Outlined.Info, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text("Помощь", fontSize = 13.sp)
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
    actions: @Composable () -> Unit,
) {
    val featureEnabled = switchValue != false
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
                    modifier = Modifier.weight(1f),
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
            if (snapshot.message.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    snapshot.message,
                    color = Muted,
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
private fun SelectedSimulcastApps(apps: List<SimulcastAppChoice>) {
    if (apps.isEmpty()) {
        Text("Приложения не выбраны", color = Muted, fontSize = 12.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        apps.take(6).forEach { app ->
            Row(
                modifier = Modifier.height(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactAppIcon(
                    icon = app.icon,
                    key = app.packageName,
                    fallback = Icons.Outlined.Apps,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    app.label,
                    color = Ink,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CompactAppIcon(
    icon: Drawable?,
    key: String,
    fallback: ImageVector,
) {
    val bitmap = remember(key, icon) {
        icon?.toBitmap(48, 48)?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Icon(fallback, null, modifier = Modifier.size(18.dp), tint = Muted)
    }
}

@Composable
private fun StatusLine(snapshot: FeatureSnapshot) {
    val color = when (snapshot.status) {
        FeatureStatus.READY, FeatureStatus.ACTIVE -> Accent
        FeatureStatus.STARTING, FeatureStatus.RECOVERING -> Warning
        FeatureStatus.NEEDS_ACTION, FeatureStatus.ERROR -> Danger
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
private fun SupportDialog(
    state: DenzaUiState,
    showTechnical: Boolean,
    onSelectClusterDisplay: (Int?) -> Unit,
    onTitleTap: () -> Unit,
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
                Row(
                    modifier = Modifier.clickable(enabled = !showTechnical, onClick = onTitleTap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (showTechnical) Icons.Outlined.Build else Icons.Outlined.Info,
                        null,
                        tint = Accent,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (showTechnical) "Диагностика" else "Как пользоваться",
                        color = Ink,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(22.dp))
                if (!showTechnical) {
                    Text(
                        "здесь пока ничего нет\nhttps://github.com/xor777/denza-lab",
                        color = Muted,
                    )
                } else {
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
