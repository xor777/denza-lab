package dev.denza.apps.ui.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.defaultapps.DefaultAppRoleStatus
import dev.denza.apps.feature.defaultapps.DefaultAppRoleUiState
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import dev.denza.apps.ui.components.DenzaAppTile
import dev.denza.apps.ui.components.DenzaKeyValueRow
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSegmentedRow
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.components.DenzaStatusLine
import dev.denza.apps.ui.components.DenzaTileGrid
import dev.denza.apps.ui.components.DenzaTileTone

/** The three stock Shortcuts launch targets, on the sheet approved in DefaultApps.dc.html. */
@Composable
internal fun DefaultAppsSheet(
    state: DefaultAppsUiState,
    compact: Boolean,
    onRefresh: () -> Unit,
    onSelect: (DefaultAppRole, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedRole by remember { mutableStateOf(DefaultAppRole.NAVIGATION) }
    val roleState = state.stateFor(selectedRole)
    // A failed readback may leave the last known installed choices intact. Keep those actionable:
    // selecting another package is how the driver can recover from a stale or rejected target.
    val canSelect = defaultAppsCanSelect(state, roleState)

    DenzaSheet(
        onDismiss = onDismiss,
        compact = compact,
        footer = {
            DenzaPrimaryButton(
                text = "Готово",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
                    .height(DenzaMetrics.Component.PRIMARY_HEIGHT),
            )
        },
    ) {
        DenzaSheetHeader(
            title = "Приложения по умолчанию",
            subtitle = "",
            onDismiss = onDismiss,
            icon = DenzaIcons.Applications,
        )

        DenzaSection("Команда") {
            DenzaSegmentedRow(
                labels = DefaultAppRole.entries.map(::defaultAppRoleLabel),
                selectedIndex = DefaultAppRole.entries.indexOf(selectedRole),
                onSelect = { selectedRole = DefaultAppRole.entries[it] },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
        }

        DenzaStatusLine(
            text = defaultAppsStatusText(state, roleState),
            tone = if (state.refreshing) {
                DenzaTileTone.WORKING
            } else when (roleState.status) {
                DefaultAppRoleStatus.ERROR -> DenzaTileTone.BROKEN
                DefaultAppRoleStatus.LOADING, DefaultAppRoleStatus.APPLYING ->
                    DenzaTileTone.WORKING
                DefaultAppRoleStatus.READY -> DenzaTileTone.IDLE
            },
        )

        DenzaSection(defaultAppSectionTitle(selectedRole)) {
            if (roleState.choices.isEmpty()) {
                Text(
                    text = when {
                        roleState.status == DefaultAppRoleStatus.ERROR ->
                            "Список приложений недоступен."
                        roleState.busy || state.refreshing ->
                            "Ищем установленные приложения…"
                        else -> "Нет установленных приложений для выбора."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = DenzaColors.Muted,
                )
            } else {
                DenzaTileGrid(
                    columns = if (compact) NARROW_COLUMNS else WIDE_COLUMNS,
                    itemCount = roleState.choices.size,
                    modifier = Modifier.fillMaxWidth(),
                ) { index, cell ->
                    val choice = roleState.choices[index]
                    DenzaAppTile(
                        label = choice.label,
                        selected = choice.selected,
                        onClick = {
                            if (!choice.selected) onSelect(selectedRole, choice.packageName)
                        },
                        modifier = cell,
                        icon = choice.icon,
                        iconKey = choice.packageName,
                        enabled = canSelect,
                    )
                }
            }
            DenzaKeyValueRow(
                label = defaultAppsSelectionLabel(roleState),
                value = defaultAppsSelectionValue(roleState),
            )
            DenzaSecondaryButton(
                text = if (state.hasError) "Повторить" else "Обновить список",
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
        }

        DenzaNote(DEFAULT_APPS_SHORTCUTS_HELP)
    }
}

internal fun defaultAppRoleLabel(role: DefaultAppRole): String = when (role) {
    DefaultAppRole.NAVIGATION -> "Навигация"
    DefaultAppRole.MUSIC -> "Музыка"
    DefaultAppRole.VIDEO -> "Видео"
}

internal fun defaultAppSectionTitle(role: DefaultAppRole): String = when (role) {
    DefaultAppRole.NAVIGATION -> "Приложение для навигации"
    DefaultAppRole.MUSIC -> "Приложение для музыки"
    DefaultAppRole.VIDEO -> "Приложение для видео"
}

private fun defaultAppsStatusText(
    state: DefaultAppsUiState,
    roleState: DefaultAppRoleUiState,
): String = when {
    state.refreshing -> "Обновляем установленные приложения…"
    roleState.message.isNotBlank() -> roleState.message
    roleState.status == DefaultAppRoleStatus.LOADING -> "Загружаем приложения…"
    roleState.status == DefaultAppRoleStatus.APPLYING -> "Сохраняем выбор…"
    roleState.status == DefaultAppRoleStatus.ERROR -> "Не удалось прочитать настройку."
    else -> ""
}

internal fun defaultAppsCanSelect(
    state: DefaultAppsUiState,
    roleState: DefaultAppRoleUiState,
): Boolean = !state.refreshing && !roleState.busy && roleState.choices.isNotEmpty()

internal fun defaultAppsSelectionLabel(roleState: DefaultAppRoleUiState): String = when {
    roleState.providerConfirmed -> "Сейчас"
    roleState.selectedPackageName != null -> "Последнее известное"
    else -> "Статус"
}

internal fun defaultAppsSelectionValue(roleState: DefaultAppRoleUiState): String = when {
    roleState.providerConfirmed || roleState.selectedPackageName != null -> roleState.selectedLabel
    else -> "Не подтверждено"
}

internal const val DEFAULT_APPS_SHORTCUTS_HELP =
    "Shortcuts: Open Navigation открывает навигацию, Open Video — видео. " +
        "На этой прошивке музыку запускает Continue playing, если нет активной медиасессии. " +
        "Open Music открывает штатную музыку; остальные команды управляют текущей медиасессией."

private const val WIDE_COLUMNS = 4
private const val NARROW_COLUMNS = 3
