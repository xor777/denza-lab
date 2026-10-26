package ru.adbgw.gateway

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** What lies over the screen, if anything. One at a time. */
sealed interface GatewayOverlay {
    /** CAG-013, the first warning: before the car is enrolled. */
    data object EnrollWarning : GatewayOverlay

    /** CAG-013, the second warning: before every pairing code. */
    data object PairWarning : GatewayOverlay
    data class PairCode(val window: PairingWindow) : GatewayOverlay
    data object DisableConfirm : GatewayOverlay
    data object Support : GatewayOverlay
}

/** What the screen holds that the repository does not: the code being typed and what lies over. */
data class GatewayViewState(
    val code: String = "",
    val enrollmentError: String? = null,
    val overlay: GatewayOverlay? = null,
)

/** Everything the screen can ask for. The screen itself does nothing but draw and call these. */
class GatewayActions(
    val onCodeChange: (String) -> Unit = {},
    val onRetryAdb: () -> Unit = {},
    val onEnroll: () -> Unit = {},
    val onConfirmEnroll: () -> Unit = {},
    val onPair: () -> Unit = {},
    val onConfirmPair: () -> Unit = {},
    val onClosePairing: () -> Unit = {},
    val onNewCode: () -> Unit = {},
    val onDisable: () -> Unit = {},
    val onConfirmDisable: () -> Unit = {},
    val onEnable: () -> Unit = {},
    val onSupport: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * The whole app on one screen: the top bar, the connection chain, one headline with at most one
 * line under it, the actions at the foot, and whatever [GatewayViewState.overlay] lays over it.
 * Stateless: every scene, the fixture's included, is this function of its arguments.
 */
@Composable
fun GatewayScreen(
    state: GatewayUiState,
    view: GatewayViewState,
    nowMillis: Long,
    actions: GatewayActions,
) {
    val scene = sceneOf(state, view, nowMillis)
    Box(Modifier.fillMaxSize().background(GatewayInk.Ground)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = PAGE_MARGIN)
                .padding(top = 12.dp, bottom = 28.dp),
        ) {
            TopBar(state, actions.onSupport)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val roomy = maxHeight >= ROOMY_HEIGHT
                val withChain = maxHeight >= CHAIN_HEIGHT
                HeroLayout(
                    modifier = Modifier.fillMaxSize(),
                    core = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (withChain) {
                                ConnectionChain(
                                    chainModel(state, nowMillis),
                                    if (roomy) ChainMetrics.Roomy else ChainMetrics.Compact,
                                )
                                Spacer(Modifier.height(if (roomy) 44.dp else 20.dp))
                            }
                            Text(
                                scene.headline,
                                style = gatewayText(if (roomy) 34.sp else 30.sp, GatewayInk.Title, FontWeight.Medium),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(10.dp))
                            // Always one line high, empty or not, so no scene moves the chain.
                            Text(
                                scene.detail.orEmpty(),
                                style = gatewayText(18.sp, if (scene.detailAlarm) GatewayInk.Red else GatewayInk.Summary, lineHeight = 24.sp),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 720.dp),
                            )
                        }
                    },
                    extra = {
                        if (scene.codeEntry) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(if (roomy) 20.dp else 12.dp))
                                CodeEntry(
                                    code = view.code,
                                    enabled = !state.busy,
                                    cell = if (roomy) 64.dp else 52.dp,
                                    onChange = actions.onCodeChange,
                                )
                                Spacer(Modifier.height(14.dp))
                                // The line is always there, so the cells do not move when it fills.
                                Text(
                                    view.enrollmentError.orEmpty(),
                                    style = gatewayText(16.sp, GatewayInk.Red, lineHeight = 22.sp),
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 720.dp),
                                )
                            }
                        }
                    },
                )
            }
            ActionBar(scene.actions, state.busy, actions)
        }
        Overlay(state, view, nowMillis, actions)
    }
}

