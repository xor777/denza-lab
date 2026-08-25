package dev.denza.apps.feature.split

/**
 * Pure semantic axes of the split product: the vocabulary of the contract automaton.
 *
 * Nothing here knows about ADB, time, threads or task/root ids (contract sections 2 and 7,
 * invariant 4). The axes are deliberately independent: the durable selection lives in [SplitSlot],
 * the live product scene in [SplitScene], and its visibility in [SceneVisibility].
 */

private val CLOSED_SLOTS: Map<SplitPane, SplitSlot> =
    SplitPane.entries.associateWith { SplitSlot.Closed }

/** Durable content of one pane (contract 2.2). A slot never carries a task or root id. */
internal sealed interface SplitSlot {
    /** The pane does not exist: nothing of ours is shown there and nothing may be revived. */
    data object Closed : SplitSlot

    /** The permanent floor of a live pane (contract 1.4.1). */
    data object Picker : SplitSlot

    /** A user-selected application. Both panes may independently hold the same package (1.5.2). */
    data class App(val packageName: String) : SplitSlot
}

/**
 * Existence and shape of the live product scene (contract 2.3).
 *
 * The scene never duplicates pane content: what each pane holds is stated once, in
 * [SplitState.slots]. `null` scene means there is no product scene at all.
 */
internal sealed interface SplitScene {
    /** Both panes are on screen. */
    data object Split : SplitScene

    /** Only [pane] survived; the opposite pane is closed. */
    data class Full(val pane: SplitPane) : SplitScene
}

/** Whether an existing scene is on screen or hidden behind Home, Recents or a foreign window. */
internal enum class SceneVisibility {
    VISIBLE,
    COVERED,
}

/**
 * The whole semantic state of the product.
 *
 * @param enabled the user toggle (contract 2.1). While it is off the product is inert.
 * @param slots durable projection of both panes; the single source of truth for pane content.
 * @param scene the live product scene, or `null` when there is none.
 * @param visibility visibility of an existing scene; meaningless while [scene] is `null`.
 * @param projectedPane pane whose navigator is temporarily on the instrument cluster (1.10).
 * @param vacancyApp ephemeral app occupying a projected pane's vacancy; lost on any restart.
 */
internal data class SplitState(
    val enabled: Boolean = false,
    val slots: Map<SplitPane, SplitSlot> = CLOSED_SLOTS,
    val scene: SplitScene? = null,
    val visibility: SceneVisibility = SceneVisibility.VISIBLE,
    val projectedPane: SplitPane? = null,
    val vacancyApp: Map<SplitPane, String> = emptyMap(),
) {
    fun slot(pane: SplitPane): SplitSlot = slots[pane] ?: SplitSlot.Closed
}

/**
 * Settled facts: the only input of the automaton.
 *
 * A fact is an established observation ("the task left the panel roots"), never a raw event
 * ("onStop arrived", "TYPE_WINDOWS_CHANGED blinked") — contract section 7, invariant 8.
 *
 * Every one of them has a producer: `ReconcileOperation` settles the topology facts including
 * [SceneEndedSettled] (1.7.5, scenario 30 - "clear all") and `PackageRemovedOperation` settles
 * [PackageRemoved] from what a live picker saw (1.5.6).
 *
 * There is deliberately no "the launch failed" fact. A launch that did not happen leaves the pane
 * exactly as the user left it - on its own picker, with the catalogue on screen and the tap live -
 * and that pane is a usable state, not an event the product state has to record (1.5.7, U5).
 */
internal sealed interface SplitFact {
    data class ToggleChanged(val enabled: Boolean) : SplitFact

    data object OpenRequested : SplitFact

    data class SelectionRequested(val pane: SplitPane, val packageName: String) : SplitFact

    data class AppLaunchConfirmed(val pane: SplitPane, val packageName: String) : SplitFact

    /** Back, close gesture, swipe from Recents or a crash: all of them are one closure (1.7.3). */
    data class AppClosedSettled(val pane: SplitPane) : SplitFact

    data class PaneCollapsedSettled(val survivor: SplitPane) : SplitFact

    data class PickerPaneClosedSettled(val pane: SplitPane) : SplitFact

    data object SceneEndedSettled : SplitFact

    data object HomeConfirmed : SplitFact

    data object SceneRevealed : SplitFact

    data class EdgeCommitConfirmed(val vacantPane: SplitPane) : SplitFact

    data class ProjectionStarted(val pane: SplitPane) : SplitFact

    data object ProjectionReturned : SplitFact

    data class PackageRemoved(val packageName: String) : SplitFact

    data class BuildSceneSucceeded(val slots: Map<SplitPane, SplitSlot>) : SplitFact
}

/**
 * High-level intents produced by the automaton.
 *
 * Concrete shell steps, identity checks and rollback belong to the operation runner; a plan only
 * says what the product wants to happen.
 *
 * Returning a projected navigator is deliberately absent: it is an actor operation running under
 * the navigation lease ([SplitInputPriority.NAV]), not something the automaton schedules. The
 * automaton only hears about the return that already happened ([SplitFact.ProjectionReturned]).
 *
 * The operations are written settled-first, so today only `DisableOperation` reads a plan, and it
 * reads whether the list is empty rather than which variant it holds: "the toggle went off over a
 * scene that needs a teardown". The variants are the automaton's own statement of which teardown
 * the contract asks for (1.2.3 versus 1.2.4-1.2.5) and are asserted as such by its tests.
 */
internal sealed interface SplitPlan {
    data class BuildScene(val slots: Map<SplitPane, SplitSlot>) : SplitPlan

    data object RevealScene : SplitPlan

    data class LaunchApp(val pane: SplitPane, val packageName: String) : SplitPlan

    data class AttachPicker(val pane: SplitPane) : SplitPlan

    /** Toggle-off over two live apps: the focused one stays fullscreen (1.2.3). */
    data object EndSplitFocusedFullscreen : SplitPlan

    /** Toggle-off over a scene containing at least one of our pickers (1.2.4, 1.2.5). */
    data object RemoveProductPickers : SplitPlan

    data object SuspendOwnedGate : SplitPlan
}

internal data class SplitReduction(
    val state: SplitState,
    val plans: List<SplitPlan> = emptyList(),
)
