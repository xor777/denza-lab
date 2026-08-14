package dev.denza.apps.feature.weather

internal data class NativeWeatherCondition(
    val id: Int,
    val label: String,
)

internal object WeatherCodeMapper {
    fun fromMetSymbol(value: String?): NativeWeatherCondition {
        val symbol = value.orEmpty().lowercase()
        return when {
            "thunder" in symbol -> NativeWeatherCondition(4, "Гроза")
            "sleet" in symbol -> NativeWeatherCondition(6, "Дождь со снегом")
            "snowshowers" in symbol -> NativeWeatherCondition(13, "Снегопад")
            "heavysnow" in symbol -> NativeWeatherCondition(16, "Сильный снег")
            "lightsnow" in symbol -> NativeWeatherCondition(14, "Небольшой снег")
            "snow" in symbol -> NativeWeatherCondition(15, "Снег")
            "rainshowers" in symbol -> NativeWeatherCondition(3, "Ливень")
            "heavyrain" in symbol -> NativeWeatherCondition(10, "Сильный дождь")
            "lightrain" in symbol -> NativeWeatherCondition(7, "Небольшой дождь")
            "rain" in symbol -> NativeWeatherCondition(8, "Дождь")
            "clearsky" in symbol -> NativeWeatherCondition(0, "Ясно")
            "fair" in symbol || "partlycloudy" in symbol ->
                NativeWeatherCondition(1, "Переменная облачность")
            "fog" in symbol -> NativeWeatherCondition(18, "Туман")
            else -> NativeWeatherCondition(2, "Облачно")
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
