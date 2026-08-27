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

    val busy: Boolean
        get() = refreshing || roles.any(DefaultAppRoleUiState::busy)

    val hasError: Boolean
        get() = roles.any { it.status == DefaultAppRoleStatus.ERROR }

    fun stateFor(role: DefaultAppRole): DefaultAppRoleUiState =
        roles.firstOrNull { it.role == role } ?: DefaultAppRoleUiState(role)

    fun update(role: DefaultAppRole, transform: (DefaultAppRoleUiState) -> DefaultAppRoleUiState):
        DefaultAppsUiState = copy(
        roles = roles.map { current -> if (current.role == role) transform(current) else current },
    )
}
