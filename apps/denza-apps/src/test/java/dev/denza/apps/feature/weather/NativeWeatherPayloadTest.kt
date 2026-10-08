package dev.denza.apps.feature.weather

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stock weather widget's payload, built from a MET Norway forecast.
 *
 * The widget is the car's own: it reads what this writes and nothing else, so a day filed under the
 * wrong date or a sunrise on the wrong day is what the driver sees. The expectations come from the
 * forecast the test writes and from almanac times, never from asking the builder.
 */
class NativeWeatherPayloadTest {

    @Test
    fun theCurrentReadingIsThePointNearestNowInTheWidgetsUnits() {
        val now = Instant.parse("2026-06-21T10:20:00Z")
        val forecast = forecast(
            point("2026-06-21T09:00:00Z", temperature = 14.4, wind = 5.0, visibility = 8_000.0),
            point("2026-06-21T10:00:00Z", temperature = 16.6, wind = 10.0, visibility = 12_500.0),
            point("2026-06-21T11:00:00Z", temperature = 18.0, wind = 2.0),
        )
        val condition = data(build(forecast, now)).getJSONObject("condition")

        assertEquals("the point nearest 10:20 is 10:00, rounded", 17, condition.getInt("temperature"))
        assertEquals("metres per second into km/h", 36, condition.getInt("windspeed"))
        assertEquals("metres into kilometres", 13, condition.getInt("visibility"))
        // And the dashboard's tile reads the same point, so the two never disagree.
        assertEquals(17, NativeWeatherPayload.currentTemperature(forecast, now.toEpochMilli(), MOSCOW))
    }

    /**
     * A day is a local day. Moscow is three hours ahead, so 22:00 UTC is already tomorrow there,
     * and the stock service keys today's min and max by the date inside `moonSetFmt`.
     */
    @Test
    fun theDaysAreTheCarsLocalDays() {
        val now = Instant.parse("2026-06-21T09:00:00Z")
        val forecast = forecast(
            point("2026-06-21T09:00:00Z", temperature = 20.0),
            point("2026-06-21T20:00:00Z", temperature = 12.0),
            point("2026-06-21T22:00:00Z", temperature = 30.0),
        )
        val days = data(build(forecast, now)).getJSONObject("dailys").getJSONArray("dailyweathers")

        assertEquals(2, days.length())
        val today = days.getJSONObject(0)
        assertEquals(LocalDate.parse("2026-06-21"), date(today.getString("moonSetFmt")))
        assertEquals("01:00 MSK is tomorrow's, not today's", 20, today.getInt("maxtemp"))
        assertEquals(12, today.getInt("mintemp"))
        assertEquals(LocalDate.parse("2026-06-22"), date(days.getJSONObject(1).getString("moonSetFmt")))
    }

    /** Moscow's solstices against the almanac, to within a few minutes. */
    @Test
    fun sunriseAndSunsetAreTheAlmanacsInMoscow() {
        assertSun(MOSCOW, MOSCOW_LAT, MOSCOW_LON, "2026-06-21", sunrise = "03:44", sunset = "21:18")
        assertSun(MOSCOW, MOSCOW_LAT, MOSCOW_LON, "2026-12-21", sunrise = "08:59", sunset = "15:58")
    }

    private fun assertSun(zone: ZoneId, lat: Double, lon: Double, day: String, sunrise: String, sunset: String) {
        val (rise, set) = sun(zone, lat, lon, day)
        assertMinutes("$day sunrise", LocalTime.parse(sunrise), rise.toLocalTime())
        assertMinutes("$day sunset", LocalTime.parse(sunset), set.toLocalTime())
    }

    private fun assertMinutes(what: String, expected: LocalTime, actual: LocalTime) {
        val apart = kotlin.math.abs(expected.toSecondOfDay() - actual.toSecondOfDay()) / 60
        assertTrue("$what: $actual, the almanac says $expected", apart <= 5)
    }

    /** The first day's sunrise and sunset, as the payload writes them, in [zone]. */
    private fun sun(zone: ZoneId, lat: Double, lon: Double, day: String): Pair<ZonedDateTime, ZonedDateTime> {
        val noon = LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant()
        val payload = NativeWeatherPayload.build(
            forecast(point(noon.toString(), temperature = 10.0)),
            latitude = lat,
            longitude = lon,
            nowMillis = noon.toEpochMilli(),
            zoneId = zone,
        )
        val first = data(payload).getJSONObject("dailys").getJSONArray("dailyweathers").getJSONObject(0)
        return OffsetDateTime.parse(first.getString("sunRiseFmt")).atZoneSameInstant(zone) to
            OffsetDateTime.parse(first.getString("sunSetFmt")).atZoneSameInstant(zone)
    }

    private fun build(forecast: JSONObject, now: Instant): String =
        NativeWeatherPayload.build(forecast, MOSCOW_LAT, MOSCOW_LON, nowMillis = now.toEpochMilli(), zoneId = MOSCOW)

    private fun data(payload: String): JSONObject = JSONObject(payload).getJSONObject("data")

    private fun date(isoOffset: String): LocalDate = OffsetDateTime.parse(isoOffset).toLocalDate()

    private fun forecast(vararg points: JSONObject): JSONObject =
        JSONObject().put("properties", JSONObject().put("timeseries", JSONArray(points.toList())))

    /** One MET Norway timeseries point. */
    private fun point(
        time: String,
        temperature: Double,
        wind: Double = 3.0,
        visibility: Double? = null,
    ): JSONObject {
        val details = JSONObject()
            .put("air_temperature", temperature)
            .put("wind_speed", wind)
            .put("wind_from_direction", 180.0)
            .put("relative_humidity", 60.0)
            .put("air_pressure_at_sea_level", 1013.0)
        if (visibility != null) details.put("visibility", visibility)
        return JSONObject()
            .put("time", time)
            .put(
                "data",
                JSONObject()
                    .put("instant", JSONObject().put("details", details))
                    .put("next_1_hours", JSONObject().put("summary", JSONObject().put("symbol_code", "clearsky_day"))),
            )
    }

    private companion object {
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
        const val MOSCOW_LAT = 55.7558
        const val MOSCOW_LON = 37.6173
    }
}
