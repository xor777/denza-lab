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
    fun theCacheIsServedWithoutARequestOnlyBetweenItsSaveAndItsExpiry() {
        val saved = 1_000_000_000L
        val expires = saved + 30L * 60L * 1_000L

        assertTrue(WeatherForecastCachePolicy.canServeWithoutRequest(true, saved, expires, saved))
        assertTrue(WeatherForecastCachePolicy.canServeWithoutRequest(true, saved, expires, expires - 1L))
        assertFalse(WeatherForecastCachePolicy.canServeWithoutRequest(true, saved, expires, expires))
        assertFalse(WeatherForecastCachePolicy.canServeWithoutRequest(false, saved, expires, saved))
        assertFalse(WeatherForecastCachePolicy.canServeWithoutRequest(true, 0L, expires, saved))
    }

    /**
     * Cached on the car's real date, then the clock is set back a day: every expiry is in the
     * future now. Until 2026-11-19 the fast path compared only the expiry and served the old
     * forecast as this run's; the save time in the future sends the run to MET instead.
     */
    @Test
    fun aClockSetBackSendsTheRunToMetInsteadOfTheCache() {
        val saved = 1_000_000_000_000L
        val expires = saved + 30L * 60L * 1_000L
        val setBack = saved - 24L * 60L * 60L * 1_000L

        assertFalse(WeatherForecastCachePolicy.canServeWithoutRequest(true, saved, expires, setBack))
        assertFalse(WeatherForecastCachePolicy.canServeWithoutRequest(true, saved, expires, saved - 1L))
    }

    @Test
    fun boundsClockRollbackMutationAsWell() {
        val now = 1_000_000_000L
        val limit = WeatherAdapterConfig.MAX_STALE_FORECAST_MILLIS

        assertTrue(WeatherForecastCachePolicy.canServeAfterNetworkFailure(true, now + limit, now))
        assertFalse(WeatherForecastCachePolicy.canServeAfterNetworkFailure(true, now + limit + 1L, now))
    }
}
