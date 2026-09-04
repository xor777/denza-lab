package dev.denza.apps.ui.dashboard

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.defaultapps.DefaultAppChoice
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.defaultapps.DefaultAppRoleStatus
import dev.denza.apps.feature.defaultapps.DefaultAppRoleUiState
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import dev.denza.apps.ui.components.DenzaAppChooser
import dev.denza.apps.ui.components.DenzaAppTile
import dev.denza.apps.ui.components.DenzaChoiceGroup
import dev.denza.apps.ui.components.DenzaChoiceIcon
import dev.denza.apps.ui.components.DenzaChoiceRow
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.components.DenzaSwitchRow
import kotlinx.coroutines.delay

/**
 * The three stock Shortcuts launch targets: what each command opens, and how to change it.
 *
 * The panel used to be one grid serving three roles, under a segmented row that swapped its items.
 * Two things were wrong with that and both were visible from the seat. The grid sat under a switch,
 * a row of segments, a status line and a heading, capped, inside a column that scrolled - so about
 * two rows of applications were visible and the driver scrolled a small window inside a panel with
 * room to spare. And because it was one grid whose items were swapped, moving from Навигация to
 * Музыка kept the offset: the next list opened in the middle of itself.
 *
 * So the panel answers rather than asks. Three rows say what each command opens, and pressing one
 * turns the panel into that role's chooser - a page, with the grid filling it and nothing nested.
 * See [DenzaAppChooser].
 *
 * There is no heading over the rows, and that is measured rather than tasteful: the panel has
 * 680 - 2 x 20 = 640 dp for content, and header 40 + 32 + switch 68 + 32 + three rows 225 + 32 +
 * note 110 + 32 + footer 62 comes to 633. A label and its gap is 30 more. The switch's own subtitle
 * already says these are the car's commands, which is what a heading would have said.
 */
