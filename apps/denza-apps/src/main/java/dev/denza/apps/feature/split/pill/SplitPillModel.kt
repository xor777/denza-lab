package dev.denza.apps.feature.split.pill

import dev.denza.apps.feature.split.SceneVisibility
import dev.denza.apps.feature.split.SplitLiveScene
import dev.denza.apps.feature.split.SplitPair
import dev.denza.apps.feature.split.SplitPane
import dev.denza.apps.feature.split.SplitScene
import dev.denza.apps.feature.split.SplitSlot
import dev.denza.apps.feature.split.SplitState

/** What a pin badge shows: the star outlined (not pinned) or solid (pinned). */
internal enum class PillBadge { OUTLINE, SOLID }

/**
 * One item of a row, as the pill draws it: nothing but what the user sees and what a tap on it
 * asks for. [key] is what a row change keeps an item by - a chip by its pair, so the chip of a
 * pair slides, opens up or closes in place rather than leaving and coming back.
 */
internal sealed interface PillItem {
    val key: String

    /**
     * A pair chip. [left] and [right] are the apps on the screen's left and right ([leftPane]
     * decides which pane that is); [missing] names the side whose app is a contour (an incomplete
     * pair, the repair row), and such a chip is opened; [live] marks the pair on screen, and only
     * its chip carries [badge].
     */
    data class Chip(
        val pair: SplitPair,
        val left: String,
        val right: String,
        val missing: Side?,
        val live: Boolean,
        val badge: PillBadge?,
    ) : PillItem {
        override val key: String = "pair:${pair.primary}|${pair.secondary}"
    }

    /** One app for one pane: the apps row and the repair row. */
    data class Single(val pane: SplitPane, val packageName: String) : PillItem {
        override val key: String = "app:${pane.name}:$packageName"
    }

    data object Separator : PillItem {
        override val key: String get() = "sep"
    }

    data object Plus : PillItem {
        override val key: String get() = "plus"
    }

    /** The divider's mark between the halves of the apps row. */
    data object DividerMark : PillItem {
        override val key: String get() = "divider"
    }
}

internal enum class Side { LEFT, RIGHT }

/**
 * Everything the pill shows, derived and immutable (contract «К 1.15»): the pill has no state of
 * its own beyond its motion, and this is recomputed from the automaton's state on every commit.
 *
 * [home] is the row a tap on the handle - or the pill opening by itself - shows: the pairs, the
 * repair row while one pane is empty, the pairs or the apps while both are. [apps] is what «+»
 * turns it into. [vacant] says a pane stands empty: the pill opens by itself and stays open.
 * [empty] names those panes, so a change from two empty panes to one - the first of them filled -
 * is a change the view can see although [vacant] stays true (ревью 2026-11-19, B3): the home row
 * is then the repair row.
 */
internal data class SplitPillDescription(
    val dividerX: Double,
    val leftPane: SplitPane,
    val home: List<PillItem>,
    val apps: List<PillItem>,
    val vacant: Boolean,
    val live: SplitPair?,
    val empty: Set<SplitPane> = emptySet(),
)

internal object SplitPillModel {

    /**
     * The pill for [state], the scene [live] as last read, the divider at [dividerX] (dp, `null`
     * when the window list shows none) and the launcher [catalogue] in its own order.
     *
     * `null` - nothing at all - unless a product two-pane scene is on the screen: the toggle on, the
     * scene split and visible, no navigator projected out of it, and a divider to stand on (1.15.1,
     * 1.15.9, 1.15.11, 1.15.12).
     *
     * Only what can be launched is offered (1.15.13, ревью 2026-11-19 A3/C6): once the catalogue
     * is loaded and not empty, a pair or an app it does not hold is left out before the three
     * places are counted - except the pair on screen, whose chip is always there. An empty
     * catalogue is one not read yet, and filters nothing. The model reads memory and never
     * changes it: forgetting an uninstalled app is the actor's (`PackageRemoved`).
     */
    fun describe(
        state: SplitState,
        live: SplitLiveScene,
        dividerX: Double?,
        catalogue: List<String>,
    ): SplitPillDescription? {
        if (!state.enabled) return null
        if (state.scene != SplitScene.Split || state.visibility != SceneVisibility.VISIBLE) return null
        if (state.projectedPane != null) return null
        val divider = dividerX ?: return null
        val leftPane = leftPaneAt(divider)
        val shown = SplitPane.entries.associateWith { pane -> shownIn(state, live, pane) }
        val empty = SplitPane.entries.filter { pane -> shown[pane] == null }
        val onScreen = shown.values.filterNotNull().toSet()
        val livePair = if (empty.isEmpty()) {
            SplitPair(shown.getValue(SplitPane.PRIMARY)!!, shown.getValue(SplitPane.SECONDARY)!!)
        } else {
            null
        }
        val launchable = Launchable(catalogue)
        val apps = appsRow(state, leftPane, onScreen, launchable)
        val home = when (empty.size) {
            0 -> pairsRow(state, leftPane, livePair, launchable)
            // A repair row with nothing in it - no pair to finish, no app to offer - is no row.
            1 -> repairRow(state, leftPane, empty.single(), shown, launchable).ifEmpty { apps }
            else -> if (remembered(state).any(launchable::pair)) pairsRow(state, leftPane, null, launchable) else apps
        }
        return SplitPillDescription(
            dividerX = divider,
            leftPane = leftPane,
            home = home,
            apps = apps,
            vacant = empty.isNotEmpty(),
            live = livePair,
            empty = empty.toSet(),
        )
    }