/**
 * The chain, the headline and its line ([core]) stand centred in the room between the top bar and
 * the actions, and stay there whatever hangs under them ([extra], the code entry) - a scene that
 * changes state does not move the chain. Only when the room is too short for both does the core
 * rise, just enough to leave [EXTRA_CLEARANCE] above the actions.
 */
@Composable
private fun HeroLayout(modifier: Modifier, core: @Composable () -> Unit, extra: @Composable () -> Unit) {
    Layout(contents = listOf(core, extra), modifier = modifier) { (coreMeasurables, extraMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val corePlaceables = coreMeasurables.map { it.measure(loose) }
        val extraPlaceables = extraMeasurables.map { it.measure(loose) }
        val coreHeight = corePlaceables.maxOfOrNull { it.height } ?: 0
        val extraHeight = extraPlaceables.maxOfOrNull { it.height } ?: 0
        val height = constraints.maxHeight
        val width = constraints.maxWidth
        val clearance = if (extraHeight > 0) EXTRA_CLEARANCE.roundToPx() else 0
        val top = minOf((height - coreHeight) / 2, height - coreHeight - extraHeight - clearance).coerceAtLeast(0)
        layout(width, height) {
            corePlaceables.forEach { it.place((width - it.width) / 2, top) }
            extraPlaceables.forEach { it.place((width - it.width) / 2, top + coreHeight) }
        }
    }
}

// --------------------------------------------------------------------------------------- scenes

private enum class SceneAction { RetryAdb, CheckAdb, Enroll, Pair, Replace, Disable, Enable }

private class Scene(
    val headline: String,
    val detail: String? = null,
    val detailAlarm: Boolean = false,
    val codeEntry: Boolean = false,
    val actions: List<Pair<SceneAction, Boolean>> = emptyList(),
)

private fun sceneOf(state: GatewayUiState, view: GatewayViewState, nowMillis: Long): Scene = when {
    state.permanentFailure != null -> Scene(
        headline = state.headline,
        detail = state.permanentFailure,
        detailAlarm = true,
        actions = if (state.isEnrolled) mainActions(state) else emptyList(),
    )
    !state.isEnrolled -> onboardingScene(state, view)
    else -> Scene(
        headline = state.headline,
        detail = mainDetail(state, nowMillis),
        actions = mainActions(state),
    )
}

private fun onboardingScene(state: GatewayUiState, view: GatewayViewState): Scene = when (state.adbState) {
    AdbState.Checking -> Scene(headline = state.headline, detail = state.message)
    AdbState.AuthorizationRequired -> Scene(
        headline = "Разрешите ADB на экране",
        detail = "Нажмите «Разрешить» в системном окне",
        actions = listOf(SceneAction.RetryAdb to true),
    )
    AdbState.Unavailable -> Scene(
        headline = "ADB выключен",
        detail = "Включите отладку по сети в настройках машины",
        actions = listOf(SceneAction.CheckAdb to true),
    )
    AdbState.Available -> Scene(
        headline = "Код от администратора",
        detail = state.message?.takeIf { it != view.enrollmentError },
        codeEntry = true,
        actions = listOf(SceneAction.Enroll to (view.code.length == CODE_LENGTH)),
    )
}

private fun mainActions(state: GatewayUiState): List<Pair<SceneAction, Boolean>> = if (state.enabled) {
    listOf(
        (if (state.clientLabel == null) SceneAction.Pair else SceneAction.Replace) to (state.permanentFailure == null),
        SceneAction.Disable to true,
    )
} else {
    listOf(SceneAction.Enable to true)
}

private fun mainDetail(state: GatewayUiState, nowMillis: Long): String? = when {
    state.message != null -> state.message
    !state.enabled -> "Не включится после перезапуска"
    state.clientState == ClientState.Active -> null
    else -> state.lastActivityMillis?.let { "Последняя активность ${relativeTime(nowMillis - it, it)}" }
}

private fun relativeTime(ago: Long, at: Long): String {
    val minutes = ago / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 24 * 60 -> "${minutes / 60} ч назад"
        else -> SimpleDateFormat("d MMMM", RUSSIAN).format(Date(at))
    }
}

