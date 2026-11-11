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

/**
 * Two applications and the pane of each (contract 1.14.1, 2.5): one package per [SplitPane], in the
 * slots' own coordinates. The same two applications the other way round are another pair. A pair
 * names packages only - never a task, a root or a physical side (invariant 4).
 */
internal data class SplitPair(val primary: String, val secondary: String) {
    fun packageOf(pane: SplitPane): String = when (pane) {
        SplitPane.PRIMARY -> primary
        SplitPane.SECONDARY -> secondary
    }

    operator fun contains(packageName: String): Boolean =
        primary == packageName || secondary == packageName

    val isWellFormed: Boolean get() = primary.isNotBlank() && secondary.isNotBlank()

    companion object {
        /** The pair two slots make, when both of them hold an application. */
        fun of(slots: Map<SplitPane, SplitSlot>): SplitPair? {
            val primary = slots[SplitPane.PRIMARY] as? SplitSlot.App ?: return null
            val secondary = slots[SplitPane.SECONDARY] as? SplitSlot.App ?: return null
            return SplitPair(primary.packageName, secondary.packageName)
        }
    }
}

/**
 * What the user put in the panes, as opposed to what they hold now (contract 1.14, 2.5).
 *
 * [last] is the pair that stood in both panes most recently; [recent] the pairs before it, most
 * recent first, at most [RECENT_LIMIT]; [pinned] the pairs the user pinned, in the user's order.
 * Three rules hold between them, and the automaton keeps them on every reduction: [recent] holds no
 * duplicate, never [last] and never a pinned pair. [last] and a pin may be the same pair.
 *
 * It describes no screen and drives nothing (1.14.5): it is the list a tap takes its target from.
 */
internal data class SplitMemory(
    val last: SplitPair? = null,
    val recent: List<SplitPair> = emptyList(),
    val pinned: List<SplitPair> = emptyList(),
) {
    /** The three rules above, plus the cap; what the store accepts as a memory it wrote. */
    val isWellFormed: Boolean
        get() = (last?.isWellFormed ?: true) &&
            recent.all(SplitPair::isWellFormed) &&
            pinned.all(SplitPair::isWellFormed) &&
            recent.size <= RECENT_LIMIT &&
            recent.distinct().size == recent.size &&
            pinned.distinct().size == pinned.size &&
            recent.none { pair -> pair == last || pair in pinned }

    companion object {
        /** Contract 1.14.1: five other recent pairs; the sixth pushes the oldest out (1.14.4). */
        const val RECENT_LIMIT = 5
    }
}

/** Whether the world is on screen or hidden behind Home, Recents or a foreign window. */
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
 * @param visibility whether the world this product's session lives in is on screen or covered by
 * Home, Recents or a foreign window. It is an axis of its own: a session that owns the firmware
 * gate has to follow the cover even in a process that came back with no scene in memory (1.12,
 * правка 2026-09-18), and `SceneRevealed` is what puts it back once a scene is proven ours again.
 * @param projectedPane pane whose navigator is temporarily on the instrument cluster (1.10).
 * @param vacancyApp ephemeral app occupying a projected pane's vacancy; lost on any restart.
 * @param memory the pairs the user put in the panes (1.14); durable, beside the slots.
 */
internal data class SplitState(
    val enabled: Boolean = false,
    val slots: Map<SplitPane, SplitSlot> = CLOSED_SLOTS,
    val scene: SplitScene? = null,
    val visibility: SceneVisibility = SceneVisibility.VISIBLE,
    val projectedPane: SplitPane? = null,
    val vacancyApp: Map<SplitPane, String> = emptyMap(),
    val memory: SplitMemory = SplitMemory(),
) {
    fun slot(pane: SplitPane): SplitSlot = slots[pane] ?: SplitSlot.Closed
}

/**
 * Settled facts: the only input of the automaton.
 *
 * A fact is an established observation ("the task left the panel roots"), never a raw event
 * ("onStop arrived", "TYPE_WINDOWS_CHANGED blinked") — contract section 7, invariant 8.
 *
 * Every one of them but the three pin facts has a producer: `ReconcileOperation` settles the
 * topology facts including [SceneEndedSettled] (1.7.5, scenario 30 - "clear all"),
 * `PackageRemovedOperation` settles [PackageRemoved] from what a live picker saw (1.5.6) and an
 * open settles it for a remembered package the catalogue no longer resolves (section 6). [PairPinned],
 * [PairUnpinned] and [PinsReordered] are the pill's (1.15.7, 1.15.8), which is not written yet: they
 * are reduced now so that the memory of 1.14 is whole, and nothing sends them.
 *
 * Invariant 14 (ред. 2026-11-11) is what the producers owe: a slot loses its package only because
 * the application left the pane on a visible scene ([AppClosedSettled]), the pane was collapsed
 * ([PaneCollapsedSettled]), another application was chosen there ([AppLaunchConfirmed]) or the
 * package left the car ([PackageRemoved]). An observation under cover, after a sleep or a process
 * restart, and any failed or late operation leave it as it is.
 *
 * There is deliberately no "the launch failed" fact. A launch that did not happen leaves the pane
 * exactly as the user left it - on its own picker, with the catalogue on screen and the tap live -
 * and that pane is a usable state, not an event the product state has to record (1.5.7, U5).
 */
internal sealed interface SplitFact {
    data class ToggleChanged(val enabled: Boolean) : SplitFact

