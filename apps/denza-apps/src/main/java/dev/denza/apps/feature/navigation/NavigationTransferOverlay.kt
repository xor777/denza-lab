package dev.denza.apps.feature.navigation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.denza.apps.R

internal data class NavigationTransferOverlayState(
    val transferActive: Boolean = false,
    val mainActivityResumed: Boolean = false,
) {
    val shouldShow: Boolean
        get() = transferActive && !mainActivityResumed
}

/** Exact visual tokens from this vehicle's SystemUI text toast. */
internal object NavigationTransferOverlayStyle {
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

/**
 * Shows a small, non-interactive status window on the main IVI display while a
 * navigation task is moving. The Denza Apps screen already renders the same
 * transition in its Navigation card, so the overlay stays hidden while that
 * Activity is active.
 */
object NavigationTransferOverlay {
    private const val TAG = "DenzaNavigationTransfer"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    @Volatile
    private var state = NavigationTransferOverlayState()

    @Volatile
    private var appContext: Context? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    fun setTransferActive(context: Context, active: Boolean) {
        updateState(context) { copy(transferActive = active) }
    }

    fun setMainActivityResumed(context: Context, resumed: Boolean) {
        updateState(context) { copy(mainActivityResumed = resumed) }
    }

    /** Retry rendering after the shell has repaired the overlay app-op. */
    fun refresh(context: Context) {
        appContext = context.applicationContext
        mainHandler.post(::renderLatestState)
    }

    private fun updateState(
        context: Context,
        transform: NavigationTransferOverlayState.() -> NavigationTransferOverlayState,
    ) {
        appContext = context.applicationContext
        synchronized(stateLock) {
            state = state.transform()
        }
        mainHandler.post(::renderLatestState)
    }

    private fun renderLatestState() {
        val context = appContext ?: return
        val latest = state
        if (latest.shouldShow && Settings.canDrawOverlays(context)) {
            show(context)
        } else {
            hide()
        }
    }

    private fun show(context: Context) {
        if (overlayView?.isAttachedToWindow == true) return
        val manager = context.getSystemService(WindowManager::class.java)
        val view = createOverlayView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            setTitle("Denza navigation transfer")
        }
        try {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
        } catch (error: RuntimeException) {
            Log.i(TAG, "navigation transfer overlay unavailable", error)
        }
    }

    private fun hide() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (error: RuntimeException) {
            Log.i(TAG, "navigation transfer overlay already removed", error)
        } finally {
            windowManager = null
        }
    }

    private fun createOverlayView(context: Context): View {
        val horizontalPadding = context.dp(NavigationTransferOverlayStyle.horizontalPaddingDp)
        val verticalPadding = context.dp(NavigationTransferOverlayStyle.verticalPaddingDp)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(NavigationTransferOverlayStyle.minimumHeightDp)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(NavigationTransferOverlayStyle.cornerRadiusDp).toFloat()
                setColor(NavigationTransferOverlayStyle.backgroundColor)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.navigation_transfer_overlay_text)

            addView(
                ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(
                        NavigationTransferOverlayStyle.foregroundColor,
                    )
                },
                LinearLayout.LayoutParams(
                    context.dp(NavigationTransferOverlayStyle.indicatorSizeDp),
                    context.dp(NavigationTransferOverlayStyle.indicatorSizeDp),
                ),
            )
            addView(
                TextView(context).apply {
                    setText(R.string.navigation_transfer_overlay_text)
                    setTextColor(NavigationTransferOverlayStyle.foregroundColor)
                    textSize = NavigationTransferOverlayStyle.textSizeSp
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    gravity = Gravity.CENTER_VERTICAL
                    minWidth = context.dp(NavigationTransferOverlayStyle.minimumTextWidthDp)
                    maxWidth = context.dp(NavigationTransferOverlayStyle.maximumTextWidthDp)
                    minHeight = context.dp(NavigationTransferOverlayStyle.minimumTextHeightDp)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = context.dp(NavigationTransferOverlayStyle.indicatorTextGapDp)
                },
            )
        }
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
