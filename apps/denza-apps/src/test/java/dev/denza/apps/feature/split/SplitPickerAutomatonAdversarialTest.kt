package dev.denza.apps.feature.split

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPickerAutomatonAdversarialTest {
    @Test
    fun randomizedEventMutationsRemainValidAndSemanticallyIdempotent() {
        repeat(128) { seed ->
            val random = Random(seed)
            var state = SplitPickerAutomatonState()
            repeat(160) {
                val event = randomEvent(random)
                val before = state
                val result = SplitPickerAutomaton.reduce(before, event)

                assertValid(result.state)
                assertActionsExplainable(before, event, result)

                val duplicate = SplitPickerAutomaton.reduce(result.state, event)
                assertTrue(
                    "duplicate event emitted actions: seed=$seed event=$event actions=${duplicate.actions}",
                    duplicate.actions.isEmpty(),
                )
                assertValid(duplicate.state)
                state = result.state
            }
        }
    }

    @Test
    fun staleMutationMatrixCannotTouchAnEstablishedPair() {
        val active = activePair()
        val mutations = listOf(
            SplitPickerEvent.PickerAttached(SplitPane.PRIMARY, 999),
            SplitPickerEvent.AppOpened(SplitPane.PRIMARY, 100, 999, "new.app"),
            SplitPickerEvent.AppOpened(SplitPane.SECONDARY, 999, 30, "new.app"),
            SplitPickerEvent.PickerBecameTop(SplitPane.PRIMARY, 999, setOf(10, 20, 999)),
            SplitPickerEvent.PickerTaskGone(SplitPane.SECONDARY, 999),
            SplitPickerEvent.ProjectionStarted(SplitPane.PRIMARY, 20),
            SplitPickerEvent.ProjectionReturned(SplitPane.PRIMARY, 10),
        )

        mutations.forEach { event ->
            val result = SplitPickerAutomaton.reduce(active, event)
            assertEquals("mutation changed state: $event", active, result.state)
            assertTrue("mutation emitted action: $event", result.actions.isEmpty())
        }
    }

    @Test
    fun homeAndToggleOffCannotBeUndoneByLateEvents() {
        val active = activePair()
        val lateEvents = listOf(
            SplitPickerEvent.PickerBecameTop(SplitPane.PRIMARY, 100, setOf(10, 100)),
            SplitPickerEvent.PickerTaskGone(SplitPane.SECONDARY, 200),
            SplitPickerEvent.PickerAttached(SplitPane.PRIMARY, 100),
            SplitPickerEvent.AppOpened(SplitPane.SECONDARY, 200, 20, MUSIC),
            SplitPickerEvent.ProjectionStarted(SplitPane.PRIMARY, 10),
            SplitPickerEvent.ProjectionReturned(SplitPane.PRIMARY, 10),
        )

        listOf(SplitPickerEvent.HomeObserved, SplitPickerEvent.ToggleOff).forEach { terminal ->
            var state = SplitPickerAutomaton.reduce(active, terminal).state
            lateEvents.forEach { late ->
                val result = SplitPickerAutomaton.reduce(state, late)
                assertTrue("late event emitted action after $terminal: $late", result.actions.isEmpty())
                state = result.state
            }
            assertEquals(SplitPickerPhase.IDLE, state.phase)
            assertTrue(state.slots.values.all { it.kind == SplitPickerSlotKind.CLOSED })
        }
    }

    private fun randomEvent(random: Random): SplitPickerEvent {
        val pane = if (random.nextBoolean()) SplitPane.PRIMARY else SplitPane.SECONDARY
        val host = listOf(100, 101, 200, 201, -1).random(random)
        val task = listOf(10, 11, 20, 21, -1).random(random)
        val packageName = listOf(NAVIGATOR, MUSIC, "new.app", "").random(random)
        return when (random.nextInt(13)) {
            0 -> SplitPickerEvent.OpenRequested
            1 -> SplitPickerEvent.HomeObserved
            2 -> SplitPickerEvent.ToggleOff
            3 -> SplitPickerEvent.DividerResized
            4 -> SplitPickerEvent.NativePickerObserved(pane, host)
            5 -> SplitPickerEvent.PickerAttached(pane, host)
            6 -> SplitPickerEvent.AppOpened(pane, host, task, packageName)
            7 -> SplitPickerEvent.PickerBecameTop(
                pane,
                host,
                buildSet {
                    if (random.nextBoolean()) add(task)
                    if (random.nextBoolean()) add(10)
                    if (random.nextBoolean()) add(20)
                },
            )
            8 -> SplitPickerEvent.PickerTaskGone(pane, host)
            9 -> SplitPickerEvent.ProjectionStarted(pane, task)
            10 -> SplitPickerEvent.PickerAttachFailed(pane, host)
            else -> SplitPickerEvent.ProjectionReturned(pane, task)
        }
    }

    private fun assertValid(state: SplitPickerAutomatonState) {
        state.slots.forEach { (pane, slot) ->
            when (slot.kind) {
                SplitPickerSlotKind.CLOSED -> {
                    assertEquals("$pane closed host", null, slot.hostTaskId)
                    assertEquals("$pane closed app", null, slot.appTaskId)
                    assertEquals("$pane closed package", null, slot.packageName)
                }
                SplitPickerSlotKind.ATTACHING -> {
                    assertTrue("$pane host", (slot.hostTaskId ?: -1) > 0)
                    if (slot.appTaskId == null) {
                        assertEquals("$pane attaching package", null, slot.packageName)
                    } else {
                        assertTrue("$pane attaching app", slot.appTaskId > 0)
                        assertTrue("$pane attaching package", !slot.packageName.isNullOrBlank())
                    }
                }
                SplitPickerSlotKind.PICKER -> {
                    assertTrue("$pane host", (slot.hostTaskId ?: -1) > 0)
                    assertEquals("$pane picker app", null, slot.appTaskId)
                    assertEquals("$pane picker package", null, slot.packageName)
                }
                SplitPickerSlotKind.APP -> {
                    assertTrue("$pane app host", (slot.hostTaskId ?: -1) > 0)
                    assertTrue("$pane app task", (slot.appTaskId ?: -1) > 0)
                    assertTrue("$pane app package", !slot.packageName.isNullOrBlank())
                }
                SplitPickerSlotKind.PROJECTED -> {
                    assertTrue("$pane projected task", (slot.appTaskId ?: -1) > 0)
                    assertTrue("$pane projected package", !slot.packageName.isNullOrBlank())
                }
                SplitPickerSlotKind.PROJECTED_ATTACHING -> {
                    assertTrue("$pane projected host", (slot.hostTaskId ?: -1) > 0)
                    assertTrue("$pane projected task", (slot.appTaskId ?: -1) > 0)
                    assertTrue("$pane projected package", !slot.packageName.isNullOrBlank())
                }
            }
        }
    }

    private fun assertActionsExplainable(
        before: SplitPickerAutomatonState,
        event: SplitPickerEvent,
        result: SplitPickerReduction,
    ) {
        result.actions.forEach { action ->
            when (action) {
                SplitPickerAction.PrepareNativeSession ->
                    assertTrue(event is SplitPickerEvent.OpenRequested)
                is SplitPickerAction.AttachPicker -> {
                    val observed = event as? SplitPickerEvent.NativePickerObserved
                    assertNotNull(observed)
                    assertEquals(observed?.pane, action.pane)
                    assertEquals(observed?.hostTaskId, action.hostTaskId)
                }
                is SplitPickerAction.RemoveExactTask -> {
                    val pane = when (event) {
                        is SplitPickerEvent.PickerBecameTop -> event.pane
                        is SplitPickerEvent.NativePickerObserved -> event.pane
                        else -> null
                    }
                    assertNotNull(pane)
                    val slot = pane?.let(before::slot)
                    assertEquals(SplitPickerSlotKind.APP, slot?.kind)
                    assertEquals(slot?.appTaskId, action.taskId)
                    assertEquals(slot?.packageName, action.packageName)
                    val observed = when (event) {
                        is SplitPickerEvent.PickerBecameTop -> event.observedTaskIds
                        is SplitPickerEvent.NativePickerObserved -> event.observedTaskIds
                        else -> emptySet()
                    }
                    assertTrue(action.taskId in observed)
                }
                is SplitPickerAction.ClosePickerAndGoHome -> {
                    val gone = event as? SplitPickerEvent.PickerTaskGone
                    assertNotNull(gone)
                    assertEquals(SplitPickerSlotKind.PICKER, before.slot(gone!!.pane).kind)
                    assertEquals(SplitPickerSlotKind.PICKER, before.slot(gone.pane.other()).kind)
                }
                is SplitPickerAction.ReturnTaskFullscreen -> {
                    val returned = event as? SplitPickerEvent.ProjectionReturned
                    assertNotNull(returned)
                    val slot = before.slot(returned!!.pane)
                    assertEquals(SplitPickerSlotKind.PROJECTED, slot.kind)
                    assertTrue(slot.userClosedWhileProjected || slot.hostTaskId == null)
                    assertEquals(returned.pane, action.pane)
                    assertEquals(returned.taskId, action.taskId)
                }
            }
        }
    }

    private fun activePair() = SplitPickerAutomatonState(
        armed = true,
        phase = SplitPickerPhase.SPLIT,
        slots = mapOf(
            SplitPane.PRIMARY to SplitPickerSlotState(
                kind = SplitPickerSlotKind.APP,
                hostTaskId = 100,
                appTaskId = 10,
                packageName = NAVIGATOR,
            ),
            SplitPane.SECONDARY to SplitPickerSlotState(
                kind = SplitPickerSlotKind.APP,
                hostTaskId = 200,
                appTaskId = 20,
                packageName = MUSIC,
            ),
        ),
    )

    private companion object {
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val MUSIC = "ru.yandex.music"
    }
}
