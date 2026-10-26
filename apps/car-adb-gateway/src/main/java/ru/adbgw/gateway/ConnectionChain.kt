package ru.adbgw.gateway

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How a node of the chain looks, in Luminofor's tones: live is the dock's blue light, working is a
 * neutral glyph with the quarter ring turning round it, waiting on the driver and broken are the
 * car's orange and red, neutral is a plain white glyph, dim is something that is not there yet.
 */
internal enum class NodeTone { Live, Working, Neutral, Attention, Broken, Dim }

internal data class ChainNode(val tone: NodeTone, val caption: String)

/**
 * The connection chain as data: машина — сервер — компьютер, each with its tone and a one-to-three
 * word state, the two hops lit or not, and whether a computer has an ADB channel open (the activity
 * pulse of CAG-008: an open channel, never a command).
 */
internal data class ChainModel(
    val car: ChainNode,
    val server: ChainNode,
    val computer: ChainNode,
    val carServerLit: Boolean,
    val serverComputerLit: Boolean,
    val pulses: Boolean,
)

internal fun chainModel(state: GatewayUiState, nowMillis: Long): ChainModel {
    val car = when (state.adbState) {
        AdbState.Available -> ChainNode(NodeTone.Live, "ADB готов")
        AdbState.Checking -> ChainNode(NodeTone.Working, "Проверка ADB")
        AdbState.AuthorizationRequired -> ChainNode(NodeTone.Attention, "Разрешите ADB")
        AdbState.Unavailable -> ChainNode(NodeTone.Attention, "ADB выключен")
    }
    val server = when {
        !state.isEnrolled -> ChainNode(NodeTone.Dim, "Нужен код")
        state.permanentFailure != null || state.relayState == RelayState.PermanentFailure ->
            ChainNode(NodeTone.Broken, "Остановлено")
        !state.enabled -> ChainNode(NodeTone.Dim, "Выключено")
        state.relayState == RelayState.Connected -> ChainNode(NodeTone.Live, "На связи")
        else -> ChainNode(NodeTone.Working, "Подключение")
    }
    val computer = when {
        state.clientState == ClientState.Active -> ChainNode(NodeTone.Live, "Работает")
        state.clientState == ClientState.Connected -> ChainNode(
            NodeTone.Live,
            state.connectedSinceMillis?.let { "На связи · ${formatDuration(nowMillis - it)}" } ?: "На связи",
        )
        state.clientLabel == null -> ChainNode(NodeTone.Dim, "Не назначен")
        else -> ChainNode(NodeTone.Neutral, state.clientLabel)
    }
    val serverComputerLit = state.clientState == ClientState.Connected || state.clientState == ClientState.Active
    return ChainModel(
        car = car,
        server = server,
        computer = computer,
        carServerLit = state.isEnrolled && state.enabled && state.relayState == RelayState.Connected,
        serverComputerLit = serverComputerLit,
        pulses = state.clientState == ClientState.Active,
    )
}

/** `m:ss`, or `h:mm:ss` past an hour. */
internal fun formatDuration(millis: Long): String {
    val seconds = max(0, millis / 1_000)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

// ------------------------------------------------------------------------------------ drawing

/** Sizes of the chain at full height and on a short screen. */
internal class ChainMetrics(
    val node: Dp,
    val glyph: Dp,
    val nameSize: Float,
    val stateSize: Float,
    val captionGap: Dp,
) {
    companion object {
        val Roomy = ChainMetrics(node = 104.dp, glyph = 44.dp, nameSize = 18f, stateSize = 15f, captionGap = 14.dp)
        val Compact = ChainMetrics(node = 80.dp, glyph = 36.dp, nameSize = 17f, stateSize = 14f, captionGap = 10.dp)
    }
}

/**
 * Three nodes across the screen joined by two fine lines. A hop that works is a lit blue line; one
 * that does not is a dashed hairline. While the computer has an ADB channel open, small pulses run
 * along both lines from the computer to the car.
 */
@Composable
internal fun ConnectionChain(model: ChainModel, metrics: ChainMetrics, modifier: Modifier = Modifier) {
    val phase = pulsePhase(model.pulses)
    val turn = ringTurn()
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val width = min(maxWidth.value, CHAIN_MAX_WIDTH).dp
        Box(Modifier.width(width)) {
            ChainLinks(model, phase, metrics.node, Modifier.width(width).height(metrics.node))
            Row(Modifier.width(width), horizontalArrangement = Arrangement.SpaceEvenly) {
                ChainNodeView("Машина", Icons.Outlined.DirectionsCar, model.car, metrics, turn, Modifier.weight(1f))
                ChainNodeView("Сервер", Icons.Outlined.Dns, model.server, metrics, turn, Modifier.weight(1f))
                ChainNodeView("Компьютер", Icons.Outlined.Computer, model.computer, metrics, turn, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun pulsePhase(running: Boolean): Float {
    if (!running) return 0f
    if (LocalStillFrame.current) return STILL_PULSE_PHASE
    val phase by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_PERIOD_MS, easing = LinearEasing)),
        label = "pulsePhase",
    )
    return phase
}

@Composable
private fun ChainLinks(model: ChainModel, phase: Float, node: Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val dash = remember(density) {
        with(density) { PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 7.dp.toPx())) }
    }
    Canvas(modifier) {
        val w = size.width
        val y = size.height / 2f
        val r = node.toPx() / 2f
        val gap = LINK_GAP.toPx()
        val stroke = 2.dp.toPx()
        val centres = floatArrayOf(w / 6f, w / 2f, w * 5f / 6f)
        for (hop in 0..1) {
            val x0 = centres[hop] + r + gap
            val x1 = centres[hop + 1] - r - gap
            if (x1 <= x0) continue
            val lit = if (hop == 0) model.carServerLit else model.serverComputerLit
            if (lit) {
                val line = android.graphics.Path().apply { moveTo(x0, y); lineTo(x1, y) }
                beam(line, stroke, GatewayInk.BlueCore, GatewayInk.Blue, glow = 0.9f, intensity = 0.9f)
            } else {
                drawLine(
                    color = GatewayInk.Unlit,
                    start = androidx.compose.ui.geometry.Offset(x0, y),
                    end = androidx.compose.ui.geometry.Offset(x1, y),
                    strokeWidth = stroke,
                    pathEffect = dash,
                )
            }
            if (model.pulses && lit) {
                // From the computer's side to the car's: right to left. Two pulses a hop, each
                // fading in and out at the ends so none pops out of a node.
                val length = x1 - x0
                val tail = min(TAIL.toPx(), length * 0.35f)
                for (k in 0 until PULSES_PER_HOP) {
                    val t = (phase + k.toFloat() / PULSES_PER_HOP) % 1f
                    val fade = sin(PI * t).toFloat()
                    if (fade < 0.02f) continue
                    val x = x1 - t * length
                    glowTail(x, min(x1, x + tail), y, stroke * 1.5f, Color.White, 0.8f * fade)
                    glowDot(x, y, 3.5.dp.toPx(), Color.White, GatewayInk.BlueCore, fade)
                }
            }
        }
    }
}

