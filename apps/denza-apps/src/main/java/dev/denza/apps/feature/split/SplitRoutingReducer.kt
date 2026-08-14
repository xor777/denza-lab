package dev.denza.apps.feature.split

internal data class SplitExpectedTask(
    val id: Int?,
    val packageName: String,
    val activityName: String?,
    val preferredRootId: Int,
)

internal data class SplitVacancy(
    val rootId: Int,
    val baselineTaskIds: Set<Int>,
    val restorePlaceholderAfterRecovery: Boolean,
)

internal data class SplitPairTarget(
    val first: SplitExpectedTask,
    val second: SplitExpectedTask,
) {
    fun panes(): List<SplitExpectedTask> = listOf(first, second)
}

internal data class SplitStablePair(
    val first: SplitExpectedTask,
    val second: SplitExpectedTask,
    val lastFocusedRootId: Int?,
) {
    fun panes(): List<SplitExpectedTask> = listOf(first, second)

    fun pane(rootId: Int): SplitExpectedTask? = panes().firstOrNull {
        it.preferredRootId == rootId
    }
}

internal data class SplitRoutingMemory(
    val anchor: SplitExpectedTask? = null,
    val vacancy: SplitVacancy? = null,
    val target: SplitPairTarget? = null,
    val stablePair: SplitStablePair? = null,
)

internal data class SplitRoutingObservation(
    val area: Int,
    val firstNativeRootId: Int,
    val secondNativeRootId: Int,
    val snapshot: SplitTaskSnapshot,
    val eligiblePackages: Set<String>,
    val recovering: Boolean = false,
) {
    val nativeRootIds: Set<Int> = setOf(firstNativeRootId, secondNativeRootId)

    fun otherRoot(rootId: Int): Int? = when (rootId) {
        firstNativeRootId -> secondNativeRootId
        secondNativeRootId -> firstNativeRootId
        else -> null
    }
}

internal sealed interface SplitRoutingAction {
    data object LaunchPlaceholder : SplitRoutingAction

    data class PlaceTask(
        val taskId: Int,
        val rootId: Int,
        val promoteInPlace: Boolean,
    ) : SplitRoutingAction

    data object BalanceDivider : SplitRoutingAction

    data class ResizeTask(
        val taskId: Int,
        val bounds: SplitBounds,
    ) : SplitRoutingAction

    data object CloseOwnedGate : SplitRoutingAction
}

internal data class SplitRoutingDecision(
    val memory: SplitRoutingMemory,
    val actions: List<SplitRoutingAction> = emptyList(),
    val splitVisible: Boolean = false,
    val event: String? = null,
)

