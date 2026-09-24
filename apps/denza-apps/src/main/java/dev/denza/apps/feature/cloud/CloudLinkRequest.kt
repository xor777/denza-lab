package dev.denza.apps.feature.cloud

/** Persisted together before any car write; a new install has no cleanup obligation. */
internal data class CloudLinkRequest(
    val enabled: Boolean = false,
    val pendingDisable: Boolean = false,
    val awaitingTcpDown: Boolean = false,
) {
    val needsService: Boolean get() = enabled || pendingDisable

    fun request(on: Boolean) = copy(
        enabled = on,
        // Re-enabling must finish an interrupted teardown before trusting the old TCP=1.
        pendingDisable = pendingDisable || (!on && enabled),
    )

    fun disabled(car: CloudCarState): CloudLinkRequest {
        check(car.onStockProfile && (!awaitingTcpDown || car.cellular || car.connected == false)) {
            "Выключение ещё не подтверждено"
        }
        return copy(pendingDisable = false, awaitingTcpDown = false)
    }
}
