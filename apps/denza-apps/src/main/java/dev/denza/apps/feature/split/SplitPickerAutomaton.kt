package dev.denza.apps.feature.split

internal enum class SplitPickerPhase {
    IDLE,
    OPENING,
    SPLIT,
    FULL,
}

internal enum class SplitPickerSlotKind {
    CLOSED,
    ATTACHING,
    PICKER,
    APP,
    PROJECTED,
    PROJECTED_ATTACHING,
}

internal data class SplitPickerSlotState(
    val kind: SplitPickerSlotKind = SplitPickerSlotKind.CLOSED,
    val hostTaskId: Int? = null,
    val appTaskId: Int? = null,
    val packageName: String? = null,
    val userClosedWhileProjected: Boolean = false,
    val attachAttempts: Int = 0,
)

internal data class SplitPickerAutomatonState(
    val armed: Boolean = false,
    val phase: SplitPickerPhase = SplitPickerPhase.IDLE,
    val slots: Map<SplitPane, SplitPickerSlotState> = SplitPane.entries.associateWith {
        SplitPickerSlotState()
    },
) {
    fun slot(pane: SplitPane): SplitPickerSlotState =
        slots[pane] ?: SplitPickerSlotState()
}

internal sealed interface SplitPickerEvent {
    data object OpenRequested : SplitPickerEvent
    data object HomeObserved : SplitPickerEvent
    data object DividerResized : SplitPickerEvent
    data object ToggleOff : SplitPickerEvent

    data class NativePickerObserved(
        val pane: SplitPane,
        val hostTaskId: Int,
        val observedTaskIds: Set<Int> = emptySet(),
    ) : SplitPickerEvent

    data class PickerAttached(
        val pane: SplitPane,
        val hostTaskId: Int,
    ) : SplitPickerEvent

    data class PickerAttachFailed(
        val pane: SplitPane,
        val hostTaskId: Int,
    ) : SplitPickerEvent

    data class AppOpened(
        val pane: SplitPane,
        val hostTaskId: Int,
        val taskId: Int,
        val packageName: String,
    ) : SplitPickerEvent

    data class PickerBecameTop(
        val pane: SplitPane,
        val hostTaskId: Int,
        val observedTaskIds: Set<Int>,
    ) : SplitPickerEvent

    data class PickerTaskGone(
        val pane: SplitPane,
        val hostTaskId: Int,
    ) : SplitPickerEvent

    data class ProjectionStarted(
        val pane: SplitPane,
        val taskId: Int,
    ) : SplitPickerEvent

    data class ProjectionReturned(
        val pane: SplitPane,
        val taskId: Int,
    ) : SplitPickerEvent
}

internal sealed interface SplitPickerAction {
    data object PrepareNativeSession : SplitPickerAction

    data class AttachPicker(
        val pane: SplitPane,
        val hostTaskId: Int,
    ) : SplitPickerAction

    data class RemoveExactTask(
        val taskId: Int,
        val packageName: String,
    ) : SplitPickerAction

    data class RemovePickerArtifact(
        val hostTaskId: Int,
    ) : SplitPickerAction

    data class ReturnTaskFullscreen(
        val pane: SplitPane,
        val taskId: Int,
        val packageName: String,
    ) : SplitPickerAction
}

internal data class SplitPickerReduction(
    val state: SplitPickerAutomatonState,
    val actions: List<SplitPickerAction> = emptyList(),
)

/**
 * Pure user-intent state machine for the hosted picker lifecycle.
 *
 * It deliberately has no foreground inference and no timing. Repeated observations are no-ops
 * after the first semantic transition, so Activity visibility/configuration flapping cannot
 * produce an unbounded mutation loop.
 */
internal object SplitPickerAutomaton {
    fun reduce(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent,
    ): SplitPickerReduction = when (event) {
        SplitPickerEvent.OpenRequested -> open(current)
        SplitPickerEvent.HomeObserved -> idle(current, armed = current.armed)
        SplitPickerEvent.ToggleOff -> idle(current, armed = false)
        SplitPickerEvent.DividerResized -> unchanged(current)
        is SplitPickerEvent.NativePickerObserved -> nativePicker(current, event)
        is SplitPickerEvent.PickerAttached -> pickerAttached(current, event)
        is SplitPickerEvent.PickerAttachFailed -> pickerAttachFailed(current, event)
        is SplitPickerEvent.AppOpened -> appOpened(current, event)
        is SplitPickerEvent.PickerBecameTop -> pickerBecameTop(current, event)
        is SplitPickerEvent.PickerTaskGone -> pickerTaskGone(current, event)
        is SplitPickerEvent.ProjectionStarted -> projectionStarted(current, event)
        is SplitPickerEvent.ProjectionReturned -> projectionReturned(current, event)
    }

