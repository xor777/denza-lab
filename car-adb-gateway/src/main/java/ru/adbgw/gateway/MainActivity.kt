package ru.adbgw.gateway

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

private val Background = Color(0xFFF1F3EE)
private val Ink = Color(0xFF17211B)
private val Muted = Color(0xFF667068)
private val Accent = Color(0xFFC9F27B)
private val AccentDark = Color(0xFF557925)
private val CardColor = Color(0xFFFAFBF8)
private val Danger = Color(0xFFB84036)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        GatewayRepository.initialize(this)
        val initial = GatewayRepository.state.value
        if (initial.registration != null && initial.enabled) GatewayService.start(this)
        setContent { GatewayTheme { GatewayApp() } }
    }
}

@Composable
private fun GatewayTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = Ink,
        onPrimary = Color.White,
        secondary = AccentDark,
        background = Background,
        surface = CardColor,
        onSurface = Ink,
        error = Danger,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun GatewayApp() {
    val context = LocalContext.current
    val state by GatewayRepository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var inviteCode by remember { mutableStateOf("") }
    var showEnrollmentWarning by remember { mutableStateOf(false) }
    var showPairingWarning by remember { mutableStateOf(false) }
    var showDisableConfirmation by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    var hiddenPairingRequestId by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!state.isEnrolled) GatewayRepository.prepareAdb()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 52.dp, vertical = 34.dp),
        ) {
            Header(state, onSupport = { showSupport = true })
            Spacer(Modifier.height(28.dp))
            if (!state.isEnrolled) {
                OnboardingScreen(
                    state = state,
                    code = inviteCode,
                    onCodeChanged = { inviteCode = normalizeCodeInput(it) },
                    onRetry = { scope.launch { GatewayRepository.prepareAdb() } },
                    onEnroll = { showEnrollmentWarning = true },
                )
            } else {
                MainDashboard(
                    state = state,
                    onPair = { showPairingWarning = true },
                    onDisable = { showDisableConfirmation = true },
                    onEnable = { scope.launch { GatewayRepository.enableRemoteAccess() } },
                )
            }
        }
    }

    if (showEnrollmentWarning) {
        FullAccessWarning(
            title = "Подключить автомобиль?",
            body = "После подключения доверенный компьютер сможет получить полный удалённый доступ к Android-системе автомобиля. Используйте только код, полученный от администратора сервиса.",
            confirm = "Подключить",
            onDismiss = { showEnrollmentWarning = false },
            onConfirm = {
                showEnrollmentWarning = false
                scope.launch { GatewayRepository.enroll(inviteCode) }
            },
        )
    }
    if (showPairingWarning) {
        FullAccessWarning(
            title = "Полный доступ к автомобилю",
            body = "Вводя следующий код на компьютере, вы даёте ему полный доступ к Android-системе автомобиля. Убедитесь, что передаёте код человеку, которому доверяете.",
            confirm = "Показать код",
            onDismiss = { showPairingWarning = false },
            onConfirm = {
                showPairingWarning = false
                hiddenPairingRequestId = null
                scope.launch { GatewayRepository.requestPairing() }
            },
        )
    }
    state.pairingWindow?.takeIf { it.requestId != hiddenPairingRequestId }?.let { pairing ->
        PairingCodeDialog(
            window = pairing,
            onDismiss = { hiddenPairingRequestId = pairing.requestId },
            onClose = { hiddenPairingRequestId = pairing.requestId },
        )
    }
    if (showDisableConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisableConfirmation = false },
            icon = { Icon(Icons.Outlined.LinkOff, null) },
            title = { Text("Отключить удалённый доступ?") },
            text = { Text("Текущее соединение и сессия компьютера будут закрыты. После перезапуска автомобиля доступ останется выключенным.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDisableConfirmation = false
                        scope.launch { GatewayRepository.disableRemoteAccess() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                ) { Text("Отключить") }
            },
            dismissButton = { TextButton(onClick = { showDisableConfirmation = false }) { Text("Отмена") } },
        )
    }
    if (showSupport) {
        SupportDialog(state = state, onDismiss = { showSupport = false })
    }
}

