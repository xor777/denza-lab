package dev.denza.apps.feature.split

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.denza.apps.R

/**
 * Covers the stock picker during its asynchronous replacement so it cannot accept an app tap.
 * The accessibility event is delivered before the stock window's first interactive frame on the
 * target firmware, while the local-ADB replacement can take more than a second.
 */
internal class SplitNativePickerInteractionBlocker(
    private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { context.getSystemService(WindowManager::class.java) }

    @Volatile
    private var overlayView: View? = null

    fun isShowing(): Boolean = overlayView != null

    fun show(bounds: Rect?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(bounds) }
            return
        }
        if (overlayView != null) return

        val safeBounds = bounds?.takeIf { it.width() > 0 && it.height() > 0 }
        val view = blockingView()
        val params = WindowManager.LayoutParams(
            safeBounds?.width() ?: WindowManager.LayoutParams.MATCH_PARENT,
            safeBounds?.height() ?: WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = safeBounds?.left ?: 0
            y = safeBounds?.top ?: 0
            title = WINDOW_TITLE
        }
        try {
            windowManager.addView(view, params)
            overlayView = view
            Log.i(TAG, "blocked stock picker interaction bounds=$safeBounds")
        } catch (error: RuntimeException) {
            Log.w(TAG, "stock picker interaction blocker unavailable", error)
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::hide)
            return
        }
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (error: RuntimeException) {
            Log.i(TAG, "stock picker interaction blocker already removed", error)
        }
    }

    private fun blockingView(): View = FrameLayout(context).apply {
        setBackgroundColor(Color.rgb(18, 23, 39))
        isClickable = true
        isFocusable = true
        setOnTouchListener { _, _ -> true }
        contentDescription = context.getString(R.string.split_picker_replacing_text)
        addView(
            TextView(context).apply {
                setText(R.string.split_picker_replacing_text)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, MESSAGE_TEXT_SIZE_SP)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private companion object {
        const val TAG = "DenzaSplitPickerBlocker"
        const val WINDOW_TITLE = "Denza split picker transition"
        const val MESSAGE_TEXT_SIZE_SP = 22f
    }
}
