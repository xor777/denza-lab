package dev.denza.apps.feature.cluster.dashboard

/**
 * The stretches of a history that belong to one shape, walked without allocating one.
 *
 * Both boxes on the panel need the same question answered and neither of them is allowed to answer
 * it with a list: this runs inside `onDraw` over the vehicle's live instruments. The engine's box
 * asks *which bins were answered* - a bin nothing arrived in breaks the area rather than being drawn
 * through, because a step across a gap would claim the engine held a steady output through five
 * seconds nobody watched. The petal asks *which buckets gave energy back* - the return is drawn
 * where it happened and nowhere else, which is the whole of the eighth pass's «беспорядочно».
 *
 * It is here rather than private to the renderer so that it can be tested at all: a `Canvas` call is
 * unverifiable by construction in this module, and "the blue is only on the return buckets" is a
 * statement about runs rather than about pixels.
 */
internal object ContourRuns {

    /**
     * Calls [block] once per maximal run of indices below [count] that [keep] accepts.
     *
     * @param keep whether the value at an index belongs to a shape
     * @param block the run's first index and how many indices it covers
     */
    inline fun forEach(count: Int, keep: (Int) -> Boolean, block: (start: Int, length: Int) -> Unit) {
        var index = 0
        while (index < count) {
            if (!keep(index)) {
                index++
                continue
            }
            val start = index
            while (index < count && keep(index)) index++
            block(start, index - start)
        }
    }

    /** The same walk, collected - for a test, and for nothing that draws. */
    fun of(count: Int, keep: (Int) -> Boolean): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        forEach(count, keep) { start, length -> out += start to length }
        return out
    }
}