@Composable
private fun Header(state: GatewayUiState, onSupport: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Ink, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(13.dp).background(Accent, CircleShape))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Car ADB Gateway", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("Безопасный удалённый доступ", fontSize = 14.sp, color = Muted)
        }
        Spacer(Modifier.weight(1f))
        val chipColor by animateColorAsState(
            when {
                !state.enabled -> Color(0xFFE5E7E2)
                state.relayState == RelayState.Connected -> Accent
                else -> Color(0xFFFFE3A3)
            },
            label = "statusColor",
        )
        Row(
            modifier = Modifier.background(chipColor, RoundedCornerShape(100.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(Ink, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(
                when {
                    !state.enabled -> "Доступ выключен"
                    state.relayState == RelayState.Connected -> "Готово"
                    else -> "Подключение"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(10.dp))
        TextButton(onClick = onSupport) {
            Icon(Icons.Outlined.Info, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(7.dp))
            Text("Поддержка")
        }
    }
}

@Composable
private fun OnboardingScreen(
    state: GatewayUiState,
    code: String,
    onCodeChanged: (String) -> Unit,
    onRetry: () -> Unit,
    onEnroll: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(0.72f),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            shape = RoundedCornerShape(30.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 54.dp, vertical = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(66.dp).background(Accent, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Security, null, tint = Ink, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    if (state.adbState == AdbState.Available) "Введите код подключения" else "Разрешите доступ к системе",
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = Ink,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when (state.adbState) {
                        AdbState.AuthorizationRequired -> "Подтвердите системный запрос отладки на экране Android, затем нажмите «Проверить снова»."
                        AdbState.Available -> "Получите одноразовый код у администратора сервиса. Он нужен только при первой настройке этого автомобиля."
                        AdbState.Unavailable -> "ADB пока недоступен. Проверьте, что отладка включена в настройках автомобиля."
                        else -> "Приложение проверяет локальный системный доступ. При первом запуске Android покажет стандартный запрос подтверждения."
                    },
                    modifier = Modifier.widthIn(max = 680.dp),
                    fontSize = 18.sp,
                    lineHeight = 27.sp,
                    textAlign = TextAlign.Center,
                    color = Muted,
                )
                Spacer(Modifier.height(32.dp))
                if (state.adbState == AdbState.Available) {
                    CodeEntry(value = code, onValueChange = onCodeChanged, enabled = !state.busy)
                    Spacer(Modifier.height(26.dp))
                    Button(
                        onClick = onEnroll,
                        enabled = code.length == 8 && !state.busy,
                        modifier = Modifier.height(62.dp).widthIn(min = 300.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text("Подключить автомобиль", fontSize = 17.sp)
                    }
                } else {
                    Button(
                        onClick = onRetry,
                        enabled = !state.busy,
                        modifier = Modifier.height(62.dp).widthIn(min = 260.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                        else {
                            Icon(Icons.Outlined.Refresh, null)
                            Spacer(Modifier.width(10.dp))
                            Text("Проверить снова", fontSize = 17.sp)
                        }
                    }
                }
                state.message?.let {
                    Spacer(Modifier.height(18.dp))
                    Text(it, fontSize = 14.sp, color = if (state.adbState == AdbState.Available) AccentDark else Muted)
                }
            }
        }
    }
}

@Composable
private fun CodeEntry(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        cursorBrush = SolidColor(Ink),
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        decorationBox = { innerTextField ->
            Box {
                Box(Modifier.size(1.dp).alpha(0f)) { innerTextField() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(8) { index ->
                        if (index == 4) {
                            Box(Modifier.width(12.dp).height(58.dp), contentAlignment = Alignment.Center) {
                                Text("–", color = Muted, fontSize = 24.sp)
                            }
                        }
                        val char = value.getOrNull(index)?.toString().orEmpty()
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .background(Color.White, RoundedCornerShape(14.dp))
                                .border(1.5.dp, if (char.isNotEmpty()) Ink else Color(0xFFD5D9D2), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(char, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun MainDashboard(
    state: GatewayUiState,
    onPair: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StatusCard(state, modifier = Modifier.weight(1.45f).fillMaxHeight())
        ActionPanel(
            state = state,
            onPair = onPair,
            onDisable = onDisable,
            onEnable = onEnable,
            modifier = Modifier.weight(0.72f).fillMaxHeight(),
        )
    }
}

@Composable
private fun StatusCard(state: GatewayUiState, modifier: Modifier = Modifier) {
    val now by currentTime()
    val pulse = if (state.clientState == ClientState.Active) {
        val transition = rememberInfiniteTransition(label = "activity")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "activityPulse",
        ).value
    } else 1f
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(42.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).alpha(pulse).background(if (state.enabled) Accent else Color(0xFF889088), CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(state.registration?.deviceLabel ?: "Автомобиль", color = Color.White.copy(alpha = 0.72f), fontSize = 17.sp)
            }
            Spacer(Modifier.weight(0.7f))
            Text(
                state.headline,
                color = Color.White,
                fontSize = 42.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.widthIn(max = 720.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(activityDescription(state, now), color = Color.White.copy(alpha = 0.63f), fontSize = 19.sp, lineHeight = 28.sp)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActivityFact(
                    icon = Icons.Outlined.Link,
                    title = "Соединение",
                    value = if (state.relayState == RelayState.Connected) "Работает" else "Восстанавливается",
                    modifier = Modifier.weight(1f),
                )
                ActivityFact(
                    icon = Icons.Outlined.Computer,
                    title = "Компьютер",
                    value = when (state.clientState) {
                        ClientState.Waiting -> "Не подключён"
                        ClientState.Connected -> "Подключён"
                        ClientState.Active -> "Выполняет работу"
                    },
                    modifier = Modifier.weight(1f),
                )
                ActivityFact(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Последняя активность",
                    value = state.lastActivityMillis?.let { relativeTime(now - it) } ?: "Пока не было",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActivityFact(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(20.dp)).padding(20.dp),
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White.copy(alpha = 0.52f), fontSize = 13.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ActionPanel(
    state: GatewayUiState,
    onPair: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(34.dp)) {
            Text("Удалённый доступ", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.enabled) "Разрешён одному доверенному компьютеру." else "Выключен и не включится после перезапуска.",
                color = Muted,
                fontSize = 16.sp,
                lineHeight = 23.sp,
            )
            Spacer(Modifier.weight(1f))
            if (state.enabled) {
                Button(
                    onClick = onPair,
                    enabled = !state.busy && state.permanentFailure == null,
                    modifier = Modifier.fillMaxWidth().height(70.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                ) {
                    Icon(Icons.Outlined.Computer, null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (state.clientLabel == null) "Подключить компьютер" else "Заменить компьютер",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onDisable,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(66.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                ) {
                    Icon(Icons.Outlined.LinkOff, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Отключить удалённый доступ", fontSize = 16.sp)
                }
            } else {
                Button(
                    onClick = onEnable,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(70.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Включить удалённый доступ", fontSize = 17.sp)
                }
            }
            state.message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = if (state.permanentFailure == null) Muted else Danger, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun FullAccessWarning(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Security, null, tint = Danger, modifier = Modifier.size(34.dp)) },
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = { Text(body, fontSize = 17.sp, lineHeight = 25.sp) },
        confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun PairingCodeDialog(window: PairingWindow, onDismiss: () -> Unit, onClose: () -> Unit) {
    val now by currentEpochSeconds()
    val remaining = max(0, window.expiresAtEpochSeconds - now)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Computer, null, modifier = Modifier.size(34.dp)) },
        title = { Text("Код для компьютера", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Передайте код доверенному разработчику. Он действует 10 минут и даёт полный доступ к этому автомобилю.", textAlign = TextAlign.Center, lineHeight = 23.sp)
                Spacer(Modifier.height(24.dp))
                DisplayCode(window.code)
                Spacer(Modifier.height(20.dp))
                Text("cag pair ${window.code}", color = Muted, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (remaining > 0) "Осталось ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}" else "Код истёк — создайте новый",
                    color = if (remaining > 0) AccentDark else Danger,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("Готово") } },
    )
}

@Composable
private fun DisplayCode(code: String) {
    val compact = code.filter(Char::isLetterOrDigit)
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(8) { index ->
            if (index == 4) Text("–", modifier = Modifier.padding(top = 10.dp), fontSize = 25.sp, color = Muted)
            Box(
                Modifier.size(52.dp).background(Background, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(compact.getOrNull(index)?.toString().orEmpty(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    }
}

@Composable
private fun SupportDialog(state: GatewayUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сведения для поддержки") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SupportLine("Relay", "$RELAY_HOST:$RELAY_SSH_PORT")
                SupportLine("Автомобиль", state.registration?.deviceId ?: "не зарегистрирован")
                SupportLine("ADB", state.endpoint?.let { "${it.kind.relayValue} ${it.host}:${it.port}" } ?: state.adbState.name)
                SupportLine("Соединение", state.relayState.name)
                Spacer(Modifier.height(16.dp))
                Text("Последние события", fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(8.dp))
                state.supportEvents.take(8).forEach { event ->
                    Text(
                        "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestampMillis))}  ${event.message}",
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
                Text(
                    "После принудительной остановки в системных настройках Android приложение нужно открыть вручную.",
                    modifier = Modifier.padding(top = 18.dp),
                    color = Danger,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun SupportLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Muted, modifier = Modifier.width(120.dp), fontSize = 14.sp)
        Text(value, color = Ink, fontSize = 14.sp)
    }
}

@Composable
private fun currentTime() = produceState(initialValue = System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1_000)
    }
}

@Composable
private fun currentEpochSeconds() = produceState(initialValue = System.currentTimeMillis() / 1_000) {
    while (true) {
        value = System.currentTimeMillis() / 1_000
        delay(1_000)
    }
}

private fun activityDescription(state: GatewayUiState, now: Long): String = when {
    !state.enabled -> "Приложение не соединяется с relay до ручного включения."
    state.clientState == ClientState.Active -> "Есть активный канал к системе автомобиля. Команды не записываются."
    state.clientState == ClientState.Connected -> state.connectedSinceMillis?.let {
        "Компьютер на связи ${formatDuration(now - it)}, активных действий сейчас нет."
    } ?: "Компьютер подключён и ожидает."
    state.relayState == RelayState.Connected -> "Автомобиль на связи и готов принять один доверенный компьютер."
    else -> "Приложение продолжит попытки автоматически при появлении сети."
}

private fun formatDuration(millis: Long): String {
    val seconds = max(0, millis / 1_000)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%d:%02d".format(minutes, remaining)
}

private fun relativeTime(millis: Long): String = when {
    millis < 5_000 -> "сейчас"
    millis < 60_000 -> "${millis / 1_000} сек. назад"
    else -> "${millis / 60_000} мин. назад"
}

private fun normalizeCodeInput(value: String): String = value
    .filter(Char::isLetterOrDigit)
    .uppercase()
    .take(8)