    private fun open(current: SplitPickerAutomatonState): SplitPickerReduction {
        if (current.phase == SplitPickerPhase.OPENING) return unchanged(current)
        return SplitPickerReduction(
            state = SplitPickerAutomatonState(
                armed = true,
                phase = SplitPickerPhase.OPENING,
            ),
            actions = listOf(SplitPickerAction.PrepareNativeSession),
        )
    }

    private fun nativePicker(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent.NativePickerObserved,
    ): SplitPickerReduction {
        if (!current.armed || event.hostTaskId <= 0) return unchanged(current)
        val existing = current.slot(event.pane)
        if (existing.hostTaskId == event.hostTaskId &&
            existing.attachAttempts >= MAX_ATTACH_ATTEMPTS
        ) {
            return unchanged(current)
        }
        val nextAttachAttempts = if (existing.hostTaskId == event.hostTaskId) {
            existing.attachAttempts + 1
        } else {
            1
        }
        val attach = SplitPickerAction.AttachPicker(event.pane, event.hostTaskId)
        return when (existing.kind) {
            SplitPickerSlotKind.CLOSED -> SplitPickerReduction(
                state = current.withSlot(
                    event.pane,
                    SplitPickerSlotState(
                        kind = SplitPickerSlotKind.ATTACHING,
                        hostTaskId = event.hostTaskId,
                        attachAttempts = 1,
                    ),
                ),
                actions = listOf(attach),
            )
            SplitPickerSlotKind.PICKER -> SplitPickerReduction(
                state = current.withSlot(
                    event.pane,
                    existing.copy(
                        kind = SplitPickerSlotKind.ATTACHING,
                        hostTaskId = event.hostTaskId,
                        attachAttempts = nextAttachAttempts,
                    ),
                ),
                actions = listOf(attach),
            )
            SplitPickerSlotKind.APP -> {
                val appTaskId = existing.appTaskId
                val packageName = existing.packageName
                val remove = if (appTaskId != null &&
                    packageName != null &&
                    appTaskId in event.observedTaskIds
                ) {
                    listOf(SplitPickerAction.RemoveExactTask(appTaskId, packageName))
                } else {
                    emptyList()
                }
                SplitPickerReduction(
                    state = current.withSlot(
                        event.pane,
                        existing.copy(
                            kind = SplitPickerSlotKind.ATTACHING,
                            hostTaskId = event.hostTaskId,
                            attachAttempts = nextAttachAttempts,
                        ),
                    ),
                    actions = listOf(attach) + remove,
                )
            }
            SplitPickerSlotKind.PROJECTED,
            SplitPickerSlotKind.PROJECTED_ATTACHING,
            -> {
                if (existing.kind == SplitPickerSlotKind.PROJECTED_ATTACHING &&
                    existing.hostTaskId == event.hostTaskId
                ) {
                    unchanged(current)
                } else {
                    SplitPickerReduction(
                        state = current.withSlot(
                            event.pane,
                            existing.copy(
                                kind = SplitPickerSlotKind.PROJECTED_ATTACHING,
                                hostTaskId = event.hostTaskId,
                                userClosedWhileProjected = false,
                                attachAttempts = nextAttachAttempts,
                            ),
                        ),
                        actions = listOf(attach),
                    )
                }
            }
            SplitPickerSlotKind.ATTACHING -> {
                if (existing.hostTaskId == event.hostTaskId) unchanged(current)
                else SplitPickerReduction(
                    state = current.withSlot(
                        event.pane,
                        existing.copy(
                            hostTaskId = event.hostTaskId,
                            attachAttempts = nextAttachAttempts,
                        ),
                    ),
                    actions = listOf(attach),
                )
            }
        }
    }

