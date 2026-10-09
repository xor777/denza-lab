package dev.denza.apps.feature.split

/**
 * Explicit, command-driven split session: the live-proven recipes of one operation, behind the one
 * object the operations hold ([SplitOperationWorkspace.split]).
 *
 * This class never watches or interprets arbitrary foreground launches. Every mutation starts
 * from either the dedicated launcher or a tap in a picker, so the destination pane and expected
 * component are known before any task is moved.
 *
 * It builds the recipes from its constructor and owns none of them itself; each call below is one
 * line into the unit that holds it:
 * - [SplitWorld] - the funnel every command and settle pause goes through, the reads, the waits
 *   and the identity predicates;
 * - [SplitTaskCommands] - the task-tree commands every recipe is made of;
 * - [SplitGate] - every transition of the firmware gate a session makes, and its lease;
 * - [SplitOwnedScene] - the reads that prove a scene ours;
 * - [SplitSceneBuilder] - the scene of an open: reveal or build;
 * - [SplitSelect] - a tap in a picker;
 * - [SplitCollapse] - what a divider gesture left: a resize to repair, a collapse to prove;
 * - [SplitNavReturn] - the navigator back from the cluster;
 * - [SplitEdge] - the stock picker of an edge drag, and the synthetic divider drag;
 * - [SplitTeardown] - the toggle going off, and what an ended scene or pane leaves.
 */
