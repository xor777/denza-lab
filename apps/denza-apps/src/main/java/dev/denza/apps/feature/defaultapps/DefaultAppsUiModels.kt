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
    /** True only after PersonBean returned this exact package in a successful read. */
    val providerConfirmed: Boolean = false,
    /**
     * The package the driver has just tapped, while its write is still in flight.
     *
     * The grid marks it immediately; the provider is still the only thing that decides
     * [selectedPackageName], so a rejected write puts the mark back where the car says it is.
     */
    val pendingPackageName: String? = null,
) {
    val configured: Boolean
        get() = providerConfirmed &&
            selectedPackageName != null &&
            selectedPackageName != role.stockPackageName

    val busy: Boolean
        get() = status == DefaultAppRoleStatus.LOADING || status == DefaultAppRoleStatus.APPLYING
}

data class DefaultAppsUiState(
    val roles: List<DefaultAppRoleUiState> = DefaultAppRole.entries.map(::DefaultAppRoleUiState),
    val refreshing: Boolean = false,
) {
    val configuredCount: Int
        get() = roles.count(DefaultAppRoleUiState::configured)

    /**
     * A read the driver is waiting on.
     *
     * Storing a choice is not one. The grid marks the tap before the write leaves, so a tile that
     * announced "Проверяем…" for the length of every write was reporting machinery, not waiting.
     */
    val reading: Boolean
        get() = refreshing || roles.any { it.status == DefaultAppRoleStatus.LOADING }

    val hasError: Boolean
        get() = roles.any { it.status == DefaultAppRoleStatus.ERROR }

    fun stateFor(role: DefaultAppRole): DefaultAppRoleUiState =
        roles.firstOrNull { it.role == role } ?: DefaultAppRoleUiState(role)

    fun update(role: DefaultAppRole, transform: (DefaultAppRoleUiState) -> DefaultAppRoleUiState):
        DefaultAppsUiState = copy(
        roles = roles.map { current -> if (current.role == role) transform(current) else current },
    )
}
