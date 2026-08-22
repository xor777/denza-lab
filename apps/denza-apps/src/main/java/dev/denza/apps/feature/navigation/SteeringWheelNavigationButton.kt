package dev.denza.apps.feature.navigation

/**
 * Policy for the Denza configurable steering-wheel key.
 *
 * The DiLink key layout maps Linux input code 300 (AUTO_CUSTOM_KEY) to the
 * vendor Android key code 321. Long press is a different key code (322), so it
 * stays available to the stock custom-key settings flow.
 */
object SteeringWheelNavigationButton {
    const val KEY_CODE = 321
    private const val ACTION_DOWN = 0

    @JvmStatic
    fun shouldConsume(enabled: Boolean, keyCode: Int): Boolean =
        enabled && keyCode == KEY_CODE

    @JvmStatic
    fun shouldTrigger(
        enabled: Boolean,
        keyCode: Int,
        action: Int,
        repeatCount: Int,
    ): Boolean =
        shouldConsume(enabled, keyCode) &&
            action == ACTION_DOWN &&
            repeatCount == 0
}

fun interface SteeringWheelNavigationAction {
    fun perform(): Boolean
}

/**
 * Owns one physical ★ press only after navigation accepts its initial DOWN.
 * There is intentionally no timing or multi-press state: every new DOWN is an
 * independent navigation request.
 */
class SteeringWheelKeyInterceptor {
    private var pressOwned = false

    fun onKeyEvent(
        enabled: Boolean,
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        navigationAction: SteeringWheelNavigationAction,
    ): Boolean {
        if (keyCode != SteeringWheelNavigationButton.KEY_CODE) return false
        if (!enabled) {
            val consume = pressOwned
            if (action == ACTION_UP) pressOwned = false
            return consume
        }
        if (
            SteeringWheelNavigationButton.shouldTrigger(
                enabled = enabled,
                keyCode = keyCode,
                action = action,
                repeatCount = repeatCount,
            )
        ) {
            pressOwned = navigationAction.perform()
        }
        val consume = pressOwned
        if (action == ACTION_UP) pressOwned = false
        return consume
    }

    fun reset() {
        pressOwned = false
    }

    private companion object {
        const val ACTION_UP = 1
    }
}