internal class SplitPickerShellSession(
    shell: (String) -> String,
    apkPath: String,
    settle: (Long) -> Unit = Thread::sleep,
    gateLeaseStore: SplitGateLeaseStore,
    /** The topology reads of the operation this session belongs to ([SplitWorld]). */
    topology: SplitTopologyCache = SplitTopologyCache(),
    /** Where the shell-UID proxy is loaded from; the APK is the always-valid fallback. */
    proxyClasspath: SplitProxyClasspath = SplitProxyClasspath { apkPath },
    /** Milliseconds spent turning an answer into the model ([SplitWorld]). */
    parsed: (Long) -> Unit = {},
) {
    private val world = SplitWorld(shell, settle, topology, parsed)
    private val commands = SplitTaskCommands(world, apkPath, proxyClasspath)
    private val gate = SplitGate(world, gateLeaseStore)
    private val ownedScene = SplitOwnedScene(world)
    private val edge = SplitEdge(world, commands)
    private val builder = SplitSceneBuilder(world, commands, gate, ownedScene, edge)
    private val selection = SplitSelect(world, commands, gate)
    private val collapse = SplitCollapse(world, commands, ownedScene)
    private val navReturn = SplitNavReturn(world, commands, ownedScene, builder)
    private val teardown = SplitTeardown(world, commands, gate)

    // region the world's reads the operations ask for ([SplitWorld])

    fun livingTaskIds(): Set<Int> = world.livingTaskIds()

    fun sceneCovered(): Boolean = world.sceneCovered()

    fun fullIviRootTaskId(): Int = world.fullIviRootTaskId()

    // endregion

    // region the gate the operations drive themselves ([SplitGate])

    fun suspendOwnedGateForHome(displaced: () -> Boolean = { false }): Boolean =
        gate.suspendOwnedGateForHome(displaced)

    fun suspendOwnedGateIfCovered(): Boolean = gate.suspendOwnedGateIfCovered()

    fun resumeOwnedGateIfVisible(): Boolean = gate.resumeOwnedGateIfVisible()

    fun closeOwnedGate(): Boolean = gate.closeOwnedGate()

    // endregion

    // region the reads of our own scene ([SplitOwnedScene])

    fun existingOwnedSession(
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): Map<SplitPane, SplitPickerLivePane>? =
        ownedScene.existingOwnedSession(pickerComponents, expectedApps)

    fun readOwnedSession(
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): SplitSceneRead = ownedScene.readOwnedSession(pickerComponents, expectedApps)

    fun readOwnedSelection(pickerComponents: Set<String>): SplitSceneRead =
        ownedScene.readOwnedSelection(pickerComponents)

    fun observePickerTask(
        hostTaskId: Int,
        pickerComponents: Set<String>,
    ): SplitPickerPaneObservation? = ownedScene.observePickerTask(hostTaskId, pickerComponents)

    fun visiblePickerTaskIds(pickerComponents: Set<String>): List<Int> =
        ownedScene.visiblePickerTaskIds(pickerComponents)

    fun singleVisiblePickerTaskId(pickerComponents: Set<String>): Int? =
        ownedScene.singleVisiblePickerTaskId(pickerComponents)

    fun allRecordedMembersAlive(
        scene: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Boolean = ownedScene.allRecordedMembersAlive(scene, pickerComponents)

    fun deadRecordedApps(scene: Map<SplitPane, SplitPickerLivePane>): Set<SplitPane> =
        ownedScene.deadRecordedApps(scene)

    fun confirmSceneEndMembersDead(
        scene: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Boolean = ownedScene.confirmSceneEndMembersDead(scene, pickerComponents)

    fun confirmDeadRecordedApps(
        scene: Map<SplitPane, SplitPickerLivePane>,
    ): SplitDeadAppsConfirmation = ownedScene.confirmDeadRecordedApps(scene)

    // endregion

    // region the edge and the divider ([SplitEdge])

    fun awaitNativePickerCommit(): Boolean = edge.awaitNativePickerCommit()

    fun nativePickerMutationAllowed(): Boolean = edge.nativePickerMutationAllowed()

    fun observePane(pane: SplitPane, pickerComponents: Set<String>): SplitPickerPaneObservation =
        edge.observePane(pane, pickerComponents)

    fun attachPicker(pane: SplitPane, hostTaskId: Int, pickerComponent: String): Int =
        edge.attachPicker(pane, hostTaskId, pickerComponent)

    internal fun dragDividerToBalanced() = edge.dragDividerToBalanced()

    // endregion

    // region the scene of an open ([SplitSceneBuilder])

    fun revealOwnedSession(
        existing: Map<SplitPane, SplitPickerLivePane>,
        pickerComponents: Set<String>,
    ): Map<SplitPane, SplitPickerLivePane> =
        builder.revealOwnedSession(existing, pickerComponents)

    fun buildScene(
        pickerComponents: Map<SplitPane, String>,
        targets: Map<SplitPane, SplitLaunchTarget>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
        preexistingTaskIds: Set<Int>? = null,
        onPhase: (String) -> Unit = {},
        onTask: (SplitBuiltTask) -> Unit = {},
    ): SplitSceneBuild = builder.buildScene(
        pickerComponents = pickerComponents,
        targets = targets,
        expectedApps = expectedApps,
        preexistingTaskIds = preexistingTaskIds,
        onPhase = onPhase,
        onTask = onTask,
    )

    // endregion

    // region a tap in a picker ([SplitSelect])

    fun selectApp(
        pickerTaskId: Int,
        target: SplitLaunchTarget,
        pickerComponents: Set<String>,
    ): SplitPickerPlacement = selection.selectApp(pickerTaskId, target, pickerComponents)

    // endregion

    // region what the divider left ([SplitCollapse])

    fun reconcileDividerResize(
        pickerComponents: Set<String>,
        previousPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): Map<SplitPane, SplitPickerLivePane>? =
        collapse.reconcileDividerResize(pickerComponents, previousPanes)

    fun readCollapsedSession(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapseRead = collapse.readCollapsedSession(pickerComponents, expectedPanes)

    fun readCollapsedPaneByExistence(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapsedPaneRead =
        collapse.readCollapsedPaneByExistence(pickerComponents, expectedPanes)

    fun collapsedPaneByPanelBounds(
        pickerComponents: Set<String>,
        expectedPanes: Map<SplitPane, SplitPickerObservedPane>,
    ): SplitCollapsedPaneRead =
        collapse.collapsedPaneByPanelBounds(pickerComponents, expectedPanes)

    // endregion

    // region the navigator back from the cluster ([SplitNavReturn])

    fun prepareNavigationReturn(
        originalRootTaskId: Int,
        pickerComponents: Set<String>,
        expectedApps: Map<SplitPane, SplitPickerExpectedApp> = emptyMap(),
    ): SplitNavigationReturnPlan =
        navReturn.prepareNavigationReturn(originalRootTaskId, pickerComponents, expectedApps)

    fun verifyNavigationReturned(
        plan: SplitNavigationReturnPlan,
        taskId: Int,
        packageName: String,
        pickerComponents: Set<String>,
    ): SplitPickerPlacement =
        navReturn.verifyNavigationReturned(plan, taskId, packageName, pickerComponents)

    fun returnRecordedTaskFullscreen(pane: SplitPane, taskId: Int, packageName: String) =
        navReturn.returnRecordedTaskFullscreen(pane, taskId, packageName)

    // endregion

    // region taking down what a scene leaves ([SplitTeardown])

    fun closePickers(pickerComponents: Map<SplitPane, String>) =
        teardown.closePickers(pickerComponents)

    fun removeRecordedTask(taskId: Int, packageName: String): Boolean =
        teardown.removeRecordedTask(taskId, packageName)

    fun removePickerArtifact(taskId: Int, pickerComponents: Set<String>): Boolean =
        teardown.removePickerArtifact(taskId, pickerComponents)

    fun removePickerArtifacts(taskIds: List<Int>, pickerComponents: Set<String>): List<Int> =
        teardown.removePickerArtifacts(taskIds, pickerComponents)

    // endregion
}