    /**
     * A tap in a picker is answered by a launch and nothing else; the slot follows the launch the
     * recipe proved (1.5.1). There is no "selection requested" fact for the same reason there is no
     * "open requested" one: a request moves no slot, so it is not a fact the automaton can hear.
     */
    data class AppLaunchConfirmed(val pane: SplitPane, val packageName: String) : SplitFact

    /**
     * The application of [pane] left it on a scene the user was looking at: the last Back, a crash
     * in front of them, a caption swipe (1.6.2, 1.7.3, 1.14.6). A death proven under cover is not
     * this fact (invariant 14): the user did not see it, and the pane keeps the application.
     */
    data class AppClosedSettled(val pane: SplitPane) : SplitFact

    /**
     * Схлопывание дивайдером: одна панель закрыта, выживший остался один на экране (1.8.2).
     *
     * Факт называет ДВЕ панели, потому что на этой прошивке они расходятся. [collapsed] - панель,
     * чей ВЫБОР пользователь закрыл: её слот выбрасывается. [survivorPane] - панель, в которой
     * прошивка ОСТАВИЛА выжившего: с этого момента слот выжившего живёт там, и следующее открытие
     * восстанавливает его именно туда (1.3.4).
     *
     * Machine truth (корпус, [SplitCollapse.collapsedPaneByPanelBounds]): схлопывание
     * всегда отвечает area=2 и переносит выжившего в контейнер SECONDARY, какой бы стороной он ни
     * был до жеста. По решению владельца 2026-09-18 состояние экрана, которое оставил
     * пользователь, - это фактическая топология, а не прежняя метка стороны: музыка узкой панели
     * после закрытия широкой возвращалась следующим открытием обратно в узкую, хотя прошивка
     * оставила её в широкой. Раздел 5, «К 1.8»: привязка логического слота к стороне - по
     * фактической топологии после settle, не по старым меткам.
     *
     * Обычный случай `survivorPane == collapsed.other()` - прежнее поведение слово в слово.
     */
    data class PaneCollapsedSettled(
        val collapsed: SplitPane,
        val survivorPane: SplitPane,
    ) : SplitFact

    data class PickerPaneClosedSettled(val pane: SplitPane) : SplitFact

    data object SceneEndedSettled : SplitFact

    /**
     * The world is covered, by the one authority the product accepts for it: a read area (0 or 4).
     *
     * It carries no scene, and deliberately so: the gate this session owns follows the cover of the
     * world, including in a process that restarted over a session it does not remember (1.9.2,
     * 1.12, правка 2026-09-18).
     */
    data object HomeConfirmed : SplitFact

    data object SceneRevealed : SplitFact

    data class EdgeCommitConfirmed(val vacantPane: SplitPane) : SplitFact

    data class ProjectionStarted(val pane: SplitPane) : SplitFact

    data object ProjectionReturned : SplitFact

    data class PackageRemoved(val packageName: String) : SplitFact

    data class BuildSceneSucceeded(val slots: Map<SplitPane, SplitSlot>) : SplitFact

    /** The star on the pill: [pair], the last pair or a recent one, joins the pins at the end (1.15.7). */
    data class PairPinned(val pair: SplitPair) : SplitFact

    /** The remove mark in the pill's edit: the pin becomes the first recent pair (1.14.4, 1.15.8). */
    data class PairUnpinned(val pair: SplitPair) : SplitFact

    /** The pins dragged into a new order in the pill's edit (1.15.8): the same pins, rearranged. */
    data class PinsReordered(val order: List<SplitPair>) : SplitFact
}

/**
 * What one step of the automaton yields: the next semantic state, and whether the toggle went off
 * over a scene that still has to be torn down.
 *
 * Concrete shell steps, identity checks and rollback belong to the operations, which build their own
 * plans; the automaton schedules nothing. Returning a projected navigator, likewise, is an actor
 * operation running under the navigation lease ([SplitInputPriority.NAV]): the automaton only hears
 * about the return that already happened ([SplitFact.ProjectionReturned]).
 */
internal data class SplitReduction(
    val state: SplitState,
    /**
     * Единственное, что автомат сообщал операциям помимо состояния: выключили тумблер над сценой,
     * которую ещё надо разобрать.
     *
     * Здесь был `List<SplitPlan>` из семи вариантов - `BuildScene`, `RevealScene`, `LaunchApp`,
     * `AttachPicker`, `EndSplitFocusedFullscreen`, `RemoveProductPickers`, `SuspendOwnedGate`.
     * Ни один из них никто не исполнял: они только создавались, а весь продукт читал у списка
     * ровно одно - пустой он или нет, и ровно в одном месте (`DisableOperation.prepare`). Рецепты
     * прошивки написаны императивно и выверены на машине; план рядом с ними был вторым описанием
     * тех же намерений, которое ничего не решает.
     *
     * Тесты при этом утверждали конкретные варианты, и оттого выглядели как проверка мутаций,
     * которыми они не были: подменить один вариант другим - продукт вёл бы себя точно так же.
     * Это ровно тот случай, который контракт §10.3.2 запрещает называть тестом. Контрактное
     * различие 1.2.3 (полноэкранным остаётся сфокусированный) проверяется теперь там, где оно
     * исполняется - `theAppTheUserWasInIsTheOneLeftFullscreen`.
     */
    val teardownRequired: Boolean = false,
)