internal object SplitRoutingReducer {
    fun reduce(
        memory: SplitRoutingMemory,
        observation: SplitRoutingObservation,
    ): SplitRoutingDecision {
        homeForeground(observation)?.let {
            return SplitRoutingDecision(
                memory = if (memory.target != null) memory.withoutTransition() else memory,
                actions = listOf(SplitRoutingAction.CloseOwnedGate),
                event = "routing intent cleared by Home",
            )
        }

        interruptingTask(observation)?.let { task ->
            return SplitRoutingDecision(
                memory = memory.withoutTransition(),
                actions = listOf(SplitRoutingAction.CloseOwnedGate),
                event = "routing intent cleared by ${task.packageName}",
            )
        }

        memory.target?.let { target ->
            freshForegroundOutside(target, observation)?.let { fresh ->
                return supersedeTarget(memory, target, fresh, observation)
            }
            return convergeTarget(memory, target, observation)
        }

        val selection = selectionSurface(observation)
        if (selection != null) {
            return observeSelection(memory, observation, selection)
        }

        memory.stablePair?.let { stablePair ->
            val foreground = observation.snapshot.foregroundTask()
            if (foreground != null && foreground.isEligibleApp(observation)) {
                val rememberedPane = stablePair.panes().firstOrNull { foreground.matches(it) }
                if (
                    rememberedPane == null ||
                    foreground.rootId != rememberedPane.preferredRootId
                ) {
                    return beginStableReplacement(
                        stablePair = stablePair,
                        candidate = foreground,
                        observation = observation,
                        preferredRootId = memory.vacancy?.rootId,
                    )
                }
            }

            observedStablePair(observation, stablePair)?.let { refreshed ->
                return SplitRoutingDecision(
                    memory = SplitRoutingMemory(stablePair = refreshed),
                    splitVisible = true,
                    event = if (refreshed != stablePair) "stable native pair refreshed" else null,
                )
            }

            if (observation.area in EXPANDED_AREAS) {
                val survivor = visibleLaunchCandidate(observation)
                    ?.takeIf { task -> stablePair.panes().any { task.matches(it) } }
                if (survivor != null) {
                    val survivorRoot = stablePair.panes()
                        .firstOrNull { survivor.matches(it) }
                        ?.preferredRootId
                        ?: widerRoot(observation)
                    return SplitRoutingDecision(
                        memory = SplitRoutingMemory(
                            anchor = survivor.toExpected(survivorRoot),
                            vacancy = observation.otherRoot(survivorRoot)?.let { rootId ->
                                SplitVacancy(rootId, baselineTaskIds(observation, rootId), false)
                            },
                        ),
                        actions = listOf(SplitRoutingAction.CloseOwnedGate),
                        event = "expanded stable survivor: ${survivor.packageName}",
                    )
                }
            }

            return SplitRoutingDecision(memory = memory)
        }

        memory.anchor?.let { rememberedAnchor ->
            val anchor = resolveExpected(rememberedAnchor, observation.snapshot)
                ?.toExpected(rememberedAnchor.preferredRootId)
                ?: rememberedAnchor
            val vacancy = memory.vacancy
            val candidate = vacancy?.let {
                findCandidate(anchor, it, observation)
            }
            if (vacancy != null && candidate != null) {
                return beginPair(anchor, candidate, vacancy.rootId, observation)
            }

            if (observation.area in EXPANDED_AREAS) {
                if (
                    observation.recovering &&
                    vacancy?.restorePlaceholderAfterRecovery == true &&
                    resolveExpected(PLACEHOLDER_EXPECTATION, observation.snapshot) == null
                ) {
                    return beginPlaceholderPair(anchor, vacancy.rootId, observation)
                }
                return SplitRoutingDecision(
                    memory = SplitRoutingMemory(
                        anchor = anchor,
                        vacancy = vacancy?.copy(restorePlaceholderAfterRecovery = false),
                    ),
                    actions = listOf(SplitRoutingAction.CloseOwnedGate),
                    event = "expanded anchor preserved: ${anchor.packageName}",
                )
            }

            if (observation.area == AREA_BALANCED_SPLIT) {
                val stablePair = observedStablePair(observation, previous = null)
                if (stablePair != null || stableVisiblePair(observation)) {
                    return SplitRoutingDecision(
                        memory = SplitRoutingMemory(stablePair = stablePair),
                        splitVisible = true,
                        event = "stable native pair adopted",
                    )
                }
            }

            return SplitRoutingDecision(memory = memory)
        }

        if (observation.area == AREA_BALANCED_SPLIT && stableVisiblePair(observation)) {
            val stablePair = observedStablePair(observation, previous = null)
            return SplitRoutingDecision(
                memory = SplitRoutingMemory(stablePair = stablePair),
                splitVisible = true,
                event = "stable native pair adopted",
            )
        }

        val firstApp = visibleLaunchCandidate(observation) ?: return SplitRoutingDecision(memory)
        return beginFirstApp(firstApp, observation)
    }

