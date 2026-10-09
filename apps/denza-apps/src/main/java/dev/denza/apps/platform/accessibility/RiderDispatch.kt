package dev.denza.apps.platform.accessibility

/**
 * What the shared accessibility service does with everything it is given: hands it to its riders,
 * in the one order they were registered in, and nothing else.
 *
 * - Connect, every event and disconnect go to the riders in that order; an event only to a rider
 *   whose [Rider.eventTypes] has its type and whose [Rider.eventPackages], if any, has its package.
 * - A key goes to the riders that take keys, in the same order, and the first that consumes it
 *   ends the round: no later rider sees it, and the service returns true. A rider that consumed a
 *   key's DOWN owns its repeats and UP by itself (the interceptors keep that state), and since it
 *   is asked before the later riders it is the one that answers them - which holds because no two
 *   key riders take the same code (`WheelKeyRoutingTest`).
 * - A rider that throws is reported to [failed] and passed over: the others still hear the call,
 *   and a key it threw on counts as not consumed by it.
 */
class RiderDispatch<S, E>(
    riders: List<Rider<S, E>>,
    private val failed: RiderFailure,
) {
    /** The riders in their order. */
    val riders: List<Rider<S, E>> = riders.toList()

    private val keyRiders = this.riders.filter { it.takesKeys }

    fun connected(service: S) {
        for (rider in riders) guard(rider, "connect") { rider.onConnected(service) }
    }

    fun event(event: E, type: Int, packageName: CharSequence?) {
        val pkg = packageName?.toString()
        for (rider in riders) {
            if (wants(rider, type, pkg)) guard(rider, "event") { rider.onEvent(event) }
        }
    }

    fun key(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        for (rider in keyRiders) {
            val consumed = try {
                rider.onKeyEvent(keyCode, action, repeatCount)
            } catch (error: Exception) {
                failed.failed(rider.name, "key", error)
                false
            }
            if (consumed) return true
        }
        return false
    }

    fun disconnected(service: S) {
        for (rider in riders) guard(rider, "disconnect") { rider.onDisconnected(service) }
    }

    /** The rider of [type], to reach it from outside the service ([AccessibilityHost.post]). */
    fun <R : Any> rider(type: Class<R>): R? = riders.firstOrNull(type::isInstance)?.let(type::cast)

    private inline fun guard(rider: Rider<S, E>, call: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            failed.failed(rider.name, call, error)
        }
    }

    companion object {
        /** Whether [rider] is handed an event of [type] from [packageName]. */
        fun wants(rider: Rider<*, *>, type: Int, packageName: String?): Boolean {
            if ((rider.eventTypes and type) == 0) return false
            val packages = rider.eventPackages ?: return true
            return packageName != null && packageName in packages
        }
    }
}
