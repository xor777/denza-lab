package dev.denza.apps.feature.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherLegacyProxyTest {
    @Test
    fun theSpikesFourProxyKeysAreDeletedInOneCommand() {
        assertEquals(
            "settings delete global http_proxy; " +
                "settings delete global global_http_proxy_host; " +
                "settings delete global global_http_proxy_port; " +
                "settings delete global global_http_proxy_exclusion_list",
            WeatherAdapterController.LEGACY_PROXY_CLEAR_COMMAND,
        )
    }
}