    fun baseline(observation: SplitRoutingObservation): SplitRoutingMemory {
        val selection = selectionSurface(observation)
        if (selection != null) {
            val otherRootId = observation.otherRoot(selection.rootId)
            val anchor = otherRootId
                ?.let { topManagedApp(observation, it) }
                ?.toExpected(otherRootId)
            return SplitRoutingMemory(
                anchor = anchor,
                vacancy = SplitVacancy(
                    rootId = selection.rootId,
                    baselineTaskIds = baselineTaskIds(observation, selection.rootId),
                    restorePlaceholderAfterRecovery = selection.isPlaceholder(),
                ),
            )
        }

        observedStablePair(observation, previous = null)?.let { stablePair ->
            return SplitRoutingMemory(stablePair = stablePair)
        }

        if (observation.area in EXPANDED_AREAS) {
            val anchorTask = visibleLaunchCandidate(observation) ?: return SplitRoutingMemory()
            val preferredRoot = anchorTask.rootId.takeIf { it in observation.nativeRootIds }
                ?: widerRoot(observation)
            return SplitRoutingMemory(
                anchor = anchorTask.toExpected(preferredRoot),
                vacancy = SplitVacancy(
                    rootId = observation.otherRoot(preferredRoot) ?: observation.firstNativeRootId,
                    baselineTaskIds = emptySet(),
                    restorePlaceholderAfterRecovery = false,
                ),
            )
        }
        return SplitRoutingMemory()
    }

    private fun convergeTarget(
        memory: SplitRoutingMemory,
        original: SplitPairTarget,
        observation: SplitRoutingObservation,
    ): SplitRoutingDecision {
        val selection = selectionSurface(observation)
        val selectionPane = selection?.let { surface ->
            original.panes().firstOrNull { it.preferredRootId == surface.rootId }
        }
        val companionPane = selectionPane?.let { pane ->
            original.panes().firstOrNull { it !== pane }
        }
        if (selectionPane != null && companionPane != null) {
            val replacement = foregroundLaunchCandidateExcluding(companionPane, observation)
            if (replacement != null && !replacement.matches(selectionPane)) {
                return beginPair(
                    anchor = companionPane,
                    candidate = replacement,
                    vacancyRootId = selectionPane.preferredRootId,
                    observation = observation,
                    baseMemory = memory,
                )
            }
        }
        val placeholderPane = original.panes().firstOrNull { it.isPlaceholder() }
        val appPane = original.panes().firstOrNull { !it.isPlaceholder() }
        if (placeholderPane != null && appPane != null) {
            val replacement = foregroundLaunchCandidateExcluding(appPane, observation)
            if (replacement != null && !replacement.matches(placeholderPane)) {
                return beginPair(
                    anchor = appPane,
                    candidate = replacement,
                    vacancyRootId = placeholderPane.preferredRootId,
                    observation = observation,
                    baseMemory = memory,
                )
            }
        }

        val firstTask = resolveExpected(original.first, observation.snapshot)
        val secondTask = resolveExpected(original.second, observation.snapshot)
        val target = SplitPairTarget(
            first = firstTask?.toExpected(original.first.preferredRootId) ?: original.first,
            second = secondTask?.toExpected(original.second.preferredRootId) ?: original.second,
        )
        val nextMemory = memory.copy(target = target)

        if (firstTask == null || secondTask == null) {
            val missingPlaceholder = target.panes().any { pane ->
                pane.packageName == DENZA_PACKAGE &&
                    pane.activityName == PLACEHOLDER_ACTIVITY &&
                    resolveExpected(pane, observation.snapshot) == null
            }
            if (missingPlaceholder) {
                return SplitRoutingDecision(
                    memory = nextMemory,
                    actions = listOf(SplitRoutingAction.LaunchPlaceholder),
                    event = "placeholder required",
                )
            }
            return abandonMissingTarget(
                memory = memory,
                target = target,
                firstTask = firstTask,
                secondTask = secondTask,
                observation = observation,
            )
        }

        val actual = listOf(firstTask to target.first, secondTask to target.second)
        val misplaced = actual.filter { (task, expected) ->
            task.rootId != expected.preferredRootId
        }
        if (misplaced.isNotEmpty()) {
            return SplitRoutingDecision(
                memory = nextMemory,
                actions = misplaced.map { (task, expected) ->
                    SplitRoutingAction.PlaceTask(
                        taskId = task.id,
                        rootId = expected.preferredRootId,
                        promoteInPlace = false,
                    )
                },
                event = "placing ${misplaced.joinToString { it.first.packageName }}",
            )
        }

        val obscured = actual.filter { (task, expected) ->
            !isTopExpected(task, expected, observation)
        }
        if (obscured.isNotEmpty()) {
            return SplitRoutingDecision(
                memory = nextMemory,
                actions = obscured.map { (task, expected) ->
                    SplitRoutingAction.PlaceTask(
                        taskId = task.id,
                        rootId = expected.preferredRootId,
                        promoteInPlace = true,
                    )
                },
                event = "promoting ${obscured.joinToString { it.first.packageName }}",
            )
        }

        if (observation.area != AREA_BALANCED_SPLIT) {
            return SplitRoutingDecision(
                memory = nextMemory,
                actions = listOf(SplitRoutingAction.BalanceDivider),
                event = "balancing native divider",
            )
        }

        val staleBounds = actual.mapNotNull { (task, expected) ->
            val rootBounds = observation.snapshot.root(expected.preferredRootId)?.bounds
                ?: return@mapNotNull null
            if (task.bounds == rootBounds) null else task to rootBounds
        }
        if (staleBounds.isNotEmpty()) {
            return SplitRoutingDecision(
                memory = nextMemory,
                actions = staleBounds.map { (task, bounds) ->
                    SplitRoutingAction.ResizeTask(task.id, bounds)
                },
                event = "resizing ${staleBounds.joinToString { it.first.packageName }}",
            )
        }

        val completedPlaceholderPane = target.panes().firstOrNull { it.isPlaceholder() }
        val completedMemory = if (completedPlaceholderPane != null) {
            val anchor = target.panes().first { !it.isPlaceholder() }
            SplitRoutingMemory(
                anchor = anchor,
                vacancy = SplitVacancy(
                    rootId = completedPlaceholderPane.preferredRootId,
                    baselineTaskIds = baselineTaskIds(
                        observation,
                        completedPlaceholderPane.preferredRootId,
                    ),
                    restorePlaceholderAfterRecovery = true,
                ),
            )
        } else {
            SplitRoutingMemory(
                stablePair = SplitStablePair(
                    first = firstTask.toExpected(target.first.preferredRootId),
                    second = secondTask.toExpected(target.second.preferredRootId),
                    lastFocusedRootId = focusedNativeRoot(observation)
                        ?: memory.stablePair?.lastFocusedRootId
                        ?: target.second.preferredRootId,
                ),
            )
        }
        return SplitRoutingDecision(
            memory = completedMemory,
            splitVisible = true,
            event = "target pair confirmed",
        )
    }

