package dev.denza.apps.feature.weather

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class WeatherLocationLabel(
    val city: String,
    val region: String?,
    val countryName: String?,
    val countryCode: String?,
) {
    companion object {
        fun fromComponents(
            locality: String?,
            subAdminArea: String?,
            adminArea: String?,
            countryName: String?,
            countryCode: String?,
        ): WeatherLocationLabel? {
            val city = firstMeaningful(locality, subAdminArea, adminArea) ?: return null
            return WeatherLocationLabel(
                city = city,
                region = meaningful(adminArea),
                countryName = meaningful(countryName),
                countryCode = meaningful(countryCode)?.uppercase(Locale.ROOT),
            )
        }

        private fun firstMeaningful(vararg values: String?): String? =
            values.firstNotNullOfOrNull(::meaningful)

        private fun meaningful(value: String?): String? = value
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.take(MAX_COMPONENT_LENGTH)
            ?.takeIf(String::isNotEmpty)

        private val WHITESPACE = Regex("\\s+")
        private const val MAX_COMPONENT_LENGTH = 80
    }
}

/** Best-effort reverse geocoding through the Android 13 platform service. */
internal class AndroidWeatherGeocoder(context: Context) {
    private val appContext = context.applicationContext

    fun resolve(latitude: Double, longitude: Double): WeatherLocationLabel? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (!Geocoder.isPresent()) {
            Log.i(TAG, "Android Geocoder is unavailable")
            return null
        }

        val result = AtomicReference<List<Address>>(emptyList())
        val error = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        return try {
            Geocoder(appContext, Locale.forLanguageTag("ru-RU")).getFromLocation(
                latitude,
                longitude,
                MAX_RESULTS,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: List<Address>) {
                        result.set(addresses)
                        latch.countDown()
                    }

                    override fun onError(errorMessage: String?) {
                        error.set(errorMessage)
                        latch.countDown()
                    }
                },
            )
            if (!latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.i(TAG, "Android Geocoder timed out")
                null
            } else {
                error.get()?.let { message -> Log.i(TAG, "Android Geocoder failed: $message") }
                result.get().firstNotNullOfOrNull { address ->
                    address.toWeatherLocationLabel()
                }
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.i(TAG, "Android Geocoder was interrupted", failure)
            null
        } catch (failure: Exception) {
            Log.i(TAG, "Android Geocoder failed", failure)
            null
        }
    }

    private fun Address.toWeatherLocationLabel(): WeatherLocationLabel? =
        WeatherLocationLabel.fromComponents(
            locality = locality,
            subAdminArea = subAdminArea,
            adminArea = adminArea,
            countryName = countryName,
            countryCode = countryCode,
        )

    private companion object {
        const val TAG = "DenzaWeatherGeocoder"
        const val MAX_RESULTS = 3
        const val TIMEOUT_MILLIS = 5_000L
    }
}
