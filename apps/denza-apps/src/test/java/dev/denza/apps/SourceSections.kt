package dev.denza.apps

import org.junit.Assert.assertTrue

/**
 * The part of a source file between two anchors, for the contract tests that read the code.
 *
 * `substringAfter` and `substringBefore` hand back the whole string when the anchor is not in it.
 * So a renamed method quietly turned «the body of `refresh`» into «the rest of the file»: a
 * `contains` went on passing on some other line - in the weather contract, on a KDoc above the
 * step it meant - and the test kept its name and lost its meaning. This fails on the missing
 * anchor instead.
 */
internal fun String.between(start: String, end: String): String {
    val from = anchor(start, 0) + start.length
    return substring(from, anchor(end, from))
}

private fun String.anchor(text: String, from: Int): Int =
    indexOf(text, from).also { assertTrue("«$text» is not in the source: the part this test reads has moved", it >= 0) }