    private fun observeSelection(
        memory: SplitRoutingMemory,
        observation: SplitRoutingObservation,
        selection: SplitTask,
    ): SplitRoutingDecision {
        val existingVacancy = memory.vacancy?.takeIf { it.rootId == selection.rootId }
        val otherRootId = observation.otherRoot(selection.rootId)
        val rememberedAnchor = memory.anchor
            ?.let { expected ->
                resolveExpected(expected, observation.snapshot)
                    ?.toExpected(expected.preferredRootId)
                    ?: expected
            }
        val visibleAnchor = otherRootId
            ?.let { topManagedApp(observation, it) }
            ?.toExpected(otherRootId)
        val anchor = rememberedAnchor ?: visibleAnchor
        val vacancy = SplitVacancy(
            rootId = selection.rootId,
            baselineTaskIds = existingVacancy?.baselineTaskIds
                ?: baselineTaskIds(observation, selection.rootId),
            restorePlaceholderAfterRecovery =
                existingVacancy?.restorePlaceholderAfterRecovery == true ||
                    selection.isPlaceholder(),
        )

        if (anchor != null && existingVacancy != null) {
            val candidate = findCandidate(anchor, vacancy, observation)
            if (candidate != null) {
                return beginPair(
                    anchor = anchor,
                    candidate = candidate,
                    vacancyRootId = vacancy.rootId,
                    observation = observation,
                    baseMemory = memory,
                )
            }
        }

        return SplitRoutingDecision(
            memory = SplitRoutingMemory(
                anchor = anchor,
                vacancy = vacancy,
                stablePair = memory.stablePair,
            ),
            splitVisible = observation.area == AREA_BALANCED_SPLIT,
            event = "selection surface observed in root ${selection.rootId}",
        )
    }

