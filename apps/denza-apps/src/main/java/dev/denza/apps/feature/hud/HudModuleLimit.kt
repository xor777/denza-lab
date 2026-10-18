package dev.denza.apps.feature.hud

/**
 * The road's limit the YandexHUD module publishes through this app's host (field 11, km/h), kept
 * for the car's own sign, which only this app can write. It lives as long as the module's fields:
 * a module that stops publishing takes its limit with it. Main thread.
 */
class HudModuleLimit(private val now: () -> Long) {
    private var kmh: Int? = null
    private var until = 0L

    /** The module published its limit, or none ([kmh] null), for [ttlMs]. */
    fun published(kmh: Int?, ttlMs: Long) {
        this.kmh = kmh
        until = now() + ttlMs
    }

    fun withdrawn() {
        kmh = null
        until = 0L
    }

    /** The module's limit while it lives, else null. */
    fun current(): Int? = kmh?.takeIf { now() < until }
}
