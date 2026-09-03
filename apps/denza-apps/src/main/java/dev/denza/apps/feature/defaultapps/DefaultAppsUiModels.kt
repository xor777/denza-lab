package dev.denza.apps.feature.defaultapps

import android.graphics.drawable.Drawable

data class DefaultAppChoice(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val selected: Boolean,
    val known: Boolean,
    val stock: Boolean,
)

enum class DefaultAppRoleStatus {
    LOADING,
    READY,
    APPLYING,
    ERROR,
}

data class DefaultAppRoleUiState(
    val role: DefaultAppRole,
    val selectedPackageName: String? = null,
    val selectedLabel: String = "Не выбрано",
    val choices: List<DefaultAppChoice> = emptyList(),
    val status: DefaultAppRoleStatus = DefaultAppRoleStatus.LOADING,
    val message: String = "",
    /**
     * True only after PersonBean returned the exact provider representation of this selection.
     * That readback may have happened in an earlier process; every process start re-reads it.
     */
    val providerConfirmed: Boolean = false,
    /**
     * The package the driver has just tapped, while its write is still in flight.
     *
     * The grid marks it immediately; the provider is still the only thing that decides
     * [selectedPackageName], so a rejected write puts the mark back where the car says it is.
     */
    val pendingPackageName: String? = null,
) {
    /** What the car will be using for this role once the write in flight has landed. */
    val effectivePackageName: String?
        get() = pendingPackageName ?: selectedPackageName

    /**
     * Whether this role currently points somewhere other than the car's own application.
     *
     * A write in flight counts as its target. The grid and the tile already show it that way -
     * the mark moved when the finger did - and the provider puts both back if it refuses.
     */
    val configured: Boolean
        get() = providerConfirmed &&
            effectivePackageName != null &&
            effectivePackageName != role.stockPackageName

    val busy: Boolean
        get() = status == DefaultAppRoleStatus.LOADING || status == DefaultAppRoleStatus.APPLYING

    /**
     * Whether the switch has something to put in this role by itself.
     *
     * A remembered pick would also serve, but it lives in settings rather than in this state, and
     * it can only differ from this on a car where the driver chose an application outside the
     * catalog *and* none of the catalog's own is installed.
     */
    val switchable: Boolean
        get() = choices.any { it.known && !it.stock }
}

data class DefaultAppsUiState(
    val roles: List<DefaultAppRoleUiState> = DefaultAppRole.entries.map(::DefaultAppRoleUiState),
    val refreshing: Boolean = false,
) {
    val configuredCount: Int
        get() = roles.count(DefaultAppRoleUiState::configured)

    /**
     * A first read where the driver has nothing to look at yet.
     *
     * Storing a choice is not one. The grid marks the tap before the write leaves, so a tile that
     * announced "Проверяем…" for the length of every write was reporting machinery, not waiting.
     */
    val reading: Boolean
        get() = roles.any { it.status == DefaultAppRoleStatus.LOADING }

    /**
     * Whether the car is running any application of the driver's rather than its own.
     *
     * This is the tile's switch, and it is read off the car rather than off a flag of our own: the
     * roles either point at the stock applications or they do not, and a stored "enabled" beside
     * that could only ever disagree with it.
     */
    val substituting: Boolean
        get() = configuredCount > 0

    /** Whether the switch could be turned on at all: something has to be there to switch to. */
    val canSubstitute: Boolean
        get() = roles.any(DefaultAppRoleUiState::switchable)

    val hasError: Boolean
        get() = roles.any { it.status == DefaultAppRoleStatus.ERROR }

    fun stateFor(role: DefaultAppRole): DefaultAppRoleUiState =
        roles.firstOrNull { it.role == role } ?: DefaultAppRoleUiState(role)

    fun update(role: DefaultAppRole, transform: (DefaultAppRoleUiState) -> DefaultAppRoleUiState):
        DefaultAppsUiState = copy(
        roles = roles.map { current -> if (current.role == role) transform(current) else current },
    )
}