    private fun supersedeTarget(
        memory: SplitRoutingMemory,
        target: SplitPairTarget,
        fresh: SplitTask,
        observation: SplitRoutingObservation,
    ): SplitRoutingDecision {
        memory.stablePair?.let { stablePair ->
            return beginStableReplacement(stablePair, fresh, observation)
        }

        val selection = selectionSurface(observation)
        if (selection != null) {
            val replaced = target.panes().firstOrNull { it.preferredRootId == selection.rootId }
            val companion = replaced?.let { pane -> target.panes().firstOrNull { it !== pane } }
            val resolvedCompanion = companion?.let {
                resolveExpected(it, observation.snapshot)?.toExpected(it.preferredRootId)
            }
            if (resolvedCompanion != null) {
                return beginPair(
                    anchor = resolvedCompanion,
                    candidate = fresh,
                    vacancyRootId = selection.rootId,
                    observation = observation,
                )
            }
        }

        val placeholder = target.panes().firstOrNull { it.isPlaceholder() }
        val companion = placeholder?.let { pane -> target.panes().firstOrNull { it !== pane } }
        val resolvedCompanion = companion?.let {
            resolveExpected(it, observation.snapshot)?.toExpected(it.preferredRootId)
        }
        if (placeholder != null && resolvedCompanion != null) {
            return beginPair(
                anchor = resolvedCompanion,
                candidate = fresh,
                vacancyRootId = placeholder.preferredRootId,
                observation = observation,
            )
        }

        val baseline = baseline(observation)
        val freshMemory = if (baseline.anchor?.packageName == fresh.packageName) {
            baseline
        } else {
            val preferredRoot = fresh.rootId.takeIf { it in observation.nativeRootIds }
                ?: widerRoot(observation)
            SplitRoutingMemory(anchor = fresh.toExpected(preferredRoot))
        }
        return SplitRoutingDecision(
            memory = freshMemory,
            actions = if (observation.area in EXPANDED_AREAS) {
                listOf(SplitRoutingAction.CloseOwnedGate)
            } else {
                emptyList()
            },
            splitVisible = observation.area == AREA_BALANCED_SPLIT && stableVisiblePair(observation),
            event = "stale target replaced by ${fresh.packageName}",
        )
    }

    private fun abandonMissingTarget(
        memory: SplitRoutingMemory,
        target: SplitPairTarget,
        firstTask: SplitTask?,
        secondTask: SplitTask?,
        observation: SplitRoutingObservation,
    ): SplitRoutingDecision {
        memory.stablePair
            ?.takeIf { stablePair ->
                stablePair.panes().all { resolveExpected(it, observation.snapshot) != null }
            }
            ?.let { stablePair ->
                return SplitRoutingDecision(
                    memory = SplitRoutingMemory(stablePair = stablePair),
                    event = "replacement abandoned because a target member disappeared",
                )
            }

        val present = firstTask ?: secondTask
        val missing = if (firstTask == null) target.first else target.second
        if (present == null) {
            return SplitRoutingDecision(
                memory = baseline(observation),
                event = "target abandoned because both members disappeared",
            )
        }

        val presentExpected = if (present.rootId in observation.nativeRootIds) {
            present.toExpected(present.rootId)
        } else {
            val original = if (firstTask != null) target.first else target.second
            present.toExpected(original.preferredRootId)
        }
        val vacancyRoot = observation.otherRoot(presentExpected.preferredRootId)
            ?: missing.preferredRootId.takeIf { it in observation.nativeRootIds }
        return SplitRoutingDecision(
            memory = SplitRoutingMemory(
                anchor = presentExpected,
                vacancy = vacancyRoot?.let {
                    SplitVacancy(
                        rootId = it,
                        baselineTaskIds = baselineTaskIds(observation, it),
                        restorePlaceholderAfterRecovery = false,
                    )
                },
            ),
            event = "target abandoned because ${missing.packageName} disappeared",
        )
    }