    private fun pickerAttached(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent.PickerAttached,
    ): SplitPickerReduction {
        if (event.hostTaskId <= 0) return unchanged(current)
        val existing = current.slot(event.pane)
        if (existing.kind == SplitPickerSlotKind.PICKER ||
            existing.kind == SplitPickerSlotKind.APP ||
            existing.kind == SplitPickerSlotKind.PROJECTED
        ) {
            return unchanged(current)
        }
        if (existing.kind == SplitPickerSlotKind.PROJECTED_ATTACHING) {
            return unchanged(
                current.withSlot(
                    event.pane,
                    existing.copy(
                        kind = SplitPickerSlotKind.PROJECTED,
                        hostTaskId = event.hostTaskId,
                        attachAttempts = 0,
                    ),
                ),
            )
        }
        if (existing.kind != SplitPickerSlotKind.ATTACHING) return unchanged(current)
        return unchanged(
            current.withSlot(
                event.pane,
                SplitPickerSlotState(
                    kind = SplitPickerSlotKind.PICKER,
                    hostTaskId = event.hostTaskId,
                ),
            ),
        )
    }

    private fun pickerAttachFailed(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent.PickerAttachFailed,
    ): SplitPickerReduction {
        val existing = current.slot(event.pane)
        if (existing.hostTaskId != event.hostTaskId) return unchanged(current)
        return when (existing.kind) {
            SplitPickerSlotKind.ATTACHING -> unchanged(
                current.withSlot(
                    event.pane,
                    existing.copy(
                        kind = if (existing.appTaskId != null) {
                            SplitPickerSlotKind.APP
                        } else {
                            SplitPickerSlotKind.PICKER
                        },
                    ),
                ),
            )
            SplitPickerSlotKind.PROJECTED_ATTACHING -> unchanged(
                current.withSlot(
                    event.pane,
                    existing.copy(kind = SplitPickerSlotKind.PROJECTED),
                ),
            )
            else -> unchanged(current)
        }
    }

    private fun appOpened(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent.AppOpened,
    ): SplitPickerReduction {
        if (event.taskId <= 0 || event.packageName.isBlank()) return unchanged(current)
        val existing = current.slot(event.pane)
        if (existing.hostTaskId != event.hostTaskId ||
            existing.kind !in setOf(
                SplitPickerSlotKind.PICKER,
                SplitPickerSlotKind.ATTACHING,
                // Migration safety for a picker state persisted by builds that reserved the
                // entire pane while its previous app was projected to another display.
                SplitPickerSlotKind.PROJECTED,
                SplitPickerSlotKind.PROJECTED_ATTACHING,
            )
        ) {
            return unchanged(current)
        }
        return unchanged(
            current.withSlot(
                event.pane,
                SplitPickerSlotState(
                    kind = SplitPickerSlotKind.APP,
                    hostTaskId = event.hostTaskId,
                    appTaskId = event.taskId,
                    packageName = event.packageName,
                ),
            ),
        )
    }

    private fun pickerBecameTop(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent.PickerBecameTop,
    ): SplitPickerReduction {
        val existing = current.slot(event.pane)
        if (existing.hostTaskId != event.hostTaskId) return unchanged(current)
        if (existing.kind == SplitPickerSlotKind.PROJECTED ||
            existing.kind == SplitPickerSlotKind.PROJECTED_ATTACHING ||
            existing.kind == SplitPickerSlotKind.PICKER ||
            existing.kind == SplitPickerSlotKind.ATTACHING
        ) {
            return unchanged(current)
        }
        if (existing.kind != SplitPickerSlotKind.APP) return unchanged(current)
        val appTaskId = existing.appTaskId ?: return unchanged(current)
        val packageName = existing.packageName ?: return unchanged(current)
        val next = current.withSlot(
            event.pane,
            SplitPickerSlotState(
                kind = SplitPickerSlotKind.PICKER,
                hostTaskId = event.hostTaskId,
            ),
        )
        val actions = if (appTaskId in event.observedTaskIds) {
            listOf(SplitPickerAction.RemoveExactTask(appTaskId, packageName))
        } else {
            emptyList()
        }
        return SplitPickerReduction(next, actions)
    }

