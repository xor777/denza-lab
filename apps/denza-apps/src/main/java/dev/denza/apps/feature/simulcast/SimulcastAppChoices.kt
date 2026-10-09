package dev.denza.apps.feature.simulcast

import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.feature.defaultapps.InstalledDefaultApp

/**
 * What «Что транслировать» offers, and which of it is chosen.
 *
 * The list is the launcher catalog the default-app roles and «Что показывать» already keep -
 * cached, and dropped when a package is installed, removed or changed - narrowed to what the car's
 * launcher shows, without this app. It used to be a sweep of its own, icons and all, on every
 * recompute of the dashboard; the page asks for it when it opens, and nothing else draws it.
 */
internal object SimulcastAppChoices {

    /** The catalog's launcher applications by name, marked against [selected]. */
    fun of(
        installed: List<InstalledDefaultApp>,
        ownPackage: String,
        selected: Collection<String>,
    ): List<SimulcastAppChoice> = withSelection(
        installed
            .filter { it.launcher && it.packageName != ownPackage }
            .map { app ->
                SimulcastAppChoice(
                    packageName = app.packageName,
                    label = app.label,
                    selected = false,
                )
            }
            // By name alone. The chosen used to lead, and the list was rebuilt on every toggle, so
            // the tile the driver had just pressed left from under the finger and reappeared at
            // the top. The mark on the tile already says which are chosen; a fixed order is what
            // lets the eye find the same tile twice.
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SimulcastAppChoice::label)),
        selected,
    )

    /**
     * The same tiles with the marks moved: chosen or not, and whether pressing one would change
     * anything. Taking one off the list is always allowed; putting one on only while there is room
     * - the picker greys what a full selection cannot take rather than accepting the tap and
     * printing the rule afterwards.
     *
     * Only what [choices] lists counts as chosen: a package that has left the car since the marks
     * were made neither keeps a mark nor fills a place, so the tiles and the count agree.
     */
    fun withSelection(
        choices: List<SimulcastAppChoice>,
        selected: Collection<String>,
    ): List<SimulcastAppChoice> {
        val listed = choices.mapTo(HashSet(), SimulcastAppChoice::packageName)
        val chosen = selected.filterTo(HashSet()) { it in listed }
        val roomLeft = chosen.size < SimulcastApps.MAX_SELECTED
        return choices.map { choice ->
            val isSelected = choice.packageName in chosen
            val selectable = isSelected || roomLeft
            if (choice.selected == isSelected && choice.selectable == selectable) {
                choice
            } else {
                choice.copy(selected = isSelected, selectable = selectable)
            }
        }
    }

    /** What the tiles say is chosen. */
    fun selected(choices: List<SimulcastAppChoice>): List<String> =
        choices.filter(SimulcastAppChoice::selected).map(SimulcastAppChoice::packageName)
}
