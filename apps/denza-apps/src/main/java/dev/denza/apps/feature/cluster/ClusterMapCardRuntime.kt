package dev.denza.apps.feature.cluster

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import dev.denza.apps.feature.hud.HudHostRuntime

/**
 * The HUD's map drawn over the instruments' right card ([ClusterMapCard]), on the main thread.
 *
 * Three things meet here: whether the panel's switch is on, where the card is ([onCard], from
 * [ClusterMapCardRider]) and the HUD's map window as a picture ([HudHostRuntime.mapFrames]). The
 * window is a non-touchable Presentation on the overlay display, transparent but for the card,
 * which it fills black with the picture as wide as the card on it. While shown it looks at the
 * camera layer four times a second, and gives way to a turn signal's camera at once.
 */
@SuppressLint("StaticFieldLeak") // the application context only
internal object ClusterMapCardRuntime {
    private const val TAG = "DenzaClusterMapCard"
    private const val CAMERA_CHECK_MS = 250L

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var enabled = false
    private var card: ClusterMapCard.Box? = null
    private var frame: Bitmap? = null
    private var window: CardPresentation? = null
    private var cameraCheck: Runnable? = null

    /** The accessibility service is up: the switch is read and, if on, the HUD's map is heard. */
    fun attach(context: Context) {
        main.post {
            this.context = context.applicationContext
            settingsChanged()
        }
    }

    /** The panel's switch moved. */
    fun settingsChanged(context: Context) {
        main.post {
            if (this.context == null) this.context = context.applicationContext
            settingsChanged()
        }
    }

    /** Where the stock map's card is on the overlay display now, or null when it is not there. */
    fun onCard(box: ClusterMapCard.Box?) {
        main.post {
            if (box != card) Log.i(TAG, "card ${box ?: "gone"}")
            card = box
            update()
        }
    }

    private fun settingsChanged() {
        val context = context ?: return
        enabled = ClusterMapCardSettings.isEnabled(context)
        HudHostRuntime.mapFrames(context, if (enabled) ::onFrame else null)
        if (!enabled) frame = null
        update()
    }

    private fun onFrame(png: ByteArray?) {
        frame = png?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        update()
    }

    private fun update() {
        val context = context ?: return
        val box = card
        val picture = frame
        val camera = ClusterMapCard.cameraOnScreen(ClusterSceneService.cameraRuntimeSnapshot().phase)
        if (!ClusterMapCard.shows(enabled, box, camera, picture != null) || box == null || picture == null) {
            hide()
            return
        }
        val shown = window ?: open(context) ?: return
        shown.draw(box, picture)
        if (cameraCheck == null) {
            val check = object : Runnable {
                override fun run() {
                    cameraCheck = null
                    update()
                }
            }
            cameraCheck = check
            main.postDelayed(check, CAMERA_CHECK_MS)
        }
    }

    private fun open(context: Context): CardPresentation? {
        val selection = ClusterDisplayResolver.resolveCameraOverlay(context)
        val id = (selection as? ClusterDisplaySelection.Selected)?.display?.id ?: run {
            Log.w(TAG, "no overlay display: $selection")
            return null
        }
        val display: Display = context.getSystemService(DisplayManager::class.java)?.getDisplay(id) ?: return null
        return try {
            CardPresentation(context, display).also {
                it.show()
                window = it
                Log.i(TAG, "shown on display $id")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "card window refused on display $id", error)
            null
        }
    }

    private fun hide() {
        cameraCheck?.let(main::removeCallbacks)
        cameraCheck = null
        val gone = window ?: return
        window = null
        try {
            gone.dismiss()
        } catch (error: RuntimeException) {
            Log.w(TAG, "card window dismiss failed", error)
        }
        Log.i(TAG, "hidden")
    }

    /** The overlay display's whole size, transparent but for the card; no touch, no focus. */
    private class CardPresentation(context: Context, display: Display) : Presentation(context, display) {
        private val view = CardView(context)

        // As ClusterPresentation: the platform chooses the window type, only the flags are ours.
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                )
            }
            setContentView(view)
        }

        fun draw(card: ClusterMapCard.Box, picture: Bitmap) {
            view.card = card
            view.picture = picture
            view.invalidate()
        }
    }

    private class CardView(context: Context) : View(context) {
        var card: ClusterMapCard.Box? = null
        var picture: Bitmap? = null
        private val black = Paint().apply { color = Color.BLACK }
        private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
        private val from = Rect()
        private val to = Rect()

        override fun onDraw(canvas: Canvas) {
            val card = card ?: return
            val picture = picture ?: return
            canvas.drawRect(card.left.toFloat(), card.top.toFloat(), card.right.toFloat(), card.bottom.toFloat(), black)
            val box = ClusterMapCard.frameBox(card, picture.width, picture.height)
            from.set(0, 0, picture.width, picture.height)
            to.set(box.left, box.top, box.right, box.bottom)
            canvas.drawBitmap(picture, from, to, smooth)
        }
    }
}
