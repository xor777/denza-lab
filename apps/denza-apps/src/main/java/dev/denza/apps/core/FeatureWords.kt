package dev.denza.apps.core

/**
 * The words a feature that did not settle says on its tile, shared so that one state reads one
 * way on every tile.
 *
 * Each is a state, never an instruction: what to do about it is the press, which goes where the
 * waiting ends. They are held to the caption's budget, and kept free of the app's internal names, by
 * `TileCaptionContractTest`, which asks every producer for every caption it can write.
 */
object FeatureWords {

    /**
     * The car does not let the app in: a key it does not trust, a channel that does not answer, or
     * the app's own access to the system - overlay, accessibility, notifications - not set up.
     */
    const val NO_ACCESS = "Нет доступа"

    /** Nothing chosen yet; the press opens the choice. */
    const val NOT_CHOSEN = "Не выбрано"

    /** A feature this car does not have. */
    const val ABSENT = "Недоступно"

    /** A switch the car did not take, when the feature cannot say which way it was going. */
    const val REFUSED = "Не переключилось"

    /** A switch the car did not take, by the way it was going. */
    fun refused(enabled: Boolean): String = if (enabled) "Не включилось" else "Не выключилось"
}
