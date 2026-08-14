package dev.denza.apps.feature.hud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.denza.apps.feature.trip.TripLocationAccessCoordinator

/** Standard Android GNSS source active only while HUD guidance is enabled. */
class HudArLocationSource(
    context: Context,
    private val listener: Listener,
) : LocationListener {
    fun interface Listener {
        fun onPose(pose: HudVehiclePose)
    }

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val filter = HudArPoseFilter()
    private var running = false
    private var registered = false
    private var firstPoseLogged = false

    fun start() {
        if (running) return
        running = true
        if (TripLocationAccessCoordinator.isGranted(appContext)) {
            register()
            return
        }
        TripLocationAccessCoordinator.ensureAccess(appContext) { granted ->
            handler.post {
                if (running && granted) {
                    register()
                } else if (running) {
                    Log.w(TAG, "GNSS permission unavailable; keeping compact HUD guidance")
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            runCatching { locationManager?.removeUpdates(this) }
        }
        registered = false
        firstPoseLogged = false
        filter.reset()
    }

    private fun register() {
        if (!running || registered) return
        val locationGranted =
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!locationGranted) return
        val manager = locationManager ?: return
        val result = runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_MIN_INTERVAL_MS,
                0f,
                this,
                Looper.getMainLooper(),
            )
            true
        }
        registered = result.getOrDefault(false)
        if (registered) {
            Log.i(TAG, "GNSS updates registered")
        } else {
            Log.w(TAG, "GNSS registration failed; keeping compact HUD guidance", result.exceptionOrNull())
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!running) return
        val pose = filter.onFix(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = location.altitude,
            hasAltitude = location.hasAltitude(),
            speedMetersPerSecond = location.speed.toDouble(),
            accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0,
            bearingDegrees = location.bearing.toDouble(),
            hasBearing = location.hasBearing(),
            bearingAccuracyDegrees = if (location.hasBearingAccuracy()) {
                location.bearingAccuracyDegrees.toDouble()
            } else {
                null
            },
            capturedAtElapsedMs = location.elapsedRealtimeNanos / 1_000_000L,
        ) ?: return
        if (!firstPoseLogged) {
            firstPoseLogged = true
            Log.i(
                TAG,
                "first qualified GNSS pose: accuracy=${pose.accuracyMeters}m " +
                    "speed=${pose.speedMetersPerSecond}m/s heading=${pose.headingDegrees}",
            )
        }
        listener.onPose(pose)
    }

    private companion object {
        const val LOCATION_MIN_INTERVAL_MS = 200L
        const val TAG = "DenzaHudAr"
    }
}
