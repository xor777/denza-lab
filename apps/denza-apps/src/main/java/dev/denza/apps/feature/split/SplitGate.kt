package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_POLL_INTERVAL_MS

/**
 * Every transition a session makes on the firmware's split gate (tx126 `setStartToSplit`), together
 * with the lease that records that the product opened it (contract, to 1.12; findings, "Placement,
 * read end to end").
 *
 * The gate is one firmware-global switch: open, every split-capable start lands in split; closed,
 * even a pane category goes fullscreen. A session opens it for a build, a reveal and a selection,
 * suspends it while a cover hides the scene, resumes it when the covered scene is on screen again,
 * and closes it when the scene ends. Each suspension and resumption is decided by a read area and
 * by the lease, never by an event.
 */
internal class SplitGate(
    private val world: SplitWorld,
    private val gateLeaseStore: SplitGateLeaseStore,
) {
    fun ensureGateOpen() {
        // On this DiLink 5.1 build tx123 is `isCanSplit()`: for the BYD platform branch it is
        // a constant capability answer, not the current mIsEnterSplit value. Only tx126 changes
        // the mutable gate, and it is idempotent in the firmware.
        world.callVoid("service call activity_task 126 i32 1")
        if (!gateLeaseStore.setOwned(true)) {
            runCatching { world.callVoid("service call activity_task 126 i32 0") }
            error("Не удалось сохранить владение split-gate")
        }
    }

    /**
     * Closes only the split gate owned by this product after Home is authoritative.
     *
     * DiLink retains a separate global "last split pair" and otherwise resurrects that OEM pair
     * when the user launches either remembered member from Home. Keep the lease so the next
     * explicit Split Screen launch can reopen the gate, but never touch a gate we did not acquire.
     *
     * Правка W5 (1.9.3, диагноз v21 Д3-Б): закрыть gate при накрытой сцене - обязанность
     * продукта, надёжно: при открытом gate прошивка сама втягивает split-способный пакет в
     * широкую панель. Прежние шесть проб по 100 мс сдавались тихо; подтверждение накрытия теперь
     * ретраится до [HOME_CONFIRM_BUDGET_MS], а [displaced] отдаёт воркер пользовательскому вводу
     * немедленно - явное действие не ждёт фоновый шум (§4).
     *
     * Правка W3 волны 7: подтверждение - предикат накрытия ([SplitWorld.sceneCovered]: area 0 ИЛИ 4), не
     * строгое ==0. Карта tx30 живьём (2026-08-25): в переходном грязном мире area дребезжит
     * 0↔4 - чужое fullscreen-окно поверх накрывает сцену так же честно, как Home (1.11.5), а
     * жёсткое ==0 сжигало весь бюджет над честно накрытой сценой и оставляло gate открытым.
     */
    fun suspendOwnedGateForHome(
        displaced: () -> Boolean = { false },
    ): Boolean {
        if (!gateLeaseStore.isOwned()) return false
        var waited = 0L
        while (true) {
            if (suspendOwnedGateIfCovered()) return true
            if (waited >= HOME_CONFIRM_BUDGET_MS || displaced()) return false
            val slice = minOf(AREA_POLL_INTERVAL_MS, HOME_CONFIRM_BUDGET_MS - waited)
            world.pause(slice)
            waited += slice
        }
    }

    /**
     * One read, one decision, no waiting: the same suspension as [suspendOwnedGateForHome], for a
     * caller that already has a reason to look at the world and no right to block in it.
     *
     * Правка волны 17 (живой диагноз v33, 2026-08-26). Подвеска gate висела на ОДНОМ триггере -
     * accessibility-событии пакета лаунчера, - и живой прогон показал, что событие приходит не
     * всегда: из восьми обычных Home над живой сценой хинт пришёл дважды, а в шести случаях
     * `HomeOperation` не запускалась ВООБЩЕ, gate оставался открытым (проверено через 65 с после
     * Home), и следующий обычный запуск из дока прошивка втягивала в split - против 1.9.2. При
     * этом на каждый Home приходили TYPE_WINDOWS_CHANGED, то есть сверка мир перечитывала и
     * накрытие ВИДЕЛА (`collapse: area=0` в живом ринге), но про gate не знала.
     *
     * Полномочие мутации здесь то же, что и всегда: не событие, а прочитанная area 0/4
     * ([SplitWorld.sceneCovered], 1.9.1, 1.11.5). Никакого нового канала и никакого таймерного цикла: это
     * один вопрос машине внутри уже запланированного чтения.
     *
     * @return whether this call is what suspended it.
     */
    fun suspendOwnedGateIfCovered(): Boolean {
        if (!gateLeaseStore.isOwned()) return false
        if (!world.sceneCovered()) return false
        world.callVoid("service call activity_task 126 i32 0")
        return true
    }

    /**
     * The mirror of [suspendOwnedGateIfCovered]: one read, one decision, no waiting.
     *
     * A suspension has exactly two ways back before this existed - the explicit open and the
     * explicit tap in a picker, both of which run [ensureGateOpen]. A scene covered by something
     * other than Home comes back by itself: the call ends, the camera goes away, the notification's
     * app is closed with Back, and the pair the user left is on the screen again with the gate the
     * product closed under the cover still closed. `startIviWindow` then answers every new task and
     * every move-to-front of a pane member with `startFullWindow` (findings, "which panel a task
     * lands in"), which is a member of the scene escaping to fullscreen with nobody having asked.
     *
     * Mutation authority is the same as the suspension's: a read area, never an event. Only a gate
     * this product holds the lease for, and only over a scene the area calls visible (1/2/3).
     *
     * @return whether this call is what resumed it.
     */
    fun resumeOwnedGateIfVisible(): Boolean {
        if (!gateLeaseStore.isOwned()) return false
        if (world.sceneCovered()) return false
        world.callVoid("service call activity_task 126 i32 1")
        return true
    }

    /**
     * The gate is firmware-global, so it is closed by exactly one rule: we opened it, therefore we
     * close it (contract, to 1.12; invariant 1). A gate that was already open when our session
     * started, or that belongs to a stock split the user built themselves, is left alone.
     *
     * @return whether this call is what closed it.
     */
    fun closeOwnedGate(): Boolean {
        if (!gateLeaseStore.isOwned()) return false
        world.callVoid("service call activity_task 126 i32 0")
        check(gateLeaseStore.setOwned(false)) { "Не удалось освободить split-gate" }
        return true
    }

    private companion object {
        /**
         * Правка W5: сколько suspend ждёт подтверждения area==0. Тихая сдача после 6×100 мс
         * оставляла gate открытым над накрытой сценой (v21 Д3, уверенность «gate был открыт»
         * ~0.8) - и прошивка честно втягивала следующий запуск в широкую панель.
         */
        const val HOME_CONFIRM_BUDGET_MS = 3_000L
    }
}
