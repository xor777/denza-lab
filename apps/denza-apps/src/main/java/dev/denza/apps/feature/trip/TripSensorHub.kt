package dev.denza.apps.feature.trip

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import dev.denza.apps.feature.hud.HudGuidanceRuntime
import java.util.TimeZone

/**
 * Registers the standard Android IMU + GNSS providers and feeds one shared
 * [TripEngine]. Everything runs on the main looper so the engine is only ever
 * touched from one thread and the renderer can read it lock-free.
 *
 * The hub (and its engine) is process-scoped via [TripSession]: `stop()`
 * unregisters the sensors but deliberately KEEPS the engine, so reopening the
 * panel resumes the same trip instead of resetting it. The engine's own
 * movement gate decides when the trip clock actually starts.
 *
 * Only product-usable sources are wired here (see docs/vehicle-data-findings.md):
 * TYPE_ACCELEROMETER / TYPE_GYROSCOPE / TYPE_GRAVITY at ~30 Hz, the standard GNSS
 * provider at ~1 Hz, and the app's existing validated Yandex guidance via
 * [HudGuidanceRuntime]. No DiCar getters, no BYD events, no vendor SCP sensors.
 */
class TripSensorHub(context: Context) : SensorEventListener, LocationListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val handler = Handler(Looper.getMainLooper())

    /** The process-lifetime engine. Never reset; read on the main thread only. */
    val engine = TripEngine()

    /**
     * The panel's audio capture. Process-scoped like the engine, but unlike the
     * engine it holds a real system effect, so it is attached and released with
     * the panel's visibility exactly as the sensors are.
     */
    val spectrum = SpectrumSource()

    /** The active media session behind the panel's track strip and controls. */
    val nowPlaying = NowPlayingSource()

    var running: Boolean = false
        private set

    var locationGranted: Boolean = false
        private set

    /** Host (activity) context, held only while running, for the dialog fallback. */
    private var hostContext: Context? = null

    private val gravity = DoubleArray(3)
    private var haveGravity = false
    private val gyro = DoubleArray(3)
    private var lastGuidancePollMs = 0L
    private var runtimeFallbackRequested = false

    fun start(host: Context? = null) {
        if (running) return
        running = true
        hostContext = host
        haveGravity = false
        gyro[0] = 0.0; gyro[1] = 0.0; gyro[2] = 0.0
        registerSensor(Sensor.TYPE_GRAVITY)
        registerSensor(Sensor.TYPE_GYROSCOPE)
        registerSensor(Sensor.TYPE_ACCELEROMETER)
        ensureLocationAccess()
        spectrum.start(appContext, this)
        nowPlaying.start(appContext)
    }

    fun stop() {
        if (!running) return
        running = false
        hostContext = null
        sensorManager?.unregisterListener(this)
        runCatching { locationManager?.removeUpdates(this) }
        spectrum.stop(this)
        nowPlaying.stop()
        haveGravity = false
    }

    /** Drive time-based derivations (countdown, timers) each rendered frame. */
    fun tick() {
        engine.onTick(SystemClock.elapsedRealtime())
    }

    private fun registerSensor(type: Int) {
        val sensor = sensorManager?.getDefaultSensor(type) ?: return
        // ~30 Hz (33.333 ms). SENSOR_DELAY is a hint; the platform may run faster.
        sensorManager.registerListener(this, sensor, SAMPLING_PERIOD_US, handler)
    }

    /**
     * Start GNSS if already permitted, otherwise self-heal the permission over
     * ADB and, only if that channel fails, fall back to the runtime dialog. The
     * panel keeps running its IMU-only elements throughout; GNSS elements light up
     * once the grant lands (live for the ADB path, on the next start for a dialog
     * the user answers). See [TripLocationAccessCoordinator].
     */
    private fun ensureLocationAccess() {
        locationGranted = hasLocationPermission()
        if (locationGranted) {
            startLocationUpdates()
            return
        }
        TripLocationAccessCoordinator.ensureAccess(appContext) {
            // May run on a background thread; hop back to the main looper.
            handler.post {
                if (!running) return@post
                locationGranted = hasLocationPermission()
                if (locationGranted) startLocationUpdates() else requestRuntimeFallback()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        // The grant can be revoked between the asynchronous repair callback and
        // this system call. Re-check here; the annotation only teaches lint the
        // contract that the explicit guard enforces at runtime.
        if (!hasLocationPermission()) {
            locationGranted = false
            return
        }
        val lm = locationManager ?: return
        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_MIN_INTERVAL_MS,
                    0f,
                    this,
                    Looper.getMainLooper(),
                )
            }
        }
    }

    /** Last-resort runtime prompt, asked at most once per process. */
    private fun requestRuntimeFallback() {
        if (runtimeFallbackRequested) return
        val activity = hostContext?.findActivity() ?: return
        runtimeFallbackRequested = true
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(TripLocationAccessPolicy.FINE, TripLocationAccessPolicy.COARSE),
            LOCATION_REQUEST_CODE,
        )
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = event.values[0].toDouble()
                gravity[1] = event.values[1].toDouble()
                gravity[2] = event.values[2].toDouble()
                haveGravity = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro[0] = event.values[0].toDouble()
                gyro[1] = event.values[1].toDouble()
                gyro[2] = event.values[2].toDouble()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val now = SystemClock.elapsedRealtime()
                val ax = event.values[0].toDouble()
                val ay = event.values[1].toDouble()
                val az = event.values[2].toDouble()
                // Before the gravity sensor delivers its first value, a stationary
                // accelerometer reads gravity itself; use it as the seed so the
                // calibrator's low-pass has something sane to start from.
                val gx = if (haveGravity) gravity[0] else ax
                val gy = if (haveGravity) gravity[1] else ay
                val gz = if (haveGravity) gravity[2] else az
                engine.onImu(now, ax, ay, az, gx, gy, gz, gyro[0], gyro[1], gyro[2])
                pollGuidance(now)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val tzOffsetMinutes = TimeZone.getDefault().getOffset(wall) / 60_000
        engine.onLocation(
            nowElapsedMs = now,
            wallMs = wall,
            tzOffsetMinutes = tzOffsetMinutes,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            hasAltitude = location.hasAltitude(),
            verticalAccuracyMeters = if (location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters.toDouble()
            } else {
                -1.0
            },
            hasVerticalAccuracy = location.hasVerticalAccuracy(),
            bearing = location.bearing.toDouble(),
            hasBearing = location.hasBearing(),
            speed = location.speed.toDouble(),
            accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0,
        )
    }

    private fun pollGuidance(nowElapsedMs: Long) {
        if (nowElapsedMs - lastGuidancePollMs < GUIDANCE_POLL_MS) return
        lastGuidancePollMs = nowElapsedMs
        val remaining = HudGuidanceRuntime.remaining(SystemClock.uptimeMillis())
        engine.onGuidance(
            distanceMeters = remaining?.distanceMeters,
            timeSeconds = remaining?.timeSeconds,
            valid = remaining != null,
            nowElapsedMs = nowElapsedMs,
        )
    }

    private companion object {
        const val SAMPLING_PERIOD_US = 33_333 // ~30 Hz
        const val LOCATION_MIN_INTERVAL_MS = 1_000L
        const val GUIDANCE_POLL_MS = 300L
        const val LOCATION_REQUEST_CODE = 4207
    }
}
