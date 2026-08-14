package dev.denza.apps.feature.weather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherForecastCachePolicyTest {
    @Test
    fun servesOnlyBoundedCacheAfterAConnectionMutation() {
        val now = 1_000_000_000L
        val limit = WeatherAdapterConfig.MAX_STALE_FORECAST_MILLIS

        assertFalse(WeatherForecastCachePolicy.canServeAfterNetworkFailure(false, now, now))
        assertFalse(WeatherForecastCachePolicy.canServeAfterNetworkFailure(true, 0L, now))
        assertTrue(WeatherForecastCachePolicy.canServeAfterNetworkFailure(true, now - limit, now))
        assertFalse(WeatherForecastCachePolicy.canServeAfterNetworkFailure(true, now - limit - 1L, now))
    }

    @Test
    fun boundsClockRollbackMutationAsWell() {
        val now = 1_000_000_000L
        val limit = WeatherAdapterConfig.MAX_STALE_FORECAST_MILLIS

        assertTrue(WeatherForecastCachePolicy.canServeAfterNetworkFailure(true, now + limit, now))
        assertFalse(WeatherForecastCachePolicy.canServeAfterNetworkFailure(true, now + limit + 1L, now))
    }
}
