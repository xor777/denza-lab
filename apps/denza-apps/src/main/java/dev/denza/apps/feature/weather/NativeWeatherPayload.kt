package dev.denza.apps.feature.weather

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

internal object NativeWeatherPayload {
    fun build(
        forecast: JSONObject,
        latitude: Double,
        longitude: Double,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val timeseries = forecast.getJSONObject("properties").getJSONArray("timeseries")
        val points = (0 until timeseries.length()).mapNotNull { index ->
            parsePoint(timeseries.optJSONObject(index), zoneId)
        }
        require(points.isNotEmpty()) { "Forecast contains no usable points" }

        val current = points.minBy { abs(it.instant.toEpochMilli() - nowMillis) }
        val currentDetails = current.details
        val currentCondition = WeatherCodeMapper.fromMetSymbol(current.symbol)
        val temperature = currentDetails.optDouble("air_temperature", 0.0).roundToInt()
        val humidity = currentDetails.optDouble("relative_humidity", 0.0).roundToInt()
        val pressure = currentDetails.optDouble("air_pressure_at_sea_level", 0.0).roundToInt()
        val windSpeed = currentDetails.optDouble("wind_speed", 0.0)
        val windDegrees = currentDetails.optDouble("wind_from_direction", 0.0)
        val windDirection = WeatherCodeMapper.cardinal(windDegrees)
        val visibilityKilometres = currentDetails.optDouble("visibility", 10_000.0)
            .let { value -> if (value > 100.0) value / 1_000.0 else value }
            .roundToInt()
        val updateMillis = current.instant.toEpochMilli()
        val updateText = current.localTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        val native = JSONObject()
            .put("ts", nowMillis)
            .put("citycode", "GPS")
            .put("city", city(latitude, longitude, zoneId))
            .put("weatherDesc", "Прогноз MET Norway")
            .put("mobilelink", "")
            .put("updatetime", updateMillis)
            .put(
                "condition",
                JSONObject()
                    .put("updatetime", updateMillis)
                    .put("updatetimeFmt", updateText)
                    .put("windgustspeed", (windSpeed * 3.6).roundToInt())
                    .put("realfeel", temperature)
                    .put("realfeelDesc", "")
                    .put("feelTemperatureShade", temperature)
                    .put("windgustdir", windDirection)
                    .put("weatherMapLink", "")
                    .put("uVIndex", 0)
                    .put("uvIndexDesc", "")
                    .put("precipitation", current.precipitation)
                    .put("comfortlink", "")
                    .put("winddir", windDirection)
                    .put("winddirtext", windDirection)
                    .put("winddegrees", windDegrees.roundToInt())
                    .put("temperature", temperature)
                    .put("humidity", humidity)
                    .put("windgustlevel", WeatherCodeMapper.windLevel(windSpeed))
                    .put("windlevel", WeatherCodeMapper.windLevel(windSpeed))
                    .put("visibility", visibilityKilometres)
                    .put("cloudCover", currentDetails.optDouble("cloud_area_fraction", 0.0).roundToInt())
                    .put("pressure", pressure)
                    .put("pressureTendency", "S")
                    .put("weatherid", currentCondition.id)
                    .put("cnweatherid", currentCondition.id)
                    .put("zmweatherid", currentCondition.id)
                    .put("windspeed", (windSpeed * 3.6).roundToInt())
                    .put("weathertext", currentCondition.label)
                    .put("desc", "Источник: MET Norway")
                    .put("mobilelink", "")
                    .put("expiretime", nowMillis + 60L * 60L * 1_000L)
                    .put("compareFlag", "")
                    .put("vipLocation", ""),
            )
            .put("liveInfos", JSONArray())
            .put(
                "radar",
                JSONObject()
                    .put("dataTime", updateMillis)
                    .put("skycon", current.symbol)
                    .put("dataseries", JSONArray()),
            )
            .put("dailys", dailyForecast(points, latitude, longitude, zoneId, nowMillis))
            .put("aqidays", JSONArray())
            .put("aqi", unavailableAqi(updateMillis))
            .put("hourlys", hourlyForecast(points, nowMillis))
            .put("alarm", JSONArray())

        return JSONObject()
            .put("servertime", nowMillis)
            .put("resultinfo", "MET Norway adapter")
            .put("resultcode", "0")
            .put("data", native)
            .toString()
    }

