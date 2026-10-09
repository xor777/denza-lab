package dev.denza.apps.core

/**
 * The part of the dashboard's state that a feature writes itself, and nothing else of it.
 *
 * Most tiles are read: their feature marks a slice and the publisher reads it again
 * (`DenzaStatePublisher`). Two are not. The passenger install reports its progress as it goes, and
 * the default applications are read and written on their own thread, with every write claimed
 * before it leaves - so each writes its own part of the state. Both used to do it from inside the
 * repository, with every other tile's state within reach of a typo; a cell hands a feature its own
 * fields over the same store, with the store's guarantees.
 *
 * [update] and [updateIf] may run their transform more than once when writers race, so a transform
 * must be pure: read Android before it or after it, never inside it.
 */
interface StateCell<T : Any> {
    /** The value as last committed. */
    val value: T

    fun update(transform: (T) -> T)

    /** Applies [transform] while [predicate] holds of the value as it stands; false if it did not. */
    fun updateIf(predicate: (T) -> Boolean, transform: (T) -> T): Boolean

    /**
     * Decides from the value as it stands and commits the decision only if nothing was written
     * since it was made; otherwise decides again. A claim: [decision] may run more than once, and
     * whatever the caller starts because of the answer it starts after this returns.
     */
    fun <R> decide(decision: (T) -> Decision<T, R>): R
}

/** What [StateCell.decide] commits - [value], or nothing when it is null - and what it answers. */
class Decision<out T : Any, out R>(val value: T?, val answer: R) {
    companion object {
        /** Nothing to write: the answer alone. */
        fun <R> none(answer: R): Decision<Nothing, R> = Decision(null, answer)
    }
}
