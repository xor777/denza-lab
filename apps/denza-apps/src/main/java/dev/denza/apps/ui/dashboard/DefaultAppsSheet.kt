package dev.denza.apps.ui.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.defaultapps.DefaultAppChoice
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.defaultapps.DefaultAppRoleStatus
import dev.denza.apps.feature.defaultapps.DefaultAppRoleUiState
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import dev.denza.apps.ui.components.DenzaAppGrid
import dev.denza.apps.ui.components.DenzaAppTile
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSegmentedRow
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.components.DenzaStatusLine
import dev.denza.apps.ui.components.DenzaTileTone
import kotlinx.coroutines.delay

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
    // Nothing to point at yet. The panel waits and keeps asking the car rather than printing what
    // went wrong beside a button offering to ask again: "Список приложений недоступен." over
    // "Повторить" is a dead end with a task attached, and the task is one the panel can do itself.
    // The loop lives and dies with the panel, so a driver who closes it stops paying for it.
    val waitingForCar = roleState.choices.isEmpty()
    LaunchedEffect(waitingForCar) {
        if (!waitingForCar) return@LaunchedEffect
        while (true) {
            delay(RETRY_INTERVAL_MS)
            onRefresh()
        }
    }
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

        // "Команда" on its own named nothing: over a row reading Навигация / Музыка / Видео it
        // could as easily have been a heading for the applications underneath. What the row picks
        // is which of the car's spoken commands is being set up.
        DenzaSection("Для какой команды") {
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
                // One line, and it is a wait rather than a verdict. The three it replaces -
                // "Список приложений недоступен.", "Нет установленных приложений для выбора." -
                // told the driver the panel had given up while the car was still perfectly capable
                // of answering the next read.
                Text(
                    text = "Ищем установленные приложения…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DenzaColors.Muted,
                )
            } else {
                // The same grid the three whole-sheet pickers draw. This used to be a
                // [DenzaTileGrid] of its own with its own column counts, so the projection's
                // applications and these sat five and four to a row of the same panel.
                DenzaAppGrid(
                    items = roleState.choices,
                    key = DefaultAppChoice::packageName,
                    compact = compact,
                    columns = DenzaMetrics.Component.DEFAULT_APPS_PICKER_COLUMNS,
                ) { choice ->
                    DenzaAppTile(
                        label = choice.label,
                        selected = choice.selected,
                        onClick = {
                            if (canSelect && !choice.selected) {
                                onSelect(selectedRole, choice.packageName)
                            }
                        },
                        icon = choice.icon,
                        iconKey = choice.packageName,
                        enabled = !dimmed,
                    )
                }
            }
            // One button with one job: sweep the car again for applications installed since the
            // panel was opened. It used to become "Повторить" whenever a read had failed, which
            // made the same control a recovery step the driver was expected to work out - and the
            // panel now retries on its own, so there is nothing left for them to repeat.
            DenzaSecondaryButton(
                text = "Обновить список",
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
        roleState.message.ifBlank { "Не удалось прочитать настройку" }
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

/**
 * What this panel is for, to somebody sitting in the car.
 *
 * It used to be written to whoever found these roles: command identifiers, the word "медиасессия",
 * and the English names of the actions as they appear in a decompiled catalog. A driver has never
 * seen any of that; what they have seen is the command they say out loud and the application that
 * opens when they do.
 */
internal const val DEFAULT_APPS_SHORTCUTS_HELP =
    "Штатные сценарии открывают приложения по команде: «Открыть навигацию» и «Открыть видео» " +
        "запускают выбранные здесь. Музыку на этой прошивке начинает «Продолжить " +
        "воспроизведение», когда ничего не играет; «Открыть музыку» всегда открывает штатный " +
        "плеер."

/**
 * How often the panel asks the car again while it has nothing to show.
 *
 * Long enough that a provider taking its time is not asked twice over the same read, short enough
 * that the grid appears while the driver is still looking at the panel rather than after they have
 * closed it.
 */
private const val RETRY_INTERVAL_MS = 5_000L

private const val STATUS_LINES = 2