    private fun city(latitude: Double, longitude: Double, zoneId: ZoneId): JSONObject =
        JSONObject()
            .put("citycode", "GPS")
            .put("provincename", "GPS")
            .put("name", "GPS")
            .put("co", longitude.toString())
            .put("ca", latitude.toString())
            .put("timezone", zoneId.id)
            .put("parentcity", "GPS")
            .put("level", 3)
            .put("englishCityName", "GPS")
            .put("countryCode", "")
            .put("countryname", "")
            .put("englishCountryName", "")
            .put(
                "supplementalAdminAreas",
                JSONArray().put(
                    JSONObject()
                        .put("id", "GPS")
                        .put("localizedName", "GPS")
                        .put("englishName", "GPS")
                        .put("level", 2),
                ),
            )
            .put(
                "administrativearea",
                JSONObject()
                    .put("id", "GPS")
                    .put("localizedname", "GPS")
                    .put("englishName", "GPS")
                    .put("level", 1),
            )

    private fun dailyForecast(
        points: List<ForecastPoint>,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        nowMillis: Long,
    ): JSONObject {
        val grouped = linkedMapOf<LocalDate, DailyAggregate>()
        points.forEach { point ->
            val temperature = point.details.optDouble("air_temperature", Double.NaN)
            if (temperature.isNaN()) return@forEach
            grouped.getOrPut(point.localTime.toLocalDate()) { DailyAggregate() }.add(point, temperature)
        }
        val days = JSONArray()
        grouped.entries.take(9).forEach { (date, aggregate) ->
            val start = date.atStartOfDay(zoneId)
            val solar = SolarTimes.calculate(date, latitude, longitude, zoneId)
            val dayPoint = aggregate.dayPoint ?: aggregate.firstPoint
            val nightPoint = aggregate.nightPoint ?: aggregate.lastPoint
            val dayCondition = WeatherCodeMapper.fromMetSymbol(dayPoint?.symbol)
            val nightCondition = WeatherCodeMapper.fromMetSymbol(nightPoint?.symbol)
            val compatibilityDate = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            days.put(
                JSONObject()
                    .put("publictime", start.toInstant().toEpochMilli())
                    .put("publictimeFmt", compatibilityDate)
                    .put("moonSet", start.toInstant().toEpochMilli())
                    .put("aqivalue", 0)
                    .put("lv", 0)
                    .put("source", "MET Norway")
                    .put("sunSet", solar.sunset.toInstant().toEpochMilli())
                    .put("realFeelTempMax", aggregate.max.roundToInt().toString())
                    .put("realFeelTempMin", aggregate.min.roundToInt().toString())
                    .put("uvIndex", 0)
                    .put("conditionDay", dailyCondition(dayPoint, dayCondition))
                    .put("moonRise", start.toInstant().toEpochMilli())
                    .put("conditionNight", dailyCondition(nightPoint, nightCondition))
                    .put("mintemp", aggregate.min.roundToInt())
                    .put("maxtemp", aggregate.max.roundToInt())
                    .put("currentFestival", "")
                    .put("visibility", 10)
                    .put("pressure", aggregate.pressure.roundToInt())
                    .put("sunRise", solar.sunrise.toInstant().toEpochMilli())
                    .put("moonphase", "")
                    .put("pm25", 0)
                    .put("spanDays", 0)
                    .put("spanDaysFull", 0)
                    .put("currentlink", "")
                    .put("mobilelink", "")
                    .put("currentRestrict", "")
                    // The stock service keys today's min/max by the date embedded in moonSetFmt.
                    .put("moonSetFmt", compatibilityDate)
                    .put("spanDaysNew", 0)
                    .put("sunSetFmt", solar.sunset.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .put("uvIndexText", "--")
                    .put("moonRiseFmt", compatibilityDate)
                    .put("sunRiseFmt", solar.sunrise.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .put("aqivaluetext", "--"),
            )
        }
        return JSONObject()
            .put("publictime", nowMillis)
            .put("publictimeFmt", ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("mobilelink", "")
            .put("expiretime", nowMillis + 60L * 60L * 1_000L)
            .put("dailyweathers", days)
    }

    private fun dailyCondition(
        point: ForecastPoint?,
        condition: NativeWeatherCondition,
    ): JSONObject {
        val details = point?.details ?: JSONObject()
        val windSpeed = details.optDouble("wind_speed", 0.0)
        val windDirection = WeatherCodeMapper.cardinal(details.optDouble("wind_from_direction", 0.0))
        val precipitation = point?.precipitation ?: 0.0
        return JSONObject()
            .put("windGustDir", windDirection)
            .put("rain", precipitation.toString())
            .put("windlevel", WeatherCodeMapper.windLevel(windSpeed))
            .put("cloudCover", details.optDouble("cloud_area_fraction", 0.0).roundToInt().toString())
            .put("precProb", point?.precipitationProbability ?: 0)
            .put("thunProb", "0")
            .put("snowProb", "0")
            .put("winddir", windDirection)
            .put("ice", "0")
            .put("rainProb", (point?.precipitationProbability ?: 0).toString())
            .put("iceProb", "0")
            .put("windGustPow", WeatherCodeMapper.windLevel(windSpeed).toString())
            .put("snow", "0")
            .put("windspeed", (windSpeed * 3.6).roundToInt())
            .put("humidity", details.optDouble("relative_humidity", 0.0).roundToInt())
            .put("totalLiquid", precipitation.toString())
            .put("weatherid", condition.id)
            .put("cnweatherid", condition.id)
            .put("zmweatherid", condition.id)
            .put("weathertext", condition.label)
    }

    private fun hourlyForecast(points: List<ForecastPoint>, nowMillis: Long): JSONObject {
        val hours = JSONArray()
        points.take(48).forEach { point ->
            val condition = WeatherCodeMapper.fromMetSymbol(point.symbol)
            val windSpeed = point.details.optDouble("wind_speed", 0.0)
            hours.put(
                JSONObject()
                    .put("date", point.instant.toEpochMilli())
                    .put("weatherid", condition.id)
                    .put("temp", point.details.optDouble("air_temperature", 0.0).roundToInt())
                    .put("rainprobability", point.precipitationProbability)
                    .put("precipitation", point.precipitation)
                    .put("wp", WeatherCodeMapper.windLevel(windSpeed))
                    .put("wd", WeatherCodeMapper.cardinal(point.details.optDouble("wind_from_direction", 0.0)))
                    .put("cnweatherid", condition.id)
                    .put("zmweatherid", condition.id)
                    .put("mobilelink", "")
                    .put("Isdaynight", point.symbol.endsWith("_day")),
            )
        }
        return JSONObject()
            .put("expiretime", nowMillis + 60L * 60L * 1_000L)
            .put("hourlyweathers", hours)
    }

    private fun unavailableAqi(updateMillis: Long): JSONObject = JSONObject()
        .put("updatetime", updateMillis)
        .put("o3", 0)
        .put("pm25desc", "")
        .put("pm10", 0)
        .put("aqivaluetext", "--")
        .put("lv", 0)
        .put("aqidesc", "")
        .put("co", 0)
        .put("no2", 0)
        .put("aqivalue", 0)
        .put("pm25", 0)
        .put("so2", 0)
        .put("mobilelink", "")

    private fun parsePoint(value: JSONObject?, zoneId: ZoneId): ForecastPoint? {
        value ?: return null
        val instant = runCatching { Instant.parse(value.getString("time")) }.getOrNull() ?: return null
        val data = value.optJSONObject("data") ?: return null
        val details = data.optJSONObject("instant")?.optJSONObject("details") ?: return null
        val nextHour = data.optJSONObject("next_1_hours")
        val nextSixHours = data.optJSONObject("next_6_hours")
        val symbol = nextHour?.optJSONObject("summary")?.optString("symbol_code")
            ?.takeIf { it.isNotBlank() }
            ?: nextSixHours?.optJSONObject("summary")?.optString("symbol_code")
                ?.takeIf { it.isNotBlank() }
            ?: "cloudy"
        val precipitationDetails = nextHour?.optJSONObject("details")
            ?: nextSixHours?.optJSONObject("details")
            ?: JSONObject()
        return ForecastPoint(
            instant = instant,
            localTime = ZonedDateTime.ofInstant(instant, zoneId),
            details = details,
            symbol = symbol,
            precipitation = precipitationDetails.optDouble("precipitation_amount", 0.0),
            precipitationProbability = precipitationDetails
                .optDouble("probability_of_precipitation", 0.0)
                .roundToInt(),
        )
    }

    private data class ForecastPoint(
        val instant: Instant,
        val localTime: ZonedDateTime,
        val details: JSONObject,
        val symbol: String,
        val precipitation: Double,
        val precipitationProbability: Int,
    )

    private class DailyAggregate {
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        var pressure = 0.0
        var firstPoint: ForecastPoint? = null
        var lastPoint: ForecastPoint? = null
        var dayPoint: ForecastPoint? = null
        var nightPoint: ForecastPoint? = null
        private var dayDistance = Int.MAX_VALUE
        private var nightDistance = Int.MAX_VALUE
        private var pressureCount = 0

        fun add(point: ForecastPoint, temperature: Double) {
            min = minOf(min, temperature)
            max = maxOf(max, temperature)
            firstPoint = firstPoint ?: point
            lastPoint = point
            val value = point.details.optDouble("air_pressure_at_sea_level", Double.NaN)
            if (!value.isNaN()) {
                pressure = ((pressure * pressureCount) + value) / (pressureCount + 1)
                pressureCount += 1
            }
            val dayCandidate = abs(point.localTime.hour - 12)
            if (dayCandidate < dayDistance) {
                dayDistance = dayCandidate
                dayPoint = point
            }
            val nightCandidate = minOf(abs(point.localTime.hour), abs(point.localTime.hour - 23))
            if (nightCandidate < nightDistance) {
                nightDistance = nightCandidate
                nightPoint = point
            }
        }
    }

    private data class SolarTimes(
        val sunrise: ZonedDateTime,
        val sunset: ZonedDateTime,
    ) {
        companion object {
            fun calculate(
                date: LocalDate,
                latitude: Double,
                longitude: Double,
                zoneId: ZoneId,
            ): SolarTimes {
                val sunrise = solarEvent(date, latitude, longitude, true)
                    ?.atZone(zoneId)
                    ?: date.atTime(LocalTime.of(6, 0)).atZone(zoneId)
                val sunset = solarEvent(date, latitude, longitude, false)
                    ?.atZone(zoneId)
                    ?: date.atTime(LocalTime.of(18, 0)).atZone(zoneId)
                return SolarTimes(sunrise, sunset)
            }

            private fun solarEvent(
                date: LocalDate,
                latitude: Double,
                longitude: Double,
                sunrise: Boolean,
            ): Instant? {
                val longitudeHour = longitude / 15.0
                val approximate = date.dayOfYear +
                    (((if (sunrise) 6.0 else 18.0) - longitudeHour) / 24.0)
                val meanAnomaly = (0.9856 * approximate) - 3.289
                var trueLongitude = meanAnomaly +
                    (1.916 * sin(Math.toRadians(meanAnomaly))) +
                    (0.020 * sin(Math.toRadians(2 * meanAnomaly))) + 282.634
                trueLongitude = normalizeDegrees(trueLongitude)
                var rightAscension = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(trueLongitude))))
                rightAscension = normalizeDegrees(rightAscension)
                rightAscension += (kotlin.math.floor(trueLongitude / 90.0) * 90.0) -
                    (kotlin.math.floor(rightAscension / 90.0) * 90.0)
                rightAscension /= 15.0
                val sinDeclination = 0.39782 * sin(Math.toRadians(trueLongitude))
                val cosDeclination = cos(asin(sinDeclination))
                val cosHour = (
                    cos(Math.toRadians(90.833)) -
                        (sinDeclination * sin(Math.toRadians(latitude)))
                    ) / (cosDeclination * cos(Math.toRadians(latitude)))
                if (cosHour !in -1.0..1.0) return null
                var hour = if (sunrise) 360.0 - Math.toDegrees(acos(cosHour)) else Math.toDegrees(acos(cosHour))
                hour /= 15.0
                val localMeanTime = hour + rightAscension - (0.06571 * approximate) - 6.622
                val universalHour = normalizeHours(localMeanTime - longitudeHour)
                val seconds = (universalHour * 3_600.0).roundToInt().toLong()
                return date.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(seconds)
            }

            private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
            private fun normalizeHours(value: Double): Double = ((value % 24.0) + 24.0) % 24.0
        }
    }
}
