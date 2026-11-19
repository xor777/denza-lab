package dev.denza.apps.feature.cloud

/**
 * What the adapter owes the car's profile: the one the car was on before the adapter wrote its own.
 *
 * Persisted with the request and committed before the write it covers, so a process that dies
 * between the two still knows the write was ours (docs/telematics/cloud-tile.md, "Three ways to
 * break a working car").
 */
internal sealed interface CloudProfileMemory {
    /** What the report and the history print. */
    val label: String

    /**
     * Nothing remembered: a request saved by 0.7.1 or by an earlier build of this release. The
     * build's profile stands in, exactly as it always did.
     */
    data object Legacy : CloudProfileMemory {
        override val label = "legacy"
    }

    /** The adapter has not written its profile since the driver switched the link on. */
    data object Clear : CloudProfileMemory {
        override val label = "clear"
    }

    /** The adapter wrote its profile over [profile], one of the two the broadcast knows. */
    data class Owed(val profile: String) : CloudProfileMemory {
        override val label: String get() = profile
    }

    /**
     * The profile to give back to [car], or null when nothing is to be written: nothing owed, the
     * car already on it, or the owed profile the adapter's own.
     */
    fun restoreTo(car: CloudCarState): String? = when (this) {
        Legacy -> car.stockProfile.takeIf { !car.onStockProfile }
        Clear -> null
        is Owed -> profile.takeIf { it != CloudLinkProtocol.WIFI_PROFILE && !car.on(it) }
    }

    /** What is remembered once the adapter is about to write its profile over [car]'s. */
    fun beforeWrite(car: CloudCarState): CloudProfileMemory =
        if (this is Owed) this else car.profile?.let(::Owed) ?: this

    companion object {
        /** Persisted form: absent is [Legacy], empty is [Clear], a profile is [Owed]. */
        fun stored(memory: CloudProfileMemory): String? = when (memory) {
            Legacy -> null
            Clear -> ""
            is Owed -> memory.profile
        }

        fun read(stored: String?): CloudProfileMemory = when {
            stored == null -> Legacy
            stored.isEmpty() -> Clear
            stored == CloudLinkProtocol.WIFI_PROFILE || stored == CloudLinkProtocol.STOCK_PROFILE -> Owed(stored)
            // Never write a profile the code does not whitelist: an unknown value owes nothing.
            else -> Clear
        }
    }
}

/** Persisted together before any car write; a new install has no cleanup obligation. */
internal data class CloudLinkRequest(
    val enabled: Boolean = false,
    val pendingDisable: Boolean = false,
    val awaitingTcpDown: Boolean = false,
    val remembered: CloudProfileMemory = CloudProfileMemory.Legacy,
) {
    val needsService: Boolean get() = enabled || pendingDisable

    fun request(on: Boolean) = copy(
        enabled = on,
        // Re-enabling must finish an interrupted teardown before trusting the old TCP=1.
        pendingDisable = pendingDisable || (!on && enabled),
        // A fresh on starts with nothing owed; one over an unfinished off keeps what that off still
        // has to give back, and a repeated on keeps what it has - a 0.7.1 request stays Legacy.
        remembered = if (on && !enabled && !pendingDisable) CloudProfileMemory.Clear else remembered,
    )

    fun disabled(car: CloudCarState): CloudLinkRequest {
        check(remembered.restoreTo(car) == null && (!awaitingTcpDown || car.cellular || car.connected == false)) {
            "Выключение ещё не подтверждено"
        }
        return copy(pendingDisable = false, awaitingTcpDown = false, remembered = CloudProfileMemory.Clear)
    }

    override fun toString(): String =
        "CloudLinkRequest(enabled=$enabled, pendingDisable=$pendingDisable, awaitingTcpDown=$awaitingTcpDown, " +
            "remembered=${remembered.label})"
}