    /**
     * Which pane stands on the left. The narrow pane is the primary one (area 1, root 2): on the
     * left at the 434 detent, on the right at 846 (findings, "The divider is a DOCK_DIVIDER
     * window"). A divider left of the screen's middle therefore has the primary pane on its left.
     */
    fun leftPaneAt(dividerX: Double): SplitPane =
        if (dividerX < SplitPillSpec.Stage.W / 2) SplitPane.PRIMARY else SplitPane.SECONDARY

    /**
     * The app a pane shows: what the last read found there; a pane the process has no read of
     * shows what its slot names (an adopted scene before its first read). A pane on its picker -
     * whatever its slot still owes - shows nothing, and so does an owed pane with no read: owed
     * means its picker is on the screen (1.3.11).
     */
    private fun shownIn(state: SplitState, live: SplitLiveScene, pane: SplitPane): String? {
        val read = live[pane]
        if (read != null) return read.appPackageName
        if (pane in state.owed) return null
        return (state.slot(pane) as? SplitSlot.App)?.packageName
    }

    /** What the launch catalogue can start; an empty catalogue (not read yet) can start anything. */
    private class Launchable(val order: List<String>) {
        private val known = order.toSet()

        fun app(packageName: String): Boolean = known.isEmpty() || packageName in known

        fun pair(pair: SplitPair): Boolean = app(pair.primary) && app(pair.secondary)
    }

    /** The pairs memory holds, pins first, then the last and the recent ones (1.14.1). */
    private fun remembered(state: SplitState): List<SplitPair> {
        val memory = state.memory
        val pins = memory.pinned
        val others = (listOfNotNull(memory.last) + memory.recent).filter { pair -> pair !in pins }
        return (pins + others).distinct()
    }

    /**
     * Pinned pairs first, then recent ones, three chips at most; the pair on screen always among
     * them, taking the third place when it would not be; a separator, and «+». Only the live chip
     * carries a badge: outline while the pair is not pinned, solid once it is.
     */
    private fun pairsRow(
        state: SplitState,
        leftPane: SplitPane,
        live: SplitPair?,
        launchable: Launchable,
    ): List<PillItem> {
        val all = remembered(state).filter { pair -> pair == live || launchable.pair(pair) }
        val n = SplitPillSpec.Row.MAX_CHIPS.toInt()
        val chips = all.take(n).toMutableList()
        if (live != null && live !in chips) {
            if (chips.size < n) chips += live else chips[n - 1] = live
        }
        val pinned = state.memory.pinned
        val out = mutableListOf<PillItem>()
        chips.forEach { pair ->
            val isLive = pair == live
            out += chip(
                pair = pair,
                leftPane = leftPane,
                missingPane = null,
                live = isLive,
                badge = if (!isLive) null else if (pair in pinned) PillBadge.SOLID else PillBadge.OUTLINE,
            )
        }
        if (chips.isNotEmpty()) out += PillItem.Separator
        out += PillItem.Plus
        return out
    }