@Composable
internal fun DefaultAppsSheet(
    state: DefaultAppsUiState,
    compact: Boolean,
    onRefresh: () -> Unit,
    onSelect: (DefaultAppRole, String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var openRole by remember { mutableStateOf<DefaultAppRole?>(null) }

    DenzaSheet(
        onDismiss = { if (openRole != null) openRole = null else onDismiss() },
        compact = compact,
        // The chooser page scrolls in its grid alone; the panel of rows has nothing to scroll.
        scrolls = openRole == null,
        footer = {
            if (openRole == null) {
                DenzaPrimaryButton(
                    text = "Готово",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                        .height(DenzaMetrics.Component.PRIMARY_HEIGHT),
                )
            }
        },
    ) {
        val role = openRole
        if (role != null) {
            DefaultAppsChooserPage(
                role = role,
                roleState = state.stateFor(role),
                compact = compact,
                onRefresh = onRefresh,
                onSelect = { packageName ->
                    onSelect(role, packageName)
                    openRole = null
                },
                onBack = { openRole = null },
                onDismiss = onDismiss,
            )
            return@DenzaSheet
        }

        DenzaSheetHeader(
            title = "Приложения по умолчанию",
            subtitle = "",
            onDismiss = onDismiss,
            icon = DenzaIcons.Applications,
        )

        // The same switch the tile is, where a driver can see it. The tile carries the gesture
        // and nothing on its face says so; this row is the only place the feature says out loud
        // that it can be handed back to the car.
        DenzaSwitchRow(
            title = "Заменять приложения",
            subtitle = defaultAppsSwitchSubtitle(state),
            checked = state.substituting,
            onCheckedChange = onSetEnabled,
            enabled = state.substituting || state.canSubstitute,
        )

        // One row a command, and the value line is what the car will do with it. The line is never
        // blank - see [defaultAppsStatusText] - because a row whose second line comes and goes
        // changes the height of the group under the driver's thumb every time a read finishes.
        DenzaChoiceGroup(DefaultAppRole.entries) { entry ->
            val roleState = state.stateFor(entry)
            DenzaChoiceRow(
                title = defaultAppRoleLabel(entry),
                value = defaultAppsStatusText(roleState),
                icons = listOfNotNull(
                    defaultAppsRowChoice(roleState)?.let { choice ->
                        DenzaChoiceIcon(
                            key = choice.packageName,
                            label = choice.label,
                            drawable = choice.icon,
                        )
                    },
                ),
                onClick = { openRole = entry },
            )
        }

        DenzaNote(DEFAULT_APPS_SHORTCUTS_HELP)
    }
}

/**
 * One role's applications, filling the panel.
 *
 * The car is asked when the page opens and keeps being asked while it has nothing to show, rather
 * than printing what went wrong beside a button offering to ask again: "Список приложений
 * недоступен." over "Повторить" is a dead end with a task attached, and the task is one the panel
 * can do itself. The loop lives and dies with the page, so a driver who goes back stops paying for
 * it - and it is the reason the panel has no "Обновить список" button any more.
 */
@Composable
private fun ColumnScope.DefaultAppsChooserPage(
    role: DefaultAppRole,
    roleState: DefaultAppRoleUiState,
    compact: Boolean,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(role) { onRefresh() }
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
    val canSelect = defaultAppsCanSelect(roleState)
    val dimmed = defaultAppsChoicesDimmed(roleState)

    DenzaAppChooser(
        title = defaultAppSectionTitle(role),
        subtitle = "",
        items = roleState.choices,
        key = DefaultAppChoice::packageName,
        compact = compact,
        onDismiss = onDismiss,
        onBack = onBack,
        // One line, and it is a wait rather than a verdict. The three it replaces - "Список
        // приложений недоступен.", "Нет установленных приложений для выбора." - told the driver
        // the panel had given up while the car was still perfectly capable of answering the next
        // read.
        emptyText = "Ищем установленные приложения…",
    ) { choice ->
        DenzaAppTile(
            label = choice.label,
            selected = choice.selected,
            onClick = { if (canSelect && !choice.selected) onSelect(choice.packageName) },
            icon = choice.icon,
            iconKey = choice.packageName,
            enabled = !dimmed,
        )
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

/**
 * The application whose icon a role's row carries, or nothing at all.
 *
 * A write in flight wins, the same way the mark in the grid moves when the finger does: the row
 * shows where the role is going, and the provider puts both back if it refuses. Nothing to show is
 * an ordinary state - the catalog has not been read yet, or the role points nowhere - and the row
 * simply carries its line of words in that case rather than a placeholder standing in for an icon.
 */
internal fun defaultAppsRowChoice(roleState: DefaultAppRoleUiState): DefaultAppChoice? {
    val packageName = roleState.effectivePackageName ?: return null
    return roleState.choices.firstOrNull { it.packageName == packageName }
}

/** Never blank: see the note at the call site. Reports the wait, the failure, or the choice. */
internal fun defaultAppsStatusText(
    roleState: DefaultAppRoleUiState,
): String = when {
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

/**
 * What the switch is doing to the car, under its own title.
 *
 * "Штатные" rather than "Выключено": off is not an absence, it is the car running the applications
 * it came with, and the rows below still hold the choices the switch would put back.
 */
internal fun defaultAppsSwitchSubtitle(state: DefaultAppsUiState): String = when {
    state.reading -> "Читаем настройку…"
    state.substituting -> "Команды открывают выбранные приложения"
    state.canSubstitute -> "Команды открывают штатные приложения"
    else -> "Нечем заменять: подходящие приложения не установлены"
}

internal fun defaultAppsCanSelect(roleState: DefaultAppRoleUiState): Boolean =
    !roleState.busy && roleState.choices.isNotEmpty()

/**
 * Whether the grid is shown as not yet answering for the car.
 *
 * Only a read earns that. Storing a choice used to grey out all twenty-odd tiles for the length of
 * the write, which on a list whose selection had already moved read as the screen going away and
 * coming back.
 */
internal fun defaultAppsChoicesDimmed(roleState: DefaultAppRoleUiState): Boolean =
    roleState.status == DefaultAppRoleStatus.LOADING

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
 * How often the page asks the car again while it has nothing to show.
 *
 * Long enough that a provider taking its time is not asked twice over the same read, short enough
 * that the grid appears while the driver is still looking at the page rather than after they have
 * closed it.
 */
private const val RETRY_INTERVAL_MS = 5_000L