// -------------------------------------------------------------------------------------- top bar

@Composable
private fun TopBar(state: GatewayUiState, onSupport: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        AppMark(44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Удалённый доступ", style = gatewayText(22.sp, GatewayInk.Title, FontWeight.Medium), maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                state.registration?.deviceLabel ?: "Машина не подключена",
                style = gatewayText(15.sp, GatewayInk.Summary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        GatewayButton("Сведения", onSupport, ButtonKind.Quiet, height = 48.dp, textSize = 16.sp)
    }
}

// ------------------------------------------------------------------------------------- actions

@Composable
private fun ActionBar(list: List<Pair<SceneAction, Boolean>>, busy: Boolean, actions: GatewayActions) {
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        list.forEachIndexed { index, (action, enabled) ->
            val primary = index == 0
            GatewayButton(
                text = when (action) {
                    SceneAction.RetryAdb -> "Запросить снова"
                    SceneAction.CheckAdb -> "Проверить снова"
                    SceneAction.Enroll -> "Подключить машину"
                    SceneAction.Pair -> "Подключить компьютер"
                    SceneAction.Replace -> "Заменить компьютер"
                    SceneAction.Disable -> "Выключить доступ"
                    SceneAction.Enable -> "Включить доступ"
                },
                onClick = when (action) {
                    SceneAction.RetryAdb, SceneAction.CheckAdb -> actions.onRetryAdb
                    SceneAction.Enroll -> actions.onEnroll
                    SceneAction.Pair, SceneAction.Replace -> actions.onPair
                    SceneAction.Disable -> actions.onDisable
                    SceneAction.Enable -> actions.onEnable
                },
                kind = when {
                    action == SceneAction.Disable -> ButtonKind.QuietDanger
                    primary -> ButtonKind.Primary
                    else -> ButtonKind.Quiet
                },
                enabled = enabled && !busy,
                busy = primary && busy,
                modifier = Modifier.width(ACTION_WIDTH),
            )
        }
    }
}

// ---------------------------------------------------------------------------------- code cells

/**
 * Eight cells in two groups of four with a dash between them, driven by a hidden text field: a tap
 * anywhere on them opens the keyboard, and the next empty cell wears a fine blue ring.
 */
@Composable
private fun CodeEntry(code: String, enabled: Boolean, cell: Dp, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = code,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = gatewayText(1.sp, Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.Center) {
                CodeCells(
                    code = code,
                    cell = cell,
                    textSize = if (cell >= 64.dp) 34.sp else 28.sp,
                    plate = GatewayInk.Plate,
                    active = if (enabled && code.length < CODE_LENGTH) code.length else null,
                )
                Box(Modifier.size(1.dp)) { field() }
            }
        },
    )
}

/** The code as eight cells, two groups of four with a dash; shared by the entry and the pairing card. */
@Composable
private fun CodeCells(
    code: String,
    cell: Dp,
    textSize: TextUnit,
    plate: Color,
    active: Int? = null,
    ink: Color = GatewayInk.Title,
) {
    val chars = code.filter(Char::isLetterOrDigit)
    val shape = RoundedCornerShape(12.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(CODE_LENGTH) { index ->
            if (index == CODE_LENGTH / 2) {
                Box(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .size(width = 16.dp, height = 2.dp)
                        .background(GatewayInk.Summary, RoundedCornerShape(1.dp)),
                )
            } else if (index > 0) {
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier
                    .size(cell)
                    .background(plate, shape)
                    .border(1.dp, GatewayInk.Hairline, shape),
                contentAlignment = Alignment.Center,
            ) {
                if (index == active) ActiveCellRing()
                Text(
                    chars.getOrNull(index)?.toString().orEmpty(),
                    style = gatewayText(textSize, ink, FontWeight.Medium),
                )
            }
        }
    }
}

