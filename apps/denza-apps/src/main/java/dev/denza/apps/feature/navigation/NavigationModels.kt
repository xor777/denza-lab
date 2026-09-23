package dev.denza.apps.feature.navigation

import dev.denza.apps.BuildConfig
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.feature.cluster.ClusterMapPlacement

/**
 * What the card puts on the driver's display.
 *
 * From the driver's side the two are the same shape: one choice, one button, one thing on the
 * cluster. Underneath they have nothing in common. An application is somebody else's task - opened
 * on the head unit, given a virtual display, moved onto it, and owed a return afterwards, with a
 * failure possible at every one of those steps. The dashboard is a view of ours drawn into a window
 * this app already owns: no task, no display, nothing to give back.
 */
enum class NavigationTarget {
    APPLICATION,
    DASHBOARD,
}

/**
 * The two kinds of answer to "what is on the driver's display".
 *
 * This app's own instruments, and any application [ProjectablePackages] admits - which is any the
 * car can open. The applications used to be six navigators listed here by package, and a map, a
 * player or a browser the owner installed could not reach the cluster until somebody added a line.
 * Nothing in the projection was ever about navigation; it moves a task. So there is no list, and a
 * navigator is simply one of the applications.
 */
object NavigationAppPolicy {
    /**
     * This app's own instruments, offered in the same chooser as the applications.
     *
     * It is addressed by the real application id rather than by an invented token, so a saved
     * choice and every call that carries one stays a package name. It is never looked up as an
     * application: [ProjectablePackages] leaves this package out, and the chooser draws it with the
     * instruments' own glyph under a heading of its own.
     */
    const val DASHBOARD_PACKAGE: String = BuildConfig.APPLICATION_ID

    const val DASHBOARD_LABEL = "Приборы"

    fun isDashboard(packageName: String): Boolean = packageName == DASHBOARD_PACKAGE
}

/**
 * Where the chosen thing may sit on the driver's display.
 *
 * An application is a picture, and a picture can be put wherever the stock shade leaves room. The
 * instruments are not a picture: the dial, the two corner blocks and the columns beside them are one
 * composition measured against the whole panel, so a third of it is not a smaller version of this
 * instrument - it is a different instrument, and this product does not offer that one. A choice of
 * one is not a choice, so the card shows no placement row for the dashboard at all rather than a row
 * with three dead cells in it.
 *
 * The applications' saved placement is left untouched while the dashboard is chosen: it is their
 * setting, and it is still there when an application is chosen again.
 */
object NavigationPlacementPolicy {
    fun offered(packageName: String): List<ClusterMapPlacement> =
        if (NavigationAppPolicy.isDashboard(packageName)) {
            listOf(ClusterMapPlacement.FULL)
        } else {
            ClusterMapPlacement.entries
        }

    /** The saved placement, or the only one on offer when the saved one is not among them. */
    fun resolve(packageName: String, saved: ClusterMapPlacement): ClusterMapPlacement {
        val offered = offered(packageName)
        return if (saved in offered) saved else offered.first()
    }
}

enum class NavigationPhase {
    READY,
    OPENING,
    PROJECTING,
    PROJECTED,
    RETURNING,
    RECOVERING,
    NEEDS_ACTION,
}

data class NavigationSession(
    val phase: NavigationPhase = NavigationPhase.READY,
    val target: NavigationTarget = NavigationTarget.APPLICATION,
    val taskId: Int? = null,
    val virtualDisplayId: Int? = null,
    val message: String = "",
    val details: String? = null,
    val resolution: FeatureResolution? = null,
) {
    val buttonLabel: String
        get() = if (target == NavigationTarget.DASHBOARD) dashboardLabel() else applicationLabel()

    /**
     * The dashboard is not returned anywhere - it is put on the panel or taken off it - and it has
     * no in-between step to report: showing it is one intent to a service in this same process.
     */
    private fun dashboardLabel(): String =
        if (phase == NavigationPhase.PROJECTED) "Убрать" else "На приборку"

    private fun applicationLabel(): String = when (phase) {
        NavigationPhase.PROJECTED, NavigationPhase.RETURNING -> "Вернуть"
        NavigationPhase.OPENING, NavigationPhase.PROJECTING, NavigationPhase.RECOVERING ->
            "Проверяю"
        else -> "На приборку"
    }
}

internal enum class NavigationPrimaryAction {
    PROJECT,
    RETURN,
}

