package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_BALANCED_SPLIT
import dev.denza.apps.feature.split.SplitWorld.Companion.MAIN_DISPLAY_ID
import dev.denza.apps.feature.split.SplitWorld.Companion.ROOT_SETTLE_MS

/**
 * The divider and the stock picker it brings up (contract 1.8.5; findings, "The divider and the
 * stock picker, read end to end").
 *
 * An edge drag shows Launcher3's own picker in a pane of our scene; this waits read-only until the
 * gesture is committed and released, and then puts our picker in its place. The one synthetic drag
 * of the divider a build uses to reveal a truly empty scene lives here too. Every mutation here
 * first asks the input dispatcher whether a finger is still on the screen.
 */
internal class SplitEdge(
    private val world: SplitWorld,
    private val commands: SplitTaskCommands,
) {
    /**
     * Waits read-only while the user is dragging the native divider.
     *
     * BYD exposes the stock picker Activity before the drop is accepted and can report balanced
     * area 3 while the pointer is still down. No task operation may run until both signals settle
     * because launching our picker would steal the still-active divider gesture.
     */
    fun awaitNativePickerCommit(): Boolean {
        var releasedBalancedSamples = 0
        var releasedNonBalancedSamples = 0
        repeat(NATIVE_PICKER_COMMIT_ATTEMPTS) { attempt ->
            val balanced = world.callInt("service call activity_task 30") == AREA_BALANCED_SPLIT
            val pointerActive = hasActivePointer(world.shell("dumpsys input"))
            if (pointerActive) {
                releasedBalancedSamples = 0
                releasedNonBalancedSamples = 0
            } else if (balanced) {
                releasedBalancedSamples += 1
                releasedNonBalancedSamples = 0
            } else {
                releasedBalancedSamples = 0
                releasedNonBalancedSamples += 1
            }
            if (releasedBalancedSamples >= NATIVE_PICKER_RELEASED_SAMPLES) return true
            if (releasedNonBalancedSamples >= NATIVE_PICKER_CANCELLED_SAMPLES) return false
            if (attempt + 1 < NATIVE_PICKER_COMMIT_ATTEMPTS) {
                world.pause(NATIVE_PICKER_COMMIT_INTERVAL_MS)
            }
        }
        return false
    }

    /** Final read-only guard immediately before a stock-picker observation becomes a mutation. */
    fun nativePickerMutationAllowed(): Boolean =
        world.callInt("service call activity_task 30") == AREA_BALANCED_SPLIT &&
            !hasActivePointer(world.shell("dumpsys input"))

    private fun hasActivePointer(inputDump: String): Boolean {
        val stateStart = inputDump.indexOf("TouchStatesByDisplay:")
        if (stateStart < 0) return false
        val stateEnd = inputDump.indexOf("\n  Display:", startIndex = stateStart)
            .takeIf { it >= 0 }
            ?: inputDump.length
        return inputDump.substring(stateStart, stateEnd).contains("down=true")
    }

    fun observePane(
        pane: SplitPane,
        pickerComponents: Set<String>,
    ): SplitPickerPaneObservation {
        val roots = world.nativeRootIds()
        val root = world.snapshot().root(roots.getValue(pane))
        val nativeHost = root?.tasks?.firstOrNull { it.isNativeSplitBootstrap() }
        val pickerHost = nativeHost?.takeIf { it.matchesAnyTopComponent(pickerComponents) }
            ?: root?.tasks?.firstOrNull { it.matchesAnyComponent(pickerComponents) }
        val pickerVisible = pickerHost?.visible == true &&
            pickerHost.matchesAnyTopComponent(pickerComponents)
        val nativeVisible = nativeHost?.visible == true &&
            nativeHost.matchesOwnTopComponent()
        val host = when {
            pickerVisible -> pickerHost
            nativeVisible -> nativeHost
            else -> pickerHost ?: nativeHost
        }
        return SplitPickerPaneObservation(
            pane = pane,
            hostTaskId = host?.id,
            nativeHostVisible = nativeVisible,
            pickerVisible = pickerVisible,
            observedTaskIds = root?.tasks?.mapTo(mutableSetOf(), SplitTask::id).orEmpty(),
        )
    }

    fun attachPicker(
        pane: SplitPane,
        hostTaskId: Int,
        pickerComponent: String,
    ): Int {
        val rootId = world.nativeRootIds().getValue(pane)
        val host = world.snapshot().root(rootId)?.tasks
            ?.firstOrNull { it.id == hostTaskId && it.isNativeSplitBootstrap() }
            ?: error("Штатный host выбранного окна исчез")
        check(nativePickerMutationAllowed()) {
            "Нативный split изменился перед запуском picker"
        }
        val picker = commands.launchPickerInPane(pane, rootId, pickerComponent)
        removeBootstrapIfPresent(host)
        world.pause(ROOT_SETTLE_MS)
        val observed = observePane(pane, setOf(pickerComponent))
        check(observed.hostTaskId == picker.id && observed.pickerVisible) {
            "Пикер не стал верхним в выбранном окне"
        }
        return picker.id
    }

    private fun removeBootstrapIfPresent(previous: SplitTask) {
        val current = world.snapshot().roots.asSequence()
            .filter { it.displayId == MAIN_DISPLAY_ID }
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == previous.id && it.isNativeSplitBootstrap() }
            ?: return
        commands.removeTaskSafely(current)
    }

    /**
     * Раскрывает native split синтетическим перетаскиванием дивайдера.
     *
     * `internal`, а не `private`, по той же причине, что [awaitNativePickerCommit] и
     * [nativePickerMutationAllowed]: это охраняемая мутация, и её охрана проверяется напрямую.
     * Через reveal сюда не добраться - путь срабатывает только на по-настоящему пустой сцене,
     * и ни один сценарный тест до него не доходит (проверено: холодный `openPickerSession` не
     * отправляет ни одного `input swipe`). Единственная мутация в файле, которая не звала
     * [hasActivePointer], была ровно та, которую никто не мог позвать в тесте.
     */
    internal fun dragDividerToBalanced() {
        val inputState = world.shell("dumpsys input").also(world::validateOutput)
        val dividerLine = inputState.lineSequence().firstOrNull { line ->
            line.contains("multi-divider-shadow") && line.contains("frame=[")
        } ?: error("Нативный drag control не появился")
        val divider = DIVIDER_FRAME_PATTERN.find(dividerLine)
            ?: error("Нативный drag control не появился")
        val left = divider.groupValues[1].toInt()
        val top = divider.groupValues[2].toInt()
        val right = divider.groupValues[3].toInt()
        val bottom = divider.groupValues[4].toInt()
        // Детенты 856/1704 и ширина 2560 сняты живьём с панели ЭТОЙ машины; вычислять их из
        // чего-либо значило бы выдумать поведение прошивки, поэтому они остаются константами. Но
        // тогда обязана быть проверка, что панель та самая - иначе жест уйдёт по координатам
        // чужого экрана. Тень дивайдера растянута на всю высоту панели, и её высота - единственная
        // величина отсюда, которую можно с панелью сверить: 1600 и в живом дампе, и в измерении
        // экрана (2560x1600). Расходится - не отправляем ничего.
        //
        // Отрицательный left здесь нормален: тень уходит за край экрана (живьём frame=[-67,0]).
        if (bottom - top != PANEL_HEIGHT || right <= left) {
            error("Геометрия дивайдера не с этой панели: [$left,$top][$right,$bottom]")
        }
        val startX = ((left + right) / 2).coerceIn(EDGE_INSET, DISPLAY_WIDTH - EDGE_INSET)
        val endX = if (startX < DISPLAY_WIDTH / 2) LEFT_DIVIDER_X else RIGHT_DIVIDER_X
        // Вертикаль остаётся константой, и это следствие проверки выше, а не предположение: тень
        // растянута на всю высоту панели, панель обязана быть 1600, значит середина обязана быть
        // 800. Считать её из рамки я пробовал - при таком guard'е выражение не может дать другого
        // числа, то есть отличить вычисление от константы нечем, и тест на него был бы
        // декоративным (контракт §10.3.2).
        val y = DIVIDER_Y
        // Последнее, что делается перед мутацией, и на СВЕЖЕМ дампе: рамку читали раньше, а палец
        // за это время мог опуститься. Свой жест поверх чужого касания - это не гонка за сцену, а
        // порча жеста, который делает пользователь; этот путь и так холодный, лишнее чтение здесь
        // ничего не стоит. Тот же предикат, что охраняет мутацию штатного пикера
        // ([nativePickerMutationAllowed]) - там он уже применяется, здесь его просто не звали.
        if (hasActivePointer(world.shell("dumpsys input"))) {
            error("Дивайдер под пальцем: синтетический жест не отправляется")
        }
        world.run("input swipe $startX $y $endX $y $DIVIDER_DRAG_MS")
    }

    private companion object {
        const val NATIVE_PICKER_COMMIT_ATTEMPTS = 150
        const val NATIVE_PICKER_COMMIT_INTERVAL_MS = 100L
        // Two 100 ms samples were live-proven insufficient: edge collapse can expose area 3 for
        // substantially longer before settling to area 1/2. One second keeps this path read-only
        // through that firmware transition without drawing a window over the user's gesture.
        const val NATIVE_PICKER_RELEASED_SAMPLES = 10
        const val NATIVE_PICKER_CANCELLED_SAMPLES = 5
        const val DISPLAY_WIDTH = 2_560
        const val EDGE_INSET = 50
        const val PANEL_HEIGHT = 1_600
        const val DIVIDER_Y = PANEL_HEIGHT / 2
        const val LEFT_DIVIDER_X = 856
        const val RIGHT_DIVIDER_X = 1_704
        const val DIVIDER_DRAG_MS = 400
        val DIVIDER_FRAME_PATTERN = Regex(
            "frame=\\[(-?[0-9]+),(-?[0-9]+)]\\[(-?[0-9]+),(-?[0-9]+)]",
        )
    }
}