/** The cell the next character goes to: a fine line of blue light round it. */
@Composable
private fun ActiveCellRing() {
    Canvas(Modifier.fillMaxSize()) {
        val inset = 1.dp.toPx()
        val radius = 12.dp.toPx() - inset
        val ring = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(inset, inset, size.width - inset, size.height - inset),
                radius,
                radius,
                android.graphics.Path.Direction.CW,
            )
        }
        beam(ring, 1.5.dp.toPx(), GatewayInk.BlueCore, GatewayInk.Blue, glow = 0.5f, intensity = 0.9f)
    }
}

// ------------------------------------------------------------------------------------- overlays

@Composable
private fun Overlay(state: GatewayUiState, view: GatewayViewState, nowMillis: Long, actions: GatewayActions) {
    when (val overlay = view.overlay) {
        null -> Unit
        GatewayOverlay.EnrollWarning -> StockDialog(
            glyph = Icons.Outlined.GppMaybe,
            glyphInk = GatewayInk.Orange,
            title = "Подключить машину?",
            onDismiss = actions.onDismiss,
            primary = { GatewayButton("Подключить", actions.onConfirmEnroll, ButtonKind.Primary, Modifier.fillMaxWidth()) },
            quiet = listOf("Отмена" to actions.onDismiss),
        ) {
            DialogBody("Компьютер, которому вы потом дадите код, получит полный удалённый доступ к Android-системе машины. Вводите только код от администратора.")
        }
        GatewayOverlay.PairWarning -> StockDialog(
            glyph = Icons.Outlined.GppMaybe,
            glyphInk = GatewayInk.Orange,
            title = "Полный доступ к машине",
            onDismiss = actions.onDismiss,
            primary = { GatewayButton("Показать код", actions.onConfirmPair, ButtonKind.Primary, Modifier.fillMaxWidth()) },
            quiet = listOf("Отмена" to actions.onDismiss),
        ) {
            DialogBody("Компьютер с этим кодом получит полный доступ к Android-системе машины. Передайте код только тому, кому доверяете.")
        }
        is GatewayOverlay.PairCode -> PairingCodeDialog(overlay.window, nowMillis, state.busy, actions)
        GatewayOverlay.DisableConfirm -> StockDialog(
            glyph = Icons.Outlined.LinkOff,
            glyphInk = GatewayInk.Summary,
            title = "Выключить доступ?",
            onDismiss = actions.onDismiss,
            primary = { GatewayButton("Выключить", actions.onConfirmDisable, ButtonKind.Danger, Modifier.fillMaxWidth()) },
            quiet = listOf("Отмена" to actions.onDismiss),
        ) {
            DialogBody("Компьютер отключится, доступ не включится сам после перезапуска")
        }
        GatewayOverlay.Support -> SupportSheet(state, actions.onDismiss)
    }
}

/**
 * The car's stock dialog in Luminofor's grounds: a plate over a scrim, the glyph with the title
 * beside it, a short body, the one action across the card and the quiet ones under it side by side
 * at equal widths.
 */
