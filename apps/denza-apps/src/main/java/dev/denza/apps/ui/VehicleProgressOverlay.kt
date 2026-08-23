package dev.denza.apps.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes

/** Exact visual tokens from this vehicle's SystemUI text toast. */
internal object VehicleProgressOverlayStyle {
    val backgroundColor: Int = 0xFF343942.toInt()
    val foregroundColor: Int = 0xE6FFFFFF.toInt()

    /**
     * What a blocking wait actually shows the user (contract 1.13.2, "ожидание честное").
     *
     * The shield used to take the input and nothing else: it was fully transparent, so the car went
     * on showing every intermediate window underneath it - the firmware's own remembered companion
     * flashing up, a half-built pane, an empty picker where an app is about to be. That is dirty
     * waiting, and dirty waiting is red at any duration. The scrim is opaque and matches the dark
     * ground of the picker the wait usually ends on, so the transition into the finished scene reads
     * as one move rather than as a flicker.
     */
    /**
     * Opaque top-to-bottom backdrop in the app's own dark palette: the shield must hide the
     * rebuild (1.13.2), but it does not have to look like a flat grey void while doing so.
     */
    val scrimGradient: IntArray = intArrayOf(0xFF161A21.toInt(), 0xFF0B0E13.toInt())
    const val cornerRadiusDp = 8
    const val horizontalPaddingDp = 16
    const val verticalPaddingDp = 10
    const val minimumHeightDp = 48
    const val indicatorSizeDp = 24
    const val indicatorTextGapDp = 8
    const val minimumTextWidthDp = 88
    const val maximumTextWidthDp = 360
    const val minimumTextHeightDp = 28
    const val textSizeSp = 18f
}

internal enum class VehicleProgressOverlayInputMode {
    PASS_THROUGH,
    BLOCK_SCREEN,
}

/**
 * Shared SystemUI-shaped progress window for short car-wide transitions.
 *
 * @param onUnavailable told once, with the reason, when this window cannot be shown at all - the
 * permission is gone or WindowManager refused it. A wait the user cannot see is exactly the silent
 * failure U5 forbids, so the owner of the overlay gets to say so on a surface that does work.
 */
