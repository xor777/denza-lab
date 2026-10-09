package dev.denza.apps

/**
 * Where a writer says that a slice of the dashboard's state has changed.
 *
 * A slice is read again only when it is marked (see [DenzaStatePublisher]), so a write that does
 * not mark leaves its tile showing the old value until the next resume. The marks are therefore
 * made here, as part of the write - in the setter of the field the slice reads, wherever that is a
 * plain field - and a test can connect a recorder and hold the write to its mark.
 *
 * The repository connects its publisher as it is created. A mark made before that has nothing to
 * read it and is dropped: the repository reads every slice when it starts.
 */
object StateMarks {
    @Volatile
    private var sink: ((Set<StateSlice>, String) -> Unit)? = null

    /** Where marks go from now on: the publisher in the app, a recorder in a test, or nowhere. */
    internal fun connect(sink: ((Set<StateSlice>, String) -> Unit)?) {
        this.sink = sink
    }

    /** [slice] changed because of [cause]. */
    fun mark(slice: StateSlice, cause: String) {
        sink?.invoke(setOf(slice), cause)
    }

    /** Every slice in [slices] changed because of [cause]. */
    fun mark(slices: Set<StateSlice>, cause: String) {
        if (slices.isNotEmpty()) sink?.invoke(slices, cause)
    }

    /**
     * This app's accessibility service connected or went away: everything that reads it
     * ([StateSlice.ACCESSIBILITY]) is read again.
     */
    fun accessibilityChanged(cause: String) {
        mark(StateSlice.ACCESSIBILITY, cause)
    }
}