    private fun pickerTaskGone(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent.PickerTaskGone,
    ): SplitPickerReduction {
        val existing = current.slot(event.pane)
        if (existing.hostTaskId != event.hostTaskId ||
            existing.kind == SplitPickerSlotKind.CLOSED
        ) {
            return unchanged(current)
        }
        // A hidden picker Activity can be destroyed for memory/configuration reasons while its
        // separately-tasked app is still visible. Only a visible PICKER (or a projected vacancy)
        // represents the user's pane-dismiss gesture.
        if (existing.kind == SplitPickerSlotKind.APP ||
            existing.kind == SplitPickerSlotKind.ATTACHING
        ) {
            return unchanged(current)
        }
        if (existing.kind == SplitPickerSlotKind.PROJECTED ||
            existing.kind == SplitPickerSlotKind.PROJECTED_ATTACHING
        ) {
            return SplitPickerReduction(
                state = current.withSlot(
                    event.pane,
                    existing.copy(
                        hostTaskId = null,
                        userClosedWhileProjected = true,
                    ),
                ),
                actions = listOf(SplitPickerAction.RemovePickerArtifact(event.hostTaskId)),
            )
        }

        return SplitPickerReduction(
            state = current.withSlot(event.pane, SplitPickerSlotState()),
            actions = listOf(SplitPickerAction.RemovePickerArtifact(event.hostTaskId)),
        )
    }

    private fun projectionStarted(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent.ProjectionStarted,
    ): SplitPickerReduction {
        val existing = current.slot(event.pane)
        if (existing.kind != SplitPickerSlotKind.APP || existing.appTaskId != event.taskId) {
            return unchanged(current)
        }
        // The navigator now belongs to the instrument-display session, not to the IVI pane.
        // Its permanent picker base is immediately a normal vacancy. Keeping the task encoded
        // as PROJECTED here used to reserve both pickers through the saved-pair guard and made
        // the entire split UI unusable until navigation returned.
        return unchanged(
            current.withSlot(
                event.pane,
                SplitPickerSlotState(
                    kind = SplitPickerSlotKind.PICKER,
                    hostTaskId = existing.hostTaskId,
                ),
            ),
        )
    }

    private fun projectionReturned(
        current: SplitPickerAutomatonState,
        event: SplitPickerEvent.ProjectionReturned,
    ): SplitPickerReduction {
        val existing = current.slot(event.pane)
        if (existing.kind !in setOf(
                SplitPickerSlotKind.PROJECTED,
                SplitPickerSlotKind.PROJECTED_ATTACHING,
            ) || existing.appTaskId != event.taskId
        ) {
            return unchanged(current)
        }
        val packageName = existing.packageName ?: return unchanged(current)
        if (existing.userClosedWhileProjected || existing.hostTaskId == null) {
            return SplitPickerReduction(
                state = current.withSlot(event.pane, SplitPickerSlotState()),
                actions = listOf(
                    SplitPickerAction.ReturnTaskFullscreen(event.pane, event.taskId, packageName),
                ),
            )
        }
        return unchanged(
            current.withSlot(
                event.pane,
                existing.copy(
                    kind = SplitPickerSlotKind.APP,
                    userClosedWhileProjected = false,
                ),
            ),
        )
    }

    private fun idle(
        current: SplitPickerAutomatonState,
        armed: Boolean,
    ): SplitPickerReduction = unchanged(
        current.copy(
            armed = armed,
            phase = SplitPickerPhase.IDLE,
            slots = SplitPane.entries.associateWith { SplitPickerSlotState() },
        ),
    )

    private fun SplitPickerAutomatonState.withSlot(
        pane: SplitPane,
        slot: SplitPickerSlotState,
    ): SplitPickerAutomatonState {
        val updated = slots.toMutableMap().apply { put(pane, slot) }
        return copy(
            phase = phaseFor(updated),
            slots = updated,
        )
    }

    private fun phaseFor(slots: Map<SplitPane, SplitPickerSlotState>): SplitPickerPhase {
        val values = SplitPane.entries.map { pane -> slots[pane] ?: SplitPickerSlotState() }
        if (values.any { it.kind == SplitPickerSlotKind.ATTACHING }) {
            return SplitPickerPhase.OPENING
        }
        val occupied = values.count { slot ->
            slot.kind != SplitPickerSlotKind.CLOSED &&
                !(slot.kind == SplitPickerSlotKind.PROJECTED && slot.hostTaskId == null)
        }
        return when (occupied) {
            0 -> SplitPickerPhase.IDLE
            1 -> SplitPickerPhase.FULL
            else -> SplitPickerPhase.SPLIT
        }
    }

    private fun unchanged(state: SplitPickerAutomatonState) = SplitPickerReduction(state)

    private const val MAX_ATTACH_ATTEMPTS = 3
}
