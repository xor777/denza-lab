package dev.denza.apps.feature.weather

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * «Погода»: the stock weather app showing anything at all is the moment to give it a fresh
 * forecast ([WeatherAdapterScheduler.onNativeWeatherVisible], debounced there).
 */
class NativeWeatherRider : AccessibilityRider {
    private var context: Context? = null

    override val name: String = "native-weather"
    override val eventTypes: Int = AccessibilityEvent.TYPES_ALL_MASK
    override val eventPackages: Set<String> = setOf(NATIVE_PACKAGE)

    override fun onConnected(service: AccessibilityService) {
        context = service
    }

    override fun onEvent(event: AccessibilityEvent) {
        context?.let(WeatherAdapterScheduler::onNativeWeatherVisible)
    }

    private companion object {
        const val NATIVE_PACKAGE = "com.byd.weatherdata"
    }
}
