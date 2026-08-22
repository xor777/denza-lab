package dev.denza.apps.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
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

/** Shared SystemUI-shaped progress window for short car-wide transitions. */
internal class VehicleProgressOverlay(
    @param:StringRes private val textRes: Int,
    private val windowTitle: String,
    private val inputMode: VehicleProgressOverlayInputMode,
) {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var visible = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var removeRetryCount = 0

    fun setVisible(context: Context, visible: Boolean) {
        appContext = context.applicationContext
        this.visible = visible
        context.mainExecutor.execute(::renderLatestState)
    }

    private fun renderLatestState() {
        val context = appContext ?: return
        if (visible && Settings.canDrawOverlays(context)) {
            show(context)
        } else {
            hide()
        }
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
            gravity = Gravity.CENTER
            setTitle(windowTitle)
        }
        try {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            removeRetryCount = 0
        } catch (error: RuntimeException) {
            Log.i(TAG, "$windowTitle overlay unavailable", error)
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
        val inputFlags = if (inputMode == VehicleProgressOverlayInputMode.BLOCK_SCREEN) {
            0
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        return WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or inputFlags
    }

    private fun createWindowView(context: Context): View {
        val card = createCard(context)
        if (inputMode == VehicleProgressOverlayInputMode.PASS_THROUGH) return card
        return FrameLayout(context).apply {
            isClickable = true
            isFocusable = false
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

    private companion object {
        const val TAG = "DenzaProgressOverlay"
        const val MAX_REMOVE_RETRIES = 3
        const val REMOVE_RETRY_MS = 100L
    }
}
