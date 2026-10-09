package dev.denza.apps.feature.navigation

import dev.denza.apps.adb.AdbProblem
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureWords
import dev.denza.apps.feature.cluster.ClusterDisplaySelection

/** A press on the driver's-screen tile that did not get where it was going, as the tile says it. */
internal data class NavigationProblem(
    val message: String,
    val resolution: FeatureResolution,
)

/** The step a press was on when it stopped, in the tile's words for it. */
internal enum class NavigationStep(val words: String) {
    /** Opening the application on the central screen, or our own instruments on the cluster. */
    OPEN("Не открылось"),

    /** Moving the application onto the cluster, or finding the task that would move. */
    PROJECT("Не перенеслось"),

    /** Bringing the application back to the central screen. */
    RETURN("Не вернулось"),
}

/**
 * Every caption the driver's-screen tile writes when it did not settle.
 *
 * They were instructions, one per failure and every one longer than the tile - «Повторите перенос
 * приложения», «Повторите поиск приборного экрана», «Выберите, что показывать», «Дождитесь запуска
 * приложения и повторите». The press already does each of those things: it opens the choice it is
 * waiting on, or tries again. So the tile says what state it is in, in a few words, and the
 * exception goes to `details`, which only the technical report reads.
 */
internal object NavigationWords {

    /** The chosen application is gone or cannot be launched; the press opens the choice. */
    val notChosen = NavigationProblem(FeatureWords.NOT_CHOSEN, FeatureResolution.SELECT_NAVIGATION_APP)

    /** A cluster screen that is unverified opens the screens' choice; one that is missing is looked for again. */
    fun display(selection: ClusterDisplaySelection): NavigationProblem = when (selection) {
        is ClusterDisplaySelection.NeedsVerification ->
            NavigationProblem("Экран не выбран", FeatureResolution.SELECT_CLUSTER_DISPLAY)
        else -> NavigationProblem("Экран не найден", FeatureResolution.RETRY)
    }

    /**
     * A step that failed. The channel's own failures read as on every tile and the press goes and
     * looks ([AdbProblem]); anything else is this step's, and the press tries it again.
     */
    fun failed(step: NavigationStep, error: Throwable?): NavigationProblem {
        val channel = AdbProblem.of(error) ?: return NavigationProblem(step.words, FeatureResolution.RETRY)
        return NavigationProblem(AdbProblem.WORDS, channel.resolution)
    }
}
