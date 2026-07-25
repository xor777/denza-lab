package dev.denza.apps.feature.navigation

data class SteeringWheelPressResult(
    val navigationActionsBefore: Int = 0,
    val toggleDvrCamera: Boolean = false,
)

/**
 * Delays the normal ★ action just long enough to distinguish a double press.
 *
 * One press retains the navigation action after the inter-press window expires.
 * Two presses consume the whole sequence and request the DVR camera toggle
 * instead.
 */
class SteeringWheelPressSequence(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private var pressCount = 0
    private var lastPressAtMs = 0L

    init {
        require(timeoutMs > 0L)
    }

    fun onPress(nowMs: Long): SteeringWheelPressResult {
        var navigationActionsBefore = 0
        if (pressCount > 0 && nowMs - lastPressAtMs >= timeoutMs) {
            navigationActionsBefore = pressCount
            reset()
        }
        pressCount += 1
        lastPressAtMs = nowMs
        if (pressCount == DOUBLE_PRESS_COUNT) {
            reset()
            return SteeringWheelPressResult(
                navigationActionsBefore = navigationActionsBefore,
                toggleDvrCamera = true,
            )
        }
        return SteeringWheelPressResult(
            navigationActionsBefore = navigationActionsBefore,
        )
    }

    fun flushNavigationActions(nowMs: Long): Int {
        if (pressCount == 0 || nowMs - lastPressAtMs < timeoutMs) return 0
        val actions = pressCount
        reset()
        return actions
    }

    fun delayUntilFlush(nowMs: Long): Long {
        if (pressCount == 0) return 0L
        return (lastPressAtMs + timeoutMs - nowMs).coerceAtLeast(0L)
    }

    fun reset() {
        pressCount = 0
        lastPressAtMs = 0L
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 500L
        private const val DOUBLE_PRESS_COUNT = 2
    }
}
