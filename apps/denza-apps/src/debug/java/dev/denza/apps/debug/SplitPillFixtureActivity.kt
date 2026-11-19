package dev.denza.apps.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import dev.denza.apps.feature.split.SceneVisibility
import dev.denza.apps.feature.split.SplitMemory
import dev.denza.apps.feature.split.SplitPair
import dev.denza.apps.feature.split.SplitPane
import dev.denza.apps.feature.split.SplitPickerCatalog
import dev.denza.apps.feature.split.SplitPickerLivePane
import dev.denza.apps.feature.split.SplitScene
import dev.denza.apps.feature.split.SplitSlot
import dev.denza.apps.feature.split.SplitState
import dev.denza.apps.feature.split.pill.PillGeometry
import dev.denza.apps.feature.split.pill.SplitPillActions
import dev.denza.apps.feature.split.pill.SplitPillModel
import dev.denza.apps.feature.split.pill.SplitPillSpec
import dev.denza.apps.feature.split.pill.SplitPillView

/**
 * A board of `tools/design-canvas/split-pill` drawn by the product's own pill, still, at the
 * stand's size, so a screenshot can be laid over the board with `compare.py`:
 *
 * ```
 * adb shell am start -n dev.denza.apps/.debug.SplitPillFixtureActivity --es board open
 * adb exec-out screencap -p > app.png
 * python3 tools/design-canvas/split-pill/compare.py open app.png
 * ```
 *
 * Boards: `handle`, `handle-dim`, `open`, `open-browser`, `open-wide-left`, `vacant-right`,
 * `vacant-left`, `vacant-both`, `apps`, `apps-wide-left`. The pairs are made of this device's own
 * launcher apps, so the icons are not the board's sample icons; only the pill's cells count. The
 * view sits where the pill's window stands on the car, over black. Debug builds only.
 *
 * **After any change to how the pill draws**, run every board on a 2560 x 1600, 320 dpi emulator
 * (`am start -S -W ... --es board <b>`, then `exec-out screencap -p`), and read these pixels; no
 * unit test can, since `android.graphics` is stubs in this module's tests. Sample where the board
 * has black behind the glass - the divider's gap, x 868 (1692 on the `-wide-left` boards) - since
 * the board's panes show through the glass and the fixture's black does not. Expected values are
 * the boards' own, RGB or luminance, within 3 levels:
 *
 * | board | point (px) | expected |
 * | --- | --- | --- |
 * | `handle` | 868, 1407 (sheen) / 868, 1433 (bead) / brightest of 865..871 x 1400..1404 (rim) | (19, 20, 23) / (96, 96, 98) / 98 |
 * | `handle-dim` | the same three | (13, 14, 16) / (56, 57, 59) / 68 |
 * | every open board | gap x, 1279 (sheen) / gap x, 1429 (glass) | (19, 20, 22) / (11, 12, 15) |
 * | every open board | brightest of gap x ± 3, y 1262..1266 (rim top) / y 1436..1440 (rim bottom) | 101 / 29 |
 * | `open` | «+» glyph, brightest of 1190..1222 x 1335..1367 / its disc at 1236, 1381 | 255 / (31, 32, 36) |
 * | `open` | separator, brightest of 1098..1128 x 1321..1381 | 46 |
 * | `open` | badge star, brightest about 635, 1309 / its disc at 635, 1328 / its ring, 22.5 px out | 255 / (28, 30, 36) / 210 |
 * | `apps` | the divider's mark, the 4 px column at 866..869, 1351 | 97 over the glass's 13 |
 *
 * A rim of about 50, a sheen of about 47 at the top fading over the whole height, and «+» at 127
 * are the two failures met on 2026-11-19: a ring path emptied with `Path.rewind()` (which loses
 * its even-odd fill type and fills the whole capsule in the rim's gradient), and the system's
 * "Viewing full screen" card dimming everything by half - why only the status bar is hidden here.
 */
class SplitPillFixtureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The stage is the whole screen from its top-left, as the board's is.
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        // Only the status bar is hidden: hiding the navigation bar makes the system show its
        // "Viewing full screen" card, which dims the whole screen - pill included - by half.
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        val board = intent.getStringExtra(EXTRA_BOARD) ?: "open"
        // The catalogue scan can take seconds on the car: never on the main thread.
        Thread({
            val apps = runCatching { SplitPickerCatalog.load(this).map { it.packageName }.distinct() }.getOrDefault(emptyList())
            root.post { if (!isDestroyed) draw(root, board, apps) }
        }, "split-pill-fixture-catalogue").start()
    }

    private fun draw(root: FrameLayout, board: String, apps: List<String>) {
        val scale = root.width.toDouble() / SplitPillSpec.Stage.W
        val view = SplitPillView(this, scale, NoActions)
        val px = PillGeometry.toPx(PillGeometry.window, scale)
        root.addView(
            view,
            FrameLayout.LayoutParams(px[2] - px[0], px[3] - px[1]).apply {
                leftMargin = px[0]
                topMargin = px[1]
            },
        )
        val a = apps.getOrElse(0) { "a" }
        val b = apps.getOrElse(1) { "b" }
        val c = apps.getOrElse(2) { "c" }
        val d = apps.getOrElse(3) { "d" }
        val onScreen = SplitPair(a, b)
        val wideLeft = board.endsWith("wide-left")
        val divider = if (wideLeft) SplitPillSpec.Stage.DETENT_WIDE_LEFT else SplitPillSpec.Stage.DETENT_NARROW_LEFT
        val pinned = board == "open" || wideLeft
        // `open-browser`: the third chip's pair went on screen, the first stays pinned - as on the board.
        val browser = board == "open-browser"
        val memory = if (browser) {
            SplitMemory(last = SplitPair(c, b), recent = listOf(SplitPair(a, d)), pinned = listOf(onScreen))
        } else {
            SplitMemory(
                last = onScreen,
                recent = listOf(SplitPair(c, b), SplitPair(a, d)),
                pinned = if (pinned) listOf(onScreen) else emptyList(),
            )
        }
        val right = if (browser) d else b
        val shown: Map<SplitPane, String?> = when (board) {
            "vacant-right" -> mapOf(SplitPane.PRIMARY to a, SplitPane.SECONDARY to null)
            "vacant-left" -> mapOf(SplitPane.PRIMARY to null, SplitPane.SECONDARY to b)
            "vacant-both" -> mapOf(SplitPane.PRIMARY to null, SplitPane.SECONDARY to null)
            else -> mapOf(SplitPane.PRIMARY to a, SplitPane.SECONDARY to right)
        }
        val state = SplitState(
            enabled = true,
            slots = mapOf(
                SplitPane.PRIMARY to if (board == "vacant-both") SplitSlot.Picker else SplitSlot.App(a),
                SplitPane.SECONDARY to if (board == "vacant-both") SplitSlot.Picker else SplitSlot.App(right),
            ),
            scene = SplitScene.Split,
            visibility = SceneVisibility.VISIBLE,
            memory = memory,
        )
        val live = SplitPane.entries.associateWith { pane ->
            SplitPickerLivePane(pane, 1, shown[pane]?.let { 2 }, shown[pane])
        }
        val description = SplitPillModel.describe(state, live, divider, apps) ?: return
        val still = when (board) {
            "handle" -> SplitPillView.Board.HANDLE
            "handle-dim" -> SplitPillView.Board.HANDLE_DIM
            "apps", "apps-wide-left" -> SplitPillView.Board.APPS
            else -> SplitPillView.Board.OPEN
        }
        view.pin(description, still)
    }

    private object NoActions : SplitPillActions {
        override fun choosePair(pair: SplitPair) = Unit
        override fun selectIntoPane(pane: SplitPane, packageName: String) = Unit
        override fun setPinned(pair: SplitPair, pinned: Boolean) = Unit
        override fun touchRegion(bands: List<IntArray>) = Unit
        override fun gone() = Unit
    }

    private companion object {
        const val EXTRA_BOARD = "board"
    }
}
