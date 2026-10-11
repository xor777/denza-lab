package dev.denza.apps.platform.accessibility

/** A key's values, copied while Android still owns the KeyEvent. No pooled event is retained. */
data class AccessibilityKeyEvent(
    val keyCode: Int,
    val action: Int,
    val repeatCount: Int,
    val deviceId: Int = 0,
    val downTime: Long = 0,
    val eventTime: Long = 0,
    val canceled: Boolean = false,
)
