package dev.denza.apps.feature.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class WeatherCoordinates(
    val latitude: Double,
    val longitude: Double,
    val source: String,
)

/** Uses standard Android location first and the last stock-weather coordinates as a fallback. */
internal class WeatherLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val nativeStore = NativeWeatherStore(appContext)

    fun resolve(): WeatherCoordinates {
        if (hasLocationPermission()) {
            val lastKnown = lastKnownLocations().maxByOrNull(Location::getTime)
            if (lastKnown != null && isFresh(lastKnown)) {
                return lastKnown.toCoordinates("${lastKnown.provider} last-known")
            }

            currentLocation()?.let { current ->
                return current.toCoordinates("${current.provider} current")
            }
            lastKnown?.let { stale ->
                return stale.toCoordinates("${stale.provider} stale")
            }
        }

        return coordinatesFromNativeProvider()
            ?: error("No Android or native-weather location is available")
    }

    private fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun lastKnownLocations(): List<Location> {
        val manager = locationManager ?: return emptyList()
        return runCatching { manager.getProviders(true) }
            .getOrDefault(emptyList())
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }
                    .getOrNull()
                    ?.takeIf(::isUsable)
            }
    }

    private fun currentLocation(): Location? {
        val manager = locationManager ?: return null
        val providers = runCatching { manager.getProviders(true) }
            .getOrDefault(emptyList())
            .filterNot { it == LocationManager.PASSIVE_PROVIDER }
        if (providers.isEmpty()) return null

        val fixes = Collections.synchronizedList(mutableListOf<Location>())
        val cancellations = mutableListOf<CancellationSignal>()
        val latch = CountDownLatch(providers.size)
        providers.forEach { provider ->
            val cancellation = CancellationSignal()
            cancellations += cancellation
            runCatching {
                manager.getCurrentLocation(
                    provider,
                    cancellation,
                    { runnable -> runnable.run() },
                ) { location ->
                    if (location != null && isUsable(location)) fixes += location
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        try {
            latch.await(CURRENT_LOCATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } finally {
            cancellations.forEach(CancellationSignal::cancel)
        }
        return fixes.maxByOrNull(Location::getTime)
    }

    private fun coordinatesFromNativeProvider(): WeatherCoordinates? = runCatching {
        val root = JSONObject(nativeStore.readPayload() ?: return null)
        val city = root.optJSONObject("data")?.optJSONObject("city") ?: return null
        WeatherCoordinates(
            latitude = city.getString("ca").toDouble(),
            longitude = city.getString("co").toDouble(),
            source = "native provider fallback",
        ).takeIf { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
    }.getOrNull()

    private fun isFresh(location: Location): Boolean {
        val age = System.currentTimeMillis() - location.time
        return age in 0..FRESH_LOCATION_MILLIS
    }

    private fun isUsable(location: Location): Boolean =
        location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0 &&
            !(location.latitude == 0.0 && location.longitude == 0.0)

    private fun Location.toCoordinates(source: String) = WeatherCoordinates(
        latitude = latitude,
        longitude = longitude,
        source = source,
    )

    private companion object {
        const val FRESH_LOCATION_MILLIS = 20L * 60L * 1_000L
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 7_000L
    }
}
