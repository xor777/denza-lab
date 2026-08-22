package dev.denza.apps.feature.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherLocationLabelTest {
    @Test
    fun prefersCityAndKeepsLocalizedAdministrativeData() {
        val label = WeatherLocationLabel.fromComponents(
            locality = "  Москва  ",
            subAdminArea = "Центральный административный округ",
            adminArea = "Москва",
            countryName = "Россия",
            countryCode = "ru",
        )

        assertEquals(
            WeatherLocationLabel(
                city = "Москва",
                region = "Москва",
                countryName = "Россия",
                countryCode = "RU",
            ),
            label,
        )
    }

    @Test
    fun fallsBackFromMissingLocalityWithoutInventingAName() {
        assertEquals(
            "Одинцовский городской округ",
            WeatherLocationLabel.fromComponents(
                locality = " ",
                subAdminArea = "Одинцовский  городской\nокруг",
                adminArea = "Московская область",
                countryName = null,
                countryCode = null,
            )?.city,
        )
        assertEquals(
            "Московская область",
            WeatherLocationLabel.fromComponents(
                locality = null,
                subAdminArea = null,
                adminArea = "Московская область",
                countryName = null,
                countryCode = null,
            )?.city,
        )
        assertNull(
            WeatherLocationLabel.fromComponents(
                locality = null,
                subAdminArea = " ",
                adminArea = "\n",
                countryName = "Россия",
                countryCode = "RU",
            ),
        )
    }
}