    /**
     * The apps row: up to three apps for the left pane on the left, up to three for the right pane
     * on the right, the divider's mark between them. Each half lays out outward from the mark, the
     * most recent next to it; never an app on screen in either pane.
     */
    private fun appsRow(
        state: SplitState,
        leftPane: SplitPane,
        onScreen: Set<String>,
        launchable: Launchable,
    ): List<PillItem> {
        val rightPane = leftPane.other()
        val left = singlesFor(state, leftPane, onScreen, launchable)
        val right = singlesFor(state, rightPane, onScreen, launchable)
        return left.reversed().map { app -> PillItem.Single(leftPane, app) } +
            PillItem.DividerMark +
            right.map { app -> PillItem.Single(rightPane, app) }
    }

    /**
     * The repair row (1.15.5): the pair that was on screen as an opened chip with the missing app
     * as a contour, a separator, and up to three apps for the empty pane on that pane's side, the
     * most recent next to the chip. No «+», no badge.
     *
     * The missing app is the empty pane's own choice while its slot still owes it (1.3.11: a
     * choice that did not land, a restore that did not), else the partner of the app still
     * standing in the pair it was last remembered with - the last pair first, then the pins and
     * the recent ones. The survivor is matched by its app, not its pane: a collapse leaves the
     * survivor in the wide pane whichever pane it stood in (findings, "The collapse survivor's
     * pane"), so the pair it came from may stand the other way round now, and the chip shows it
     * as it will stand once repaired (scenario 47). With no choice owed and no pair holding the
     * survivor there is no chip, only the apps; a missing app that cannot be launched gets no
     * chip either.
     */
    private fun repairRow(
        state: SplitState,
        leftPane: SplitPane,
        emptyPane: SplitPane,
        shown: Map<SplitPane, String?>,
        launchable: Launchable,
    ): List<PillItem> {
        val fullPane = emptyPane.other()
        val standing = shown.getValue(fullPane)!!
        val owed = (state.slot(emptyPane) as? SplitSlot.App)?.packageName
        val missing = (owed ?: partnerOf(state, standing))?.takeIf(launchable::app)
        val pair = missing?.let { app ->
            if (emptyPane == SplitPane.PRIMARY) SplitPair(app, standing) else SplitPair(standing, app)
        }
        val excluded = setOfNotNull(standing, missing)
        val ones = singlesFor(state, emptyPane, excluded, launchable)
            .map { app -> PillItem.Single(emptyPane, app) }
        if (pair == null) return if (emptyPane == leftPane) ones.reversed() else ones
        val chip = chip(pair, leftPane, missingPane = emptyPane, live = false, badge = null)
        return if (emptyPane == leftPane) {
            ones.reversed() + PillItem.Separator + chip
        } else {
            listOf(chip, PillItem.Separator) + ones
        }
    }

    /** The app [standing] was last remembered beside: the last pair first, then pins, then recent. */
    private fun partnerOf(state: SplitState, standing: String): String? {
        val memory = state.memory
        val pairs = listOfNotNull(memory.last) + memory.pinned + memory.recent
        val pair = pairs.firstOrNull { pair -> pair.primary == standing || pair.secondary == standing } ?: return null
        return if (pair.primary == standing) pair.secondary else pair.primary
    }

    /**
     * Up to three apps for [pane]: its own apps in the remembered pairs first (the last pair, the
     * recent ones, the pins), then the rest of the launch catalogue; never one in [excluded], never
     * one the catalogue cannot launch.
     */
    private fun singlesFor(
        state: SplitState,
        pane: SplitPane,
        excluded: Set<String>,
        launchable: Launchable,
    ): List<String> {
        val memory = state.memory
        val own = (listOfNotNull(memory.last) + memory.recent + memory.pinned).map { pair -> pair.packageOf(pane) }
        return (own + launchable.order)
            .asSequence()
            .filter { app -> app !in excluded && launchable.app(app) }
            .distinct()
            .take(SplitPillSpec.Row.MAX_SINGLES.toInt())
            .toList()
    }

    private fun chip(
        pair: SplitPair,
        leftPane: SplitPane,
        missingPane: SplitPane?,
        live: Boolean,
        badge: PillBadge?,
    ): PillItem.Chip = PillItem.Chip(
        pair = pair,
        left = pair.packageOf(leftPane),
        right = pair.packageOf(leftPane.other()),
        missing = missingPane?.let { pane -> if (pane == leftPane) Side.LEFT else Side.RIGHT },
        live = live,
        badge = badge,
    )

    private fun SplitPane.other(): SplitPane =
        if (this == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
}