    private fun beginStableReplacement(
        stablePair: SplitStablePair,
        candidate: SplitTask,
        observation: SplitRoutingObservation,
        preferredRootId: Int? = null,
    ): SplitRoutingDecision {
        val rememberedPane = stablePair.panes().firstOrNull { candidate.matches(it) }
        val explicitDestinationRoot = selectionSurface(observation)?.rootId
        val lastFocusedRootId = stablePair.lastFocusedRootId
        val destinationRoot = when {
            explicitDestinationRoot != null -> explicitDestinationRoot
            preferredRootId != null && preferredRootId in observation.nativeRootIds ->
                preferredRootId
            rememberedPane != null -> rememberedPane.preferredRootId
            lastFocusedRootId != null && lastFocusedRootId in observation.nativeRootIds ->
                observation.otherRoot(lastFocusedRootId)
                    ?: widerRoot(observation)
            else -> widerRoot(observation)
        }
        val anchorRoot = observation.otherRoot(destinationRoot)
            ?: return SplitRoutingDecision(memory = SplitRoutingMemory(stablePair = stablePair))
        val anchor = stablePair.pane(anchorRoot)
            ?: return SplitRoutingDecision(memory = SplitRoutingMemory(stablePair = stablePair))
        return beginPair(
            anchor = anchor,
            candidate = candidate,
            vacancyRootId = destinationRoot,
            observation = observation,
            baseMemory = SplitRoutingMemory(stablePair = stablePair),
        )
    }

    private fun beginFirstApp(
        app: SplitTask,
        observation: SplitRoutingObservation,
    ): SplitRoutingDecision {
        val appRoot = app.rootId.takeIf { it in observation.nativeRootIds }
            ?: widerRoot(observation)
        val placeholderRoot = observation.otherRoot(appRoot) ?: observation.firstNativeRootId
        return beginTarget(
            SplitPairTarget(
                first = app.toExpected(appRoot),
                second = PLACEHOLDER_EXPECTATION.copy(preferredRootId = placeholderRoot),
            ),
            observation,
            event = "first app target: ${app.packageName}",
        )
    }

    private fun beginPlaceholderPair(
        anchor: SplitExpectedTask,
        vacancyRootId: Int,
        observation: SplitRoutingObservation,
    ): SplitRoutingDecision = beginTarget(
        SplitPairTarget(
            first = anchor.copy(
                preferredRootId = observation.otherRoot(vacancyRootId)
                    ?: anchor.preferredRootId,
            ),
            second = PLACEHOLDER_EXPECTATION.copy(preferredRootId = vacancyRootId),
        ),
        observation,
        event = "restoring placeholder pair",
    )

    private fun beginPair(
        anchor: SplitExpectedTask,
        candidate: SplitTask,
        vacancyRootId: Int,
        observation: SplitRoutingObservation,
        baseMemory: SplitRoutingMemory = SplitRoutingMemory(),
    ): SplitRoutingDecision {
        val anchorRootId = observation.otherRoot(vacancyRootId)
            ?: anchor.preferredRootId
        return beginTarget(
            SplitPairTarget(
                first = anchor.copy(preferredRootId = anchorRootId),
                second = candidate.toExpected(vacancyRootId),
            ),
            observation,
            event = "pair target: ${anchor.packageName} + ${candidate.packageName}",
            baseMemory = baseMemory,
        )
    }

    private fun beginTarget(
        target: SplitPairTarget,
        observation: SplitRoutingObservation,
        event: String,
        baseMemory: SplitRoutingMemory = SplitRoutingMemory(),
    ): SplitRoutingDecision = convergeTarget(
        memory = baseMemory.copy(anchor = null, vacancy = null, target = target),
        original = target,
        observation = observation,
    ).copy(event = event)

    private fun findCandidate(
        anchor: SplitExpectedTask,
        vacancy: SplitVacancy,
        observation: SplitRoutingObservation,
    ): SplitTask? {
        val anchorTask = resolveExpected(anchor, observation.snapshot)
        val topCandidates = observation.snapshot.roots.asSequence()
            .filter { it.displayId == 0 }
            .mapNotNull(::topTask)
            .filter { task -> task.isEligibleApp(observation) && !task.matches(anchor) }
            .toList()
        topCandidates.firstOrNull { candidate ->
            anchorTask != null && candidate.rootId == anchorTask.rootId
        }?.let { return it }
        topCandidates.firstOrNull { it.rootId !in observation.nativeRootIds }
            ?.let { return it }

        return observation.snapshot.root(vacancy.rootId)
            ?.tasks
            ?.asSequence()
            ?.filter { it.isEligibleApp(observation) && !it.matches(anchor) }
            ?.firstOrNull { it.id !in vacancy.baselineTaskIds }
    }