@Composable
private fun ChainNodeView(
    name: String,
    glyph: ImageVector,
    node: ChainNode,
    metrics: ChainMetrics,
    turn: Float,
    modifier: Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        NodePlate(glyph, node.tone, metrics, turn)
        Spacer(Modifier.height(metrics.captionGap))
        Text(
            name,
            style = gatewayText(metrics.nameSize.sp, GatewayInk.Title, FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            node.caption,
            style = gatewayText(metrics.stateSize.sp, captionInk(node.tone)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

internal fun captionInk(tone: NodeTone): Color = when (tone) {
    NodeTone.Live -> GatewayInk.Blue
    NodeTone.Attention -> GatewayInk.Orange
    NodeTone.Broken -> GatewayInk.Red
    NodeTone.Working, NodeTone.Neutral -> GatewayInk.Summary
    NodeTone.Dim -> GatewayInk.Footnote
}

@Composable
private fun NodePlate(glyph: ImageVector, tone: NodeTone, metrics: ChainMetrics, turn: Float) {
    val density = LocalDensity.current
    val glyphPx = with(density) { metrics.glyph.toPx() }
    val nodePx = with(density) { metrics.node.toPx() }
    val paths = remember(glyph, glyphPx, nodePx) {
        val at = (nodePx - glyphPx) / 2f
        glyph.toGlyphPaths(glyphPx, at, at)
    }
    Canvas(Modifier.size(metrics.node)) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(GatewayInk.Plate, radius = r, center = c)
        val hair = 1.dp.toPx()
        val ringR = r - hair / 2f
        when (tone) {
            NodeTone.Live -> {
                val ring = android.graphics.Path().apply {
                    addCircle(c.x, c.y, r - 1.dp.toPx(), android.graphics.Path.Direction.CW)
                }
                beam(ring, 1.5.dp.toPx(), GatewayInk.BlueCore, GatewayInk.Blue, glow = 0.6f, intensity = 0.85f)
            }
            NodeTone.Working -> {
                drawCircle(GatewayInk.Hairline, radius = ringR, center = c, style = Stroke(hair))
                val rr = r - 1.dp.toPx()
                val arc = android.graphics.Path().apply {
                    addArc(android.graphics.RectF(c.x - rr, c.y - rr, c.x + rr, c.y + rr), -90f + turn, 90f)
                }
                beam(arc, 2.dp.toPx(), GatewayInk.BlueCore, GatewayInk.Blue, glow = 0.8f)
            }
            NodeTone.Attention -> drawCircle(GatewayInk.Orange.copy(alpha = 0.45f), radius = ringR, center = c, style = Stroke(hair))
            NodeTone.Broken -> drawCircle(GatewayInk.Red.copy(alpha = 0.45f), radius = ringR, center = c, style = Stroke(hair))
            NodeTone.Neutral, NodeTone.Dim -> drawCircle(GatewayInk.Hairline, radius = ringR, center = c, style = Stroke(hair))
        }
        val blurPx = 6.dp.toPx()
        when (tone) {
            NodeTone.Live -> glowFill(paths, GatewayInk.BlueCore, GatewayInk.Blue, glow = 1f, blurPx = blurPx)
            NodeTone.Attention -> glowFill(paths, GatewayInk.Orange, GatewayInk.Orange, glow = 0.45f, blurPx = blurPx, over = true)
            NodeTone.Broken -> glowFill(paths, GatewayInk.Red, GatewayInk.Red, glow = 0.45f, blurPx = blurPx, over = true)
            NodeTone.Working, NodeTone.Neutral -> glowFill(paths, Color.White.copy(alpha = 0.72f), Color.White, glow = 0f, blurPx = 0f, over = true)
            NodeTone.Dim -> glowFill(paths, GatewayInk.Dim, Color.White, glow = 0f, blurPx = 0f, over = true)
        }
    }
}

private const val CHAIN_MAX_WIDTH = 900f
private val LINK_GAP = 14.dp
private val TAIL = 56.dp
private const val PULSES_PER_HOP = 2
private const val PULSE_PERIOD_MS = 1_600

/** Where a still frame holds the pulses: one in the middle of each hop. */
private const val STILL_PULSE_PHASE = 0.5f
