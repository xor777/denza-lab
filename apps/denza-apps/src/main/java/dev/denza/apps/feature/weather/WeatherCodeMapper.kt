package dev.denza.apps.feature.weather

import dev.denza.apps.R
import dev.denza.apps.core.AppText

internal data class NativeWeatherCondition(
    val id: Int,
    val label: String,
)

internal object WeatherCodeMapper {
    fun fromMetSymbol(value: String?): NativeWeatherCondition {
        val symbol = value.orEmpty().lowercase()
        return when {
            "thunder" in symbol -> NativeWeatherCondition(4, AppText.s(R.string.weather_condition_thunderstorm))
            "sleet" in symbol -> NativeWeatherCondition(6, AppText.s(R.string.weather_condition_sleet))
            "snowshowers" in symbol -> NativeWeatherCondition(13, AppText.s(R.string.weather_condition_snow_showers))
            "heavysnow" in symbol -> NativeWeatherCondition(16, AppText.s(R.string.weather_condition_heavy_snow))
            "lightsnow" in symbol -> NativeWeatherCondition(14, AppText.s(R.string.weather_condition_light_snow))
            "snow" in symbol -> NativeWeatherCondition(15, AppText.s(R.string.weather_condition_snow))
            "rainshowers" in symbol -> NativeWeatherCondition(3, AppText.s(R.string.weather_condition_rain_showers))
            "heavyrain" in symbol -> NativeWeatherCondition(10, AppText.s(R.string.weather_condition_heavy_rain))
            "lightrain" in symbol -> NativeWeatherCondition(7, AppText.s(R.string.weather_condition_light_rain))
            "rain" in symbol -> NativeWeatherCondition(8, AppText.s(R.string.weather_condition_rain))
            "clearsky" in symbol -> NativeWeatherCondition(0, AppText.s(R.string.weather_condition_clear))
            "fair" in symbol || "partlycloudy" in symbol ->
                NativeWeatherCondition(1, AppText.s(R.string.weather_condition_partly_cloudy))
            "fog" in symbol -> NativeWeatherCondition(18, AppText.s(R.string.weather_condition_fog))
            else -> NativeWeatherCondition(2, AppText.s(R.string.weather_condition_cloudy))
        }
    }

    fun windLevel(speedMetresPerSecond: Double): Int {
        if (!speedMetresPerSecond.isFinite() || speedMetresPerSecond <= 0.0) return 0
        val thresholds = doubleArrayOf(0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8)
        return thresholds.indexOfFirst { speedMetresPerSecond < it }.takeIf { it >= 0 } ?: 9
    }

    fun cardinal(degrees: Double): String {
        if (!degrees.isFinite()) return "N"
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        return names[((normalized + 22.5) / 45.0).toInt() % names.size]
    }
}
