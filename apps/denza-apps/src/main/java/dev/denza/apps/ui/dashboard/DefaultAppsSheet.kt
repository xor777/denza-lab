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
    val dimmed = defaultAppsChoicesDimmed(state, roleState)

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
                enabled = !state.refreshing,
            )
        }

        // One line, and it always has something to say. It used to fall silent whenever nothing
        // was happening, and [DenzaStatusLine] draws nothing at all for a blank string - so every
        // completed read and every stored choice removed a line of text and its gap from the
        // middle of the panel, and the grid, the selection and the button under it jumped up to
        // fill the space. What it says when the car is idle is what the choice actually does.
        //
        // Two lines is the ceiling. A provider failure carries up to 300 characters of the car's
        // own words, which is four or five lines of this panel, and nothing below a status that
        // can reflow by four lines has a settled place to be.
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
            maxLines = STATUS_LINES,
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
                            if (canSelect && !choice.selected) {
                                onSelect(selectedRole, choice.packageName)
                            }
                        },
                        modifier = cell,
                        icon = choice.icon,
                        iconKey = choice.packageName,
                        enabled = !dimmed,
                    )
                }
            }
            DenzaSecondaryButton(
                text = if (state.hasError) "Повторить" else "Обновить список",
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.refreshing,
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

/** Never blank: see the note at the call site. Reports the wait, the failure, or the choice. */
internal fun defaultAppsStatusText(
    state: DefaultAppsUiState,
    roleState: DefaultAppRoleUiState,
): String = when {
    state.refreshing -> "Обновляем установленные приложения…"
    roleState.status == DefaultAppRoleStatus.LOADING ->
        roleState.message.ifBlank { "Загружаем приложения…" }
    roleState.status == DefaultAppRoleStatus.APPLYING -> "Сохраняем выбор…"
    roleState.status == DefaultAppRoleStatus.ERROR ->
        roleState.message.ifBlank { "Не удалось прочитать настройку." }
    // A cold start may resolve a role without the driver touching anything, and that is worth
    // saying once - beside the outcome, not instead of it.
    roleState.message.isNotBlank() ->
        "${roleState.message} · ${defaultAppsOutcomeText(roleState)}"
    else -> defaultAppsOutcomeText(roleState)
}

/**
 * What the car will do with this role, in the words the board's result block uses.
 *
 * This is where the confirmation nuance the panel used to spell out in a "Сейчас" row now lives:
 * a package the provider has not echoed back is not presented as the one the car would open.
 */
internal fun defaultAppsOutcomeText(roleState: DefaultAppRoleUiState): String = when {
    !roleState.providerConfirmed && roleState.selectedPackageName != null ->
        "Последнее известное: ${roleState.selectedLabel}"
    !roleState.providerConfirmed || roleState.selectedPackageName == null -> "Не подтверждено"
    roleState.role == DefaultAppRole.MUSIC -> "Запустит ${roleState.selectedLabel}"
    else -> "Откроет ${roleState.selectedLabel}"
}

internal fun defaultAppsCanSelect(
    state: DefaultAppsUiState,
    roleState: DefaultAppRoleUiState,
): Boolean = !state.refreshing && !roleState.busy && roleState.choices.isNotEmpty()

/**
 * Whether the grid is shown as not yet answering for the car.
 *
 * Only a read earns that. Storing a choice used to grey out all twenty-odd tiles for the length of
 * the write, which on a list whose selection had already moved read as the screen going away and
 * coming back.
 */
internal fun defaultAppsChoicesDimmed(
    state: DefaultAppsUiState,
    roleState: DefaultAppRoleUiState,
): Boolean = state.refreshing || roleState.status == DefaultAppRoleStatus.LOADING

internal const val DEFAULT_APPS_SHORTCUTS_HELP =
    "Shortcuts: Open Navigation открывает навигацию, Open Video — видео. " +
        "На этой прошивке музыку запускает Continue playing, если нет активной медиасессии. " +
        "Open Music открывает штатную музыку; остальные команды управляют текущей медиасессией."

private const val WIDE_COLUMNS = 4
private const val NARROW_COLUMNS = 3
private const val STATUS_LINES = 2
