package dev.denza.apps.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.denza.apps.design.luminofor.LuminoforSpec
import dev.denza.apps.feature.cluster.dashboard.ClusterDashboardRenderer
import dev.denza.apps.feature.cluster.dashboard.ContourFixtures
import dev.denza.apps.feature.cluster.dashboard.ContourFrame
import dev.denza.apps.feature.trip.StripFixtures
import dev.denza.apps.ui.DashboardFixtureFrame
import dev.denza.apps.ui.SpectrumPanel

/**
 * Draws one Luminofor board's fixture with the app's own code, at the board's pixel size, in the
 * top-left corner of the screen - so a screenshot can be laid over the board's PNG.
 *
 * ```
 * adb -s emulator-5580 shell am start -n dev.denza.apps/.debug.LuminoforFixtureActivity --es board main-sound
 * adb -s emulator-5580 exec-out screencap -p > app.png
 * python3 tools/design-canvas/luminofor/compare.py main-sound app.png
 * ```
 *
 * The head-unit boards are the real dashboard body - [DashboardFixtureFrame] hosts the same
 * `DashboardBody` the screen does, and the strip is the real [SpectrumPanel] driven by the fixture's
 * model instead of the car. The cluster boards are the real Contour renderer drawing the fixture's
 * frame onto a 2560 x 720 view. The system bars are hidden so the corner is the screen's corner.
 * Debug builds only; nothing here reaches the car.
 */
class LuminoforFixtureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        val id = intent.getStringExtra(EXTRA_BOARD) ?: "main-sound"
        val all = StripFixtures.load(this)
        val board = StripFixtures.board(all, id)
        val fixture = StripFixtures.fixture(all, id)

        if (board.getString("kind") == "cluster") {
            val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            root.addView(
                ClusterFixtureView(this, ContourFixtures.frame(fixture)),
                FrameLayout.LayoutParams(LuminoforSpec.Cluster.DISPLAY_W, LuminoforSpec.Cluster.DISPLAY_H),
            )
            setContentView(root)
            return
        }
        val layout = StripFixtures.layout(board)
        val page = StripFixtures.page(board)
        val model = StripFixtures.model(fixture)
        setContent {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                DashboardFixtureFrame(board, fixture, strip = { modifier ->
                    SpectrumPanel(layout, modifier, fixture = model, fixturePage = page)
                })
            }
        }
    }

    /** The Contour's own renderer, drawing one frozen frame. */
    private class ClusterFixtureView(context: Context, private val frame: ContourFrame) : View(context) {
        private val renderer = ClusterDashboardRenderer(context)

        override fun onDraw(canvas: Canvas) {
            renderer.drawFrame(canvas, width, height, frame)
        }
    }

    companion object {
        const val EXTRA_BOARD = "board"
    }
}
