package dev.denza.apps.feature.weather

internal object WeatherAdapterConfig {
    const val REFRESH_INTERVAL_MILLIS = 10L * 60L * 1_000L
    const val INITIAL_REFRESH_DELAY_MILLIS = 5_000L
    const val MAX_STALE_FORECAST_MILLIS = 6L * 60L * 60L * 1_000L

    const val MET_ENDPOINT = "https://api.met.no/weatherapi/locationforecast/2.0/compact"
    const val MET_USER_AGENT =
        "DenzaAppsWeatherAdapter/0.5.1 github.com/xor777/denza-lab"
}
