package dev.denza.apps.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.denza.apps.feature.cluster.ClusterMapCard
import dev.denza.apps.feature.cluster.ClusterMapCardRuntime

/** The actual card view beside ClusterMapCard.dc.html. Local drawing only; no car/HUD calls. */
class ClusterMapCardFixtureActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val picture = Bitmap.createBitmap(600, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)
        canvas.drawColor(Color.rgb(37, 42, 50))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(65, 73, 85)
            strokeWidth = 8f
        }
        for (y in 60..300 step 120) canvas.drawLine(0f, y.toFloat(), 600f, y.toFloat(), paint)
        for (x in 60..540 step 120) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 360f, paint)
        paint.color = Color.rgb(67, 159, 252)
        paint.strokeWidth = 12f
        paint.style = Paint.Style.STROKE
        canvas.drawPath(Path().apply { moveTo(300f, 360f); lineTo(300f, 120f); lineTo(600f, 120f) }, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawPath(Path().apply {
            moveTo(300f, 242f); lineTo(284f, 276f); lineTo(300f, 268f); lineTo(316f, 276f); close()
        }, paint)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ClusterMapCardRuntime.CardView(this).apply {
            card = ClusterMapCard.Box(0, 0, 600, 720)
            this.picture = picture
        }, FrameLayout.LayoutParams(600, 720, Gravity.TOP or Gravity.LEFT))
        setContentView(root)
    }
}
