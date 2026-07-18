package dev.denza.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
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

@Composable
fun DenzaAppsRoot(
    state: StateFlow<DenzaUiState>,
    onToggleSimulcast: (Boolean) -> Unit,
    onRepairSimulcast: () -> Unit,
    onToggleMirrors: (Boolean) -> Unit,
    onMirrorsPosition: (MirrorsPosition) -> Unit,
    onMirrorsProcessing: (Boolean) -> Unit,
    onPreviewMirrors: () -> Unit,
    onNavigationAction: () -> Unit,
    onChooseApps: () -> Unit,
) {
    val uiState by state.collectAsState()
    var showSupport by remember { mutableStateOf(false) }
    var showTechnical by remember { mutableStateOf(false) }
    var titleTaps by remember { mutableIntStateOf(0) }

    DenzaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp, vertical = 34.dp),
            ) {
                Header(
                    onTitleTap = {
                        titleTaps += 1
                        if (titleTaps >= 7) {
                            titleTaps = 0
                            showTechnical = true
                            showSupport = true
                        }
                    },
                    onSupport = { showSupport = true },
                )
                Spacer(Modifier.height(30.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    FeatureCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Apps,
                        title = "Приложения",
                        subtitle = "${uiState.selectedAppCount} выбрано",
                        snapshot = uiState.simulcast,
                        switchValue = uiState.simulcast.desiredEnabled,
                        onSwitch = onToggleSimulcast,
                    ) {
                        Button(
                            onClick = onChooseApps,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Color(0xFF06251C),
                            ),
                        ) {
                            Text("Выбрать приложения", fontWeight = FontWeight.SemiBold)
                        }
                        if (uiState.simulcast.status == FeatureStatus.NEEDS_ACTION) {
                            Spacer(Modifier.width(10.dp))
                            OutlinedButton(onClick = onRepairSimulcast) { Text("Исправить") }
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MirrorChoice(
                                    text = "По сторонам",
                                    selected = uiState.mirrorsPosition == MirrorsPosition.SIDES,
                                    onClick = { onMirrorsPosition(MirrorsPosition.SIDES) },
                                )
                                MirrorChoice(
                                    text = "По центру",
                                    selected = uiState.mirrorsPosition == MirrorsPosition.CENTER,
                                    onClick = { onMirrorsPosition(MirrorsPosition.CENTER) },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Обработка", color = Muted, fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = uiState.mirrorsProcessing,
                                    onCheckedChange = onMirrorsProcessing,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onPreviewMirrors) { Text("Проверить") }
                            }
                        }
                    }
                    FeatureCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Map,
                        title = "Навигация",
                        subtitle = "Яндекс на приборке",
                        snapshot = uiState.navigation,
                    ) {
                        Button(
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
                Spacer(Modifier.weight(1f))
                Footer(uiState)
            }
        }
    }

    if (showSupport) {
        SupportDialog(
            state = uiState,
            showTechnical = showTechnical,
            onDismiss = {
                showSupport = false
                showTechnical = false
            },
        )
    }
}

@Composable
private fun MirrorChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Elevated, contentColor = Accent),
        ) { Text(text, fontSize = 12.sp) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text, fontSize = 12.sp) }
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
private fun Header(onTitleTap: () -> Unit, onSupport: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Accent, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(13.dp).background(Color(0xFF06251C), CircleShape))
        }
        Spacer(Modifier.width(15.dp))
        Column(modifier = Modifier.clickable(onClick = onTitleTap)) {
            Text("Denza Apps", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text("Всё нужное для автомобиля", color = Muted, fontSize = 14.sp)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSupport) {
            Icon(Icons.Outlined.Info, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Помощь")
        }
    }
}

@Composable
private fun FeatureCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    snapshot: FeatureSnapshot,
    switchValue: Boolean? = null,
    onSwitch: ((Boolean) -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.height(350.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp).background(Elevated, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = Accent)
                }
                Spacer(Modifier.weight(1f))
                if (switchValue != null && onSwitch != null) {
                    Switch(checked = switchValue, onCheckedChange = onSwitch)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(26.dp))
            StatusLine(snapshot)
            if (snapshot.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(snapshot.message, color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, content = { actions() })
        }
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
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = color,
            )
        } else {
            Box(Modifier.size(10.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(9.dp))
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
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun Footer(state: DenzaUiState) {
    val ready = listOf(state.simulcast, state.mirrors, state.navigation).count { it.isWorking }
    Text(
        if (ready == 0) "Функции включаются независимо" else "$ready из 3 функций готовы",
        color = Muted,
        fontSize = 13.sp,
    )
}

@Composable
private fun SupportDialog(state: DenzaUiState, showTechnical: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (showTechnical) Icons.Outlined.Build else Icons.Outlined.Info, null) },
        title = { Text(if (showTechnical) "Диагностика" else "Denza Apps") },
        text = {
            Column {
                Text(
                    if (showTechnical) state.technicalDetails
                    else "Приложение самостоятельно восстанавливает необходимые службы. Если функция просит действие, подтвердите ADB-ключ на экране автомобиля и нажмите «Исправить».",
                    fontFamily = if (showTechnical) FontFamily.Monospace else FontFamily.Default,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