/** Admission policy shared by UI and steering-wheel navigation commands. */
internal object NavigationPrimaryActionPolicy {
    fun action(
        initialized: Boolean,
        hasContext: Boolean,
        selectedAppInstalled: Boolean,
        actionPending: Boolean,
        session: NavigationSession,
    ): NavigationPrimaryAction? {
        if (!initialized || !hasContext || actionPending) return null
        if (session.target == NavigationTarget.DASHBOARD) return dashboardAction(session)
        if (session.phase == NavigationPhase.PROJECTED) {
            return NavigationPrimaryAction.RETURN
        }
        if (!selectedAppInstalled) return null
        if (
            session.phase == NavigationPhase.NEEDS_ACTION &&
            (
                session.resolution == FeatureResolution.SELECT_NAVIGATION_APP ||
                    session.resolution == FeatureResolution.SELECT_CLUSTER_DISPLAY
            )
        ) {
            return null
        }
        return when (session.phase) {
            NavigationPhase.READY,
            NavigationPhase.NEEDS_ACTION,
            -> NavigationPrimaryAction.PROJECT
            NavigationPhase.OPENING,
            NavigationPhase.PROJECTING,
            NavigationPhase.RETURNING,
            NavigationPhase.RECOVERING,
            NavigationPhase.PROJECTED,
            -> null
        }
    }

    /**
     * The dashboard has two commands and no third.
     *
     * `selectedAppInstalled` is not consulted: the app being asked about is this one, and it is
     * running the question. There is no OPEN either - nothing is launched, so there is no state in
     * which the button's job is to open something first.
     */
    private fun dashboardAction(session: NavigationSession): NavigationPrimaryAction? = when {
        session.phase == NavigationPhase.PROJECTED -> NavigationPrimaryAction.RETURN
        session.resolution == FeatureResolution.SELECT_CLUSTER_DISPLAY -> null
        session.phase == NavigationPhase.READY ||
            session.phase == NavigationPhase.NEEDS_ACTION -> NavigationPrimaryAction.PROJECT
        else -> null
    }
}

object NavigationRecovery {
    fun shouldRetryAfterClusterSelection(session: NavigationSession): Boolean =
        session.phase == NavigationPhase.NEEDS_ACTION &&
            session.resolution == FeatureResolution.SELECT_CLUSTER_DISPLAY

    fun proxyLost(session: NavigationSession): NavigationSession =
        if (session.phase == NavigationPhase.PROJECTED || session.virtualDisplayId != null) {
            session.copy(
                phase = NavigationPhase.RECOVERING,
                message = "Безопасно возвращаю приложение",
                details = "shell proxy disconnected",
                resolution = null,
            )
        } else {
            NavigationSession(message = "Соединение восстановится при запуске")
        }
}

internal sealed interface NavigationProjectionHealthDecision {
    data object Healthy : NavigationProjectionHealthDecision

    data class Uncertain(
        val actualDisplayId: Int,
        val confirmationCount: Int,
    ) : NavigationProjectionHealthDecision

    data class ConfirmedElsewhere(
        val actualDisplayId: Int,
    ) : NavigationProjectionHealthDecision
}

/**
 * A projected task may temporarily disappear from getRunningTasks while
 * DiShare creates or removes its mirror displays. A negative observation is
 * therefore never permission to release the display that still owns the task.
 * Only the same positive, different display observed twice is authoritative.
 */
internal class NavigationProjectionHealthTracker(
    private val requiredConfirmations: Int = 2,
) {
    private var candidateDisplayId: Int? = null
    private var confirmationCount = 0

    init {
        require(requiredConfirmations >= 2)
    }

    fun observe(
        actualDisplayId: Int,
        expectedDisplayId: Int,
    ): NavigationProjectionHealthDecision {
        if (actualDisplayId == expectedDisplayId) {
            reset()
            return NavigationProjectionHealthDecision.Healthy
        }
        if (actualDisplayId < 0) {
            reset()
            return NavigationProjectionHealthDecision.Uncertain(
                actualDisplayId = actualDisplayId,
                confirmationCount = 0,
            )
        }
        if (candidateDisplayId != actualDisplayId) {
            candidateDisplayId = actualDisplayId
            confirmationCount = 1
        } else {
            confirmationCount += 1
        }
        if (confirmationCount < requiredConfirmations) {
            return NavigationProjectionHealthDecision.Uncertain(
                actualDisplayId = actualDisplayId,
                confirmationCount = confirmationCount,
            )
        }
        val confirmed = actualDisplayId
        reset()
        return NavigationProjectionHealthDecision.ConfirmedElsewhere(confirmed)
    }

    fun reset() {
        candidateDisplayId = null
        confirmationCount = 0
    }
}

internal enum class NavigationProjectionCleanupDecision {
    RELEASE,
    RETURN_THEN_RELEASE,
    PRESERVE,
}

/**
 * Releasing a live app-owned display is destructive while its task may still
 * be attached. An unknown task location therefore fails closed.
 */
internal fun navigationProjectionCleanupDecision(
    ownedDisplayId: Int?,
    ownedDisplayAlive: Boolean,
    actualTaskDisplayId: Int?,
): NavigationProjectionCleanupDecision {
    if (ownedDisplayId == null || !ownedDisplayAlive) {
        return NavigationProjectionCleanupDecision.RELEASE
    }
    if (actualTaskDisplayId == null || actualTaskDisplayId < 0) {
        return NavigationProjectionCleanupDecision.PRESERVE
    }
    return if (actualTaskDisplayId == ownedDisplayId) {
        NavigationProjectionCleanupDecision.RETURN_THEN_RELEASE
    } else {
        NavigationProjectionCleanupDecision.RELEASE
    }
}