@Composable
private fun StockDialog(
    glyph: ImageVector,
    glyphInk: Color,
    title: String,
    onDismiss: () -> Unit,
    primary: @Composable () -> Unit,
    quiet: List<Pair<String, () -> Unit>>,
    width: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val swallow = remember { MutableInteractionSource() }
    val scrimTaps = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(GatewayInk.Scrim)
            .clickable(interactionSource = scrimTaps, indication = null, onClick = onDismiss)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .widthIn(max = width)
                .fillMaxWidth()
                .background(GatewayInk.Plate, shape)
                .border(1.dp, GatewayInk.Hairline, shape)
                .clickable(interactionSource = swallow, indication = null, onClick = {})
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(glyph, glyphInk, 32.dp)
                Spacer(Modifier.width(16.dp))
                Text(title, style = gatewayText(24.sp, GatewayInk.Title, FontWeight.Medium))
            }
            Spacer(Modifier.height(18.dp))
            content()
            Spacer(Modifier.height(24.dp))
            primary()
            if (quiet.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    quiet.forEach { (label, onClick) ->
                        GatewayButton(label, onClick, ButtonKind.Quiet, Modifier.weight(1f), height = 56.dp, textSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogBody(text: String) {
    Text(text, style = gatewayText(17.sp, GatewayInk.Summary, lineHeight = 25.sp))
}

/** A material glyph drawn flat in one ink - a dialog's, which names it rather than lights it. */
@Composable
private fun GlyphIcon(glyph: ImageVector, ink: Color, size: Dp) {
    val px = with(LocalDensity.current) { size.toPx() }
    val paths = remember(glyph, px) { glyph.toGlyphPaths(px) }
    Canvas(Modifier.size(size)) {
        glowFill(paths, ink, ink, glow = 0f, blurPx = 0f, over = true)
    }
}

/**
 * The pairing code: eight big cells, a fine blue line under them that drains over the code's ten
 * minutes, the time left, and the command the computer runs. An expired code says so in red and
 * offers a new one, which goes through the warning again.
 */
@Composable
private fun PairingCodeDialog(window: PairingWindow, nowMillis: Long, busy: Boolean, actions: GatewayActions) {
    val remaining = max(0L, window.expiresAtEpochSeconds - nowMillis / 1_000)
    val live = remaining > 0
    StockDialog(
        glyph = Icons.Outlined.Computer,
        glyphInk = GatewayInk.Summary,
        title = "Код для компьютера",
        onDismiss = actions.onClosePairing,
        width = 660.dp,
        primary = {
            if (live) {
                GatewayButton("Готово", actions.onClosePairing, ButtonKind.Primary, Modifier.fillMaxWidth())
            } else {
                GatewayButton("Новый код", actions.onNewCode, ButtonKind.Primary, Modifier.fillMaxWidth(), busy = busy)
            }
        },
        quiet = if (live) emptyList() else listOf("Закрыть" to actions.onClosePairing),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(6.dp))
            CodeCells(
                code = window.code,
                cell = 64.dp,
                textSize = 34.sp,
                plate = GatewayInk.LitPlate,
                ink = if (live) GatewayInk.Title else GatewayInk.Dim,
            )
            Spacer(Modifier.height(18.dp))
            DrainLine(fraction = if (live) remaining.toFloat() / PAIRING_SECONDS else 0f)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (live) "Осталось ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}" else "Код истёк",
                    style = gatewayText(16.sp, if (live) GatewayInk.Summary else GatewayInk.Red),
                )
            }
            if (live) {
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(GatewayInk.LitPlate, RoundedCornerShape(10.dp))
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "cag pair ${window.code}",
                        style = gatewayText(19.sp, GatewayInk.Title, family = GatewayMono),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A hairline track with the time left lit along it from the left. */
@Composable
private fun DrainLine(fraction: Float) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val y = size.height / 2f
        val stroke = 2.dp.toPx()
        drawLine(
            GatewayInk.Unlit,
            androidx.compose.ui.geometry.Offset(stroke / 2, y),
            androidx.compose.ui.geometry.Offset(size.width - stroke / 2, y),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        val lit = fraction.coerceIn(0f, 1f) * (size.width - stroke)
        if (lit > 0.5f) {
            val line = android.graphics.Path().apply {
                moveTo(stroke / 2, y)
                lineTo(stroke / 2 + lit, y)
            }
            beam(line, stroke, GatewayInk.BlueCore, GatewayInk.Blue, glow = 0.7f, intensity = 0.9f)
        }
    }
}

// --------------------------------------------------------------------------------------- support

/**
 * «Сведения»: a panel at the right edge in the car's settings kit - groups of rows on the lit
 * plate, titles at 90 %, values at 54 % - then the last events, and one line about Force Stop.
 */
@Composable
private fun SupportSheet(state: GatewayUiState, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val scrimTaps = remember { MutableInteractionSource() }
    val swallow = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(GatewayInk.Scrim)
                .clickable(interactionSource = scrimTaps, indication = null, onClick = onDismiss),
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(GatewayInk.Hairline))
        Column(
            Modifier
                .width(SHEET_WIDTH)
                .fillMaxHeight()
                .background(GatewayInk.Plate)
                .clickable(interactionSource = swallow, indication = null, onClick = {})
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Сведения", style = gatewayText(24.sp, GatewayInk.Title, FontWeight.Medium), modifier = Modifier.weight(1f))
                GatewayButton("Закрыть", onDismiss, ButtonKind.Quiet, height = 48.dp, textSize = 16.sp)
            }
            Spacer(Modifier.height(20.dp))
            Group {
                val rows = supportRows(state)
                rows.forEachIndexed { index, (title, value) ->
                    if (index > 0) GroupHairline()
                    InfoRow(title, value)
                }
            }
            if (state.supportEvents.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Журнал", style = gatewayText(15.sp, GatewayInk.Summary))
                Spacer(Modifier.height(10.dp))
                Group(Modifier.padding(vertical = 8.dp)) {
                    val clock = remember { SimpleDateFormat("HH:mm:ss", RUSSIAN) }
                    state.supportEvents.take(JOURNAL_ROWS).forEach { event ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(
                                clock.format(Date(event.timestampMillis)),
                                style = gatewayText(15.sp, GatewayInk.Summary, lineHeight = 21.sp),
                                modifier = Modifier.width(76.dp),
                            )
                            Text(event.message, style = gatewayText(15.sp, GatewayInk.Title, lineHeight = 21.sp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "После «Остановить» в настройках Android откройте приложение вручную",
                style = gatewayText(14.sp, GatewayInk.Footnote, lineHeight = 20.sp),
            )
        }
    }
}

internal fun supportRows(state: GatewayUiState): List<Pair<String, String>> = listOf(
    "Сервер" to "$RELAY_HOST:$RELAY_SSH_PORT",
    "Машина" to (state.registration?.deviceId ?: "Не подключена"),
    "Порт на сервере" to (state.registration?.relayDevicePort?.toString() ?: "—"),
    "ADB" to (state.endpoint?.let { "${it.kind.relayValue} ${it.host}:${it.port}" } ?: adbWords(state.adbState)),
    "Соединение" to relayWords(state),
    "Компьютер" to (state.clientLabel ?: "Не назначен"),
    "Регистрация до" to (
        state.registration?.let {
            SimpleDateFormat("d MMMM yyyy", RUSSIAN).format(Date(it.leaseExpiresAtEpochSeconds * 1_000))
        } ?: "—"
        ),
)

internal fun adbWords(state: AdbState): String = when (state) {
    AdbState.Checking -> "Проверка"
    AdbState.AuthorizationRequired -> "Ждёт разрешения"
    AdbState.Available -> "Готов"
    AdbState.Unavailable -> "Выключен"
}

internal fun relayWords(state: GatewayUiState): String = when {
    !state.isEnrolled -> "Нет"
    else -> when (state.relayState) {
        RelayState.Disabled -> "Выключено"
        RelayState.WaitingForNetwork -> "Ждёт сеть"
        RelayState.Connecting -> "Подключение"
        RelayState.Connected -> "На связи"
        RelayState.PermanentFailure -> "Остановлено"
    }
}

@Composable
private fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(GatewayInk.LitPlate, RoundedCornerShape(12.dp))
            .then(modifier),
        content = content,
    )
}

@Composable
private fun GroupHairline() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(GatewayInk.Hairline))
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = gatewayText(18.sp, GatewayInk.Title), maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = gatewayText(16.sp, GatewayInk.Summary),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private val RUSSIAN: Locale = Locale.forLanguageTag("ru")
private val PAGE_MARGIN = 48.dp
private val ACTION_WIDTH = 360.dp
private val SHEET_WIDTH = 480.dp
private val ROOMY_HEIGHT = 440.dp
private val CHAIN_HEIGHT = 300.dp
private val EXTRA_CLEARANCE = 20.dp
private const val CODE_LENGTH = 8
private const val JOURNAL_ROWS = 12
private const val PAIRING_SECONDS = 600f