    private fun visibleLaunchCandidate(observation: SplitRoutingObservation): SplitTask? =
        observation.snapshot.roots.asSequence()
            .filter { it.displayId == 0 && it.activityType != "home" }
            .mapNotNull(::topTask)
            .firstOrNull { it.isEligibleApp(observation) }

    private fun foregroundLaunchCandidateExcluding(
        expected: SplitExpectedTask,
        observation: SplitRoutingObservation,
    ): SplitTask? = observation.snapshot.foregroundTask()
        ?.takeIf { task ->
            task.isEligibleApp(observation) && !task.matches(expected)
        }

    private fun freshForegroundOutside(
        target: SplitPairTarget,
        observation: SplitRoutingObservation,
    ): SplitTask? = observation.snapshot.foregroundTask()
        ?.takeIf { foreground ->
            foreground.isEligibleApp(observation) &&
                target.panes().none { expected -> foreground.matches(expected) }
        }

    private fun homeForeground(observation: SplitRoutingObservation): SplitTask? {
        val topRootAndTask = observation.snapshot.roots.asSequence()
            .filter { it.displayId == 0 }
            .mapNotNull { root -> topTask(root)?.let { root to it } }
            .firstOrNull()
            ?: return null
        return topRootAndTask.second.takeIf { topRootAndTask.first.activityType == "home" }
    }

    private fun stableVisiblePair(observation: SplitRoutingObservation): Boolean =
        observation.nativeRootIds.all { rootId ->
            val root = observation.snapshot.root(rootId) ?: return@all false
            val top = topTask(root) ?: return@all false
            top.isManagedMember(observation) && top.bounds == root.bounds
        }

    private fun observedStablePair(
        observation: SplitRoutingObservation,
        previous: SplitStablePair?,
    ): SplitStablePair? {
        if (observation.area != AREA_BALANCED_SPLIT) return null
        val firstRoot = observation.snapshot.root(observation.firstNativeRootId) ?: return null
        val secondRoot = observation.snapshot.root(observation.secondNativeRootId) ?: return null
        val firstTask = topTask(firstRoot)
            ?.takeIf { it.isEligibleApp(observation) && it.bounds == firstRoot.bounds }
            ?: return null
        val secondTask = topTask(secondRoot)
            ?.takeIf { it.isEligibleApp(observation) && it.bounds == secondRoot.bounds }
            ?: return null
        return SplitStablePair(
            first = firstTask.toExpected(firstRoot.id),
            second = secondTask.toExpected(secondRoot.id),
            lastFocusedRootId = focusedNativeRoot(observation)
                ?: previous?.lastFocusedRootId?.takeIf { it in observation.nativeRootIds },
        )
    }

    private fun focusedNativeRoot(observation: SplitRoutingObservation): Int? =
        observation.snapshot.foregroundTask()
            ?.takeIf { task ->
                task.rootId in observation.nativeRootIds && task.isEligibleApp(observation)
            }
            ?.rootId

    private fun selectionSurface(observation: SplitRoutingObservation): SplitTask? =
        observation.nativeRootIds.asSequence()
            .mapNotNull { observation.snapshot.root(it) }
            .mapNotNull(::topTask)
            .firstOrNull { it.isSelectionSurface() }

    private fun topManagedApp(
        observation: SplitRoutingObservation,
        rootId: Int,
    ): SplitTask? = observation.snapshot.root(rootId)
        ?.let(::topTask)
        ?.takeIf { it.isEligibleApp(observation) }

    private fun baselineTaskIds(
        observation: SplitRoutingObservation,
        rootId: Int,
    ): Set<Int> = observation.snapshot.root(rootId)
        ?.tasks
        .orEmpty()
        .asSequence()
        .filter { it.isEligibleApp(observation) }
        .mapTo(mutableSetOf(), SplitTask::id)

