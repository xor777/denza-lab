package dev.denza.apps.feature.diagnostics.scan

/**
 * The one rule by which a hub value becomes a point on a trace - the engine's fast rows ([LiveStream]), a
 * system's table ([SystemLive]) and a hot signal's page ([ParamTrace]) all sample through it (architecture,
 * 6.1, 6.2):
 *
 * - a new sweep: each key takes its value, a gap where the sweep has none;
 * - the same sweep: a key whose last point is a value and whose value the feed no longer holds - the car
 *   lost, or the value past its horizon, which ends without a new sweep - takes one gap, and nothing more
 *   until a sweep brings it again. A key that holds its value takes nothing.
 *
 * [zeroIsSweep]: whether sweep 0 - none counted yet - is a sweep to take ([SystemLive], whose one-off view of
 * the readings as they stand takes them whatever their count) or only a moment to end held values in.
 */
internal class HubSampler<K>(private val zeroIsSweep: Boolean) {
    private var lastSweep: Long? = null
    /** The keys whose last point is a value. */
    private val held = HashSet<K>()

    /** The next [take] samples what is there now, not only what is new. The keys' last points stand. */
    fun restart() {
        lastSweep = null
    }

    /**
     * What [car] brings to [keys]: [put] with the key's [value], null a gap. Returns whether [car] was a new
     * sweep.
     */
    fun take(car: CarReadings, keys: Iterable<K>, value: (K) -> Double?, put: (K, Double?) -> Unit): Boolean {
        val fresh = car.sweep != lastSweep && (zeroIsSweep || car.sweep != 0L)
        if (fresh) lastSweep = car.sweep
        keys.forEach { k ->
            val v = value(k)
            if (!fresh && (k !in held || v != null)) return@forEach
            if (v != null) held += k else held -= k
            put(k, v)
        }
        return fresh
    }
}