internal class VehicleProgressOverlay(
    @param:StringRes private val textRes: Int,
    private val windowTitle: String,
    private val inputMode: VehicleProgressOverlayInputMode,
    private val onUnavailable: (String) -> Unit = {},
) {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var visible = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var removeRetryCount = 0
    private var unavailableReported = false

    fun setVisible(context: Context, visible: Boolean) {
        appContext = context.applicationContext
        this.visible = visible
        context.mainExecutor.execute(::renderLatestState)
    }

    private fun renderLatestState() {
        val context = appContext ?: return
        if (!visible) {
            hide()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            reportUnavailable("permission to draw over other apps is not granted")
            hide()
            return
        }
        show(context)
    }

    /** U5: the wait is invisible, so the failure has to be visible somewhere else. Once. */
    private fun reportUnavailable(reason: String) {
        Log.w(TAG, "$windowTitle overlay unavailable: $reason")
        if (unavailableReported) return
        unavailableReported = true
        onUnavailable(reason)
    }

    private fun show(context: Context) {
        val attached = overlayView
        if (attached?.isAttachedToWindow == true) {
            attached.visibility = View.VISIBLE
            attached.isClickable = inputMode == VehicleProgressOverlayInputMode.BLOCK_SCREEN
            overlayParams?.let { params ->
                params.flags = desiredFlags()
                runCatching { windowManager?.updateViewLayout(attached, params) }
                    .onFailure { Log.i(TAG, "$windowTitle overlay re-arm failed", it) }
            }
            removeRetryCount = 0
            return
        }
        val manager = context.getSystemService(WindowManager::class.java)
        val view = createWindowView(context)
        val blocksScreen = inputMode == VehicleProgressOverlayInputMode.BLOCK_SCREEN
        val params = WindowManager.LayoutParams(
            if (blocksScreen) WindowManager.LayoutParams.MATCH_PARENT else {
                WindowManager.LayoutParams.WRAP_CONTENT
            },
            if (blocksScreen) WindowManager.LayoutParams.MATCH_PARENT else {
                WindowManager.LayoutParams.WRAP_CONTENT
            },
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            desiredFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            // A shield is measured against the whole display, not against the area left over by the
            // system bars: an uncovered strip is still somewhere an intermediate window can appear.
            gravity = if (blocksScreen) Gravity.TOP or Gravity.START else Gravity.CENTER
            if (blocksScreen) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            setTitle(windowTitle)
        }
        try {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            removeRetryCount = 0
        } catch (error: RuntimeException) {
            reportUnavailable("window manager refused it: ${error.message ?: error}")
        }
    }

    private fun hide() {
        val view = overlayView ?: return
        val manager = windowManager
        // Release input first. Even if removal fails, the stale surface cannot keep the car UI
        // blocked while the bounded WindowManager retries run.
        view.isClickable = false
        view.visibility = View.INVISIBLE
        overlayParams?.let { params ->
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            runCatching { manager?.updateViewLayout(view, params) }
                .onFailure { Log.i(TAG, "$windowTitle overlay input release failed", it) }
        }
        try {
            manager?.removeViewImmediate(view)
        } catch (error: RuntimeException) {
            Log.i(TAG, "$windowTitle overlay removal failed", error)
            runCatching { manager?.removeView(view) }
                .onFailure { Log.i(TAG, "$windowTitle overlay fallback removal failed", it) }
        }
        if (!view.isAttachedToWindow) {
            overlayView = null
            windowManager = null
            overlayParams = null
            removeRetryCount = 0
        } else if (removeRetryCount < MAX_REMOVE_RETRIES) {
            removeRetryCount += 1
            view.postDelayed(::renderLatestState, REMOVE_RETRY_MS)
        } else {
            Log.w(TAG, "$windowTitle overlay remained attached but input was released")
        }
    }

    private fun desiredFlags(): Int {
        if (inputMode == VehicleProgressOverlayInputMode.BLOCK_SCREEN) {
            // No limits: the shield is laid out over the whole display, system bars included, so
            // there is no strip of the screen the user can watch the rebuild through (1.13.2).
            return WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    private fun createWindowView(context: Context): View {
        val card = createCard(context)
        if (inputMode == VehicleProgressOverlayInputMode.PASS_THROUGH) return card
        return TouchShieldFrameLayout(context).apply {
            isClickable = true
            isFocusable = false
            // 1.13.2: while the wait runs, the only thing on screen is the wait.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                VehicleProgressOverlayStyle.scrimGradient,
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    private fun createCard(context: Context): View {
        val horizontalPadding = context.dp(VehicleProgressOverlayStyle.horizontalPaddingDp)
        val verticalPadding = context.dp(VehicleProgressOverlayStyle.verticalPaddingDp)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(VehicleProgressOverlayStyle.minimumHeightDp)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(VehicleProgressOverlayStyle.cornerRadiusDp).toFloat()
                setColor(VehicleProgressOverlayStyle.backgroundColor)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(textRes)

            addView(
                ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(
                        VehicleProgressOverlayStyle.foregroundColor,
                    )
                },
                LinearLayout.LayoutParams(
                    context.dp(VehicleProgressOverlayStyle.indicatorSizeDp),
                    context.dp(VehicleProgressOverlayStyle.indicatorSizeDp),
                ),
            )
            addView(
                TextView(context).apply {
                    setText(textRes)
                    setTextColor(VehicleProgressOverlayStyle.foregroundColor)
                    textSize = VehicleProgressOverlayStyle.textSizeSp
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    gravity = Gravity.CENTER_VERTICAL
                    minWidth = context.dp(VehicleProgressOverlayStyle.minimumTextWidthDp)
                    maxWidth = context.dp(VehicleProgressOverlayStyle.maximumTextWidthDp)
                    minHeight = context.dp(VehicleProgressOverlayStyle.minimumTextHeightDp)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = context.dp(VehicleProgressOverlayStyle.indicatorTextGapDp)
                },
            )
        }
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private class TouchShieldFrameLayout(context: Context) : FrameLayout(context) {
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private companion object {
        const val TAG = "DenzaProgressOverlay"
        const val MAX_REMOVE_RETRIES = 3
        const val REMOVE_RETRY_MS = 100L
    }
}