    private fun resolveExpected(
        expected: SplitExpectedTask,
        snapshot: SplitTaskSnapshot,
    ): SplitTask? {
        val tasks = snapshot.roots.asSequence().flatMap { it.tasks.asSequence() }
        expected.id?.let { expectedId ->
            tasks.firstOrNull { it.id == expectedId && it.packageName == expected.packageName }
                ?.let { return it }
        }
        return snapshot.roots.asSequence()
            .flatMap { it.tasks.asSequence() }
            .filter { it.packageName == expected.packageName }
            .filter { task ->
                expected.activityName == null || task.activityName == expected.activityName
            }
            .sortedByDescending { it.isTop }
            .firstOrNull()
    }

    private fun isTopExpected(
        task: SplitTask,
        expected: SplitExpectedTask,
        observation: SplitRoutingObservation,
    ): Boolean {
        val top = observation.snapshot.root(expected.preferredRootId)
            ?.let(::topTask)
            ?: return false
        if (expected.isPlaceholder() && top.isSelectionSurface()) return true
        return top.id == task.id && top.packageName == task.packageName
    }

    private fun interruptingTask(observation: SplitRoutingObservation): SplitTask? {
        val foreground = observation.snapshot.foregroundTask() ?: return null
        if (foreground.isSelectionSurface() || foreground.packageName in TRANSIENT_PACKAGES) {
            return null
        }
        if (
            foreground.packageName == DENZA_PACKAGE &&
            foreground.activityName != PLACEHOLDER_ACTIVITY
        ) {
            return foreground
        }
        return foreground.takeIf { it.packageName !in observation.eligiblePackages }
    }

    private fun widerRoot(observation: SplitRoutingObservation): Int =
        observation.nativeRootIds.maxByOrNull { rootId ->
            observation.snapshot.root(rootId)?.bounds?.width() ?: -1
        } ?: observation.secondNativeRootId

    private fun topTask(root: SplitRootTask): SplitTask? =
        root.tasks.firstOrNull(SplitTask::isTop)

    private fun SplitTask.toExpected(rootId: Int): SplitExpectedTask = SplitExpectedTask(
        id = id,
        packageName = packageName,
        activityName = activityName,
        preferredRootId = rootId,
    )

    private fun SplitTask.matches(expected: SplitExpectedTask): Boolean =
        (expected.id != null && id == expected.id && packageName == expected.packageName) ||
            packageName == expected.packageName

    private fun SplitTask.isEligibleApp(observation: SplitRoutingObservation): Boolean =
        packageName in observation.eligiblePackages

    private fun SplitTask.isManagedMember(observation: SplitRoutingObservation): Boolean =
        isEligibleApp(observation) || isPlaceholder()

    private fun SplitTask.isSelectionSurface(): Boolean =
        activityName == PICKER_ACTIVITY || isPlaceholder()

    private fun SplitTask.isPlaceholder(): Boolean =
        packageName == DENZA_PACKAGE && activityName == PLACEHOLDER_ACTIVITY

    private fun SplitExpectedTask.isPlaceholder(): Boolean =
        packageName == DENZA_PACKAGE && activityName == PLACEHOLDER_ACTIVITY

    private fun SplitRoutingMemory.withoutTransition(): SplitRoutingMemory =
        stablePair?.let { SplitRoutingMemory(stablePair = it) } ?: SplitRoutingMemory()

    private const val AREA_BALANCED_SPLIT = 3
    private val EXPANDED_AREAS = setOf(1, 2, 4)
    private const val DENZA_PACKAGE = "dev.denza.apps"
    private const val PLACEHOLDER_ACTIVITY =
        "dev.denza.apps.feature.split.SplitPlaceholderActivity"
    private const val PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
    private val TRANSIENT_PACKAGES = setOf("com.android.launcher3")
    private val PLACEHOLDER_EXPECTATION = SplitExpectedTask(
        id = null,
        packageName = DENZA_PACKAGE,
        activityName = PLACEHOLDER_ACTIVITY,
        preferredRootId = -1,
    )
}

internal interface SplitRoutingStateStore {
    fun load(): SplitRoutingMemory
    fun save(memory: SplitRoutingMemory)
    fun clear()
}
