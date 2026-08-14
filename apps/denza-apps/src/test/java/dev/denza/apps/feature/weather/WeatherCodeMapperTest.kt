package dev.denza.apps.feature.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherCodeMapperTest {
    @Test
    fun mapsMetSymbolsToNativeWeatherIds() {
        assertEquals(0, WeatherCodeMapper.fromMetSymbol("clearsky_day").id)
        assertEquals(1, WeatherCodeMapper.fromMetSymbol("partlycloudy_night").id)
        assertEquals(3, WeatherCodeMapper.fromMetSymbol("rainshowers_day").id)
        assertEquals(4, WeatherCodeMapper.fromMetSymbol("heavyrainandthunder").id)
        assertEquals(6, WeatherCodeMapper.fromMetSymbol("sleet").id)
        assertEquals(10, WeatherCodeMapper.fromMetSymbol("heavyrain").id)
        assertEquals(13, WeatherCodeMapper.fromMetSymbol("snowshowers_night").id)
        assertEquals(18, WeatherCodeMapper.fromMetSymbol("fog").id)
        assertEquals(2, WeatherCodeMapper.fromMetSymbol(null).id)
    }

    @Test
    fun mapsWindSpeedAndDirection() {
        assertEquals(0, WeatherCodeMapper.windLevel(0.1))
        assertEquals(3, WeatherCodeMapper.windLevel(4.0))
        assertEquals(9, WeatherCodeMapper.windLevel(25.0))
        assertEquals("N", WeatherCodeMapper.cardinal(359.0))
        assertEquals("E", WeatherCodeMapper.cardinal(90.0))
        assertEquals("W", WeatherCodeMapper.cardinal(-90.0))
    }

    @Test
    fun failsSafeForMutatedNonFiniteSensorValues() {
        assertEquals(0, WeatherCodeMapper.windLevel(-1.0))
        assertEquals(0, WeatherCodeMapper.windLevel(Double.NaN))
        assertEquals(0, WeatherCodeMapper.windLevel(Double.NEGATIVE_INFINITY))
        assertEquals("N", WeatherCodeMapper.cardinal(Double.NaN))
        assertEquals("N", WeatherCodeMapper.cardinal(Double.POSITIVE_INFINITY))
    }

    @Test
    fun keepsEveryMetConditionMutationInsideTheNativeIconRange() {
        val symbols = listOf(
            "clearsky_day",
            "fair_night",
            "partlycloudy_day",
            "cloudy",
            "fog",
            "lightrainshowers_day",
            "rainshowers_night",
            "heavyrainshowers_day",
            "lightsleetshowers_day",
            "sleetshowers_night",
            "heavysleetshowers_day",
            "lightsnowshowers_day",
            "snowshowers_night",
            "heavysnowshowers_day",
            "lightrain",
            "rain",
            "heavyrain",
            "lightsleet",
            "sleet",
            "heavysleet",
            "lightsnow",
            "snow",
            "heavysnow",
            "heavysnowandthunder",
            "unknown_future_symbol",
        )

        symbols.forEach { symbol ->
            check(WeatherCodeMapper.fromMetSymbol(symbol).id in 0..18) { symbol }
        }
    }
}
