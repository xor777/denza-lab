package dev.denza.apps.feature.vehicle

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import dev.denza.apps.R

/**
 * The Material Symbols the vehicle panel draws, resolved once per view.
 *
 * The set is deliberately tiny. An icon earns its place here only when it
 * attaches to a specific number and says something the number cannot: the bolt
 * types the kilowatt figure as electrical flow, and the charging symbol marks
 * the one line that appears only with a gun in the socket. Section headings
 * stay words — "инверторы" has no glyph anyone would read correctly, and in the
 * narrow pane a marker icon would indent every row of its block to buy nothing.
 *
 * Same family as the cards above the panel, which draw `Icons.Outlined.*`.
 */
internal class VehicleIcons(context: Context) {
    val flow: Drawable? = tintable(context, R.drawable.ic_flow_bolt)
    val charging: Drawable? = tintable(context, R.drawable.ic_charging)
}

/**
 * `mutate()` matters: a vector drawable shares constant state with every other
 * instance of the same resource, so tinting one would otherwise recolour the
 * icon everywhere it is used.
 */
private fun tintable(context: Context, id: Int): Drawable? =
    ContextCompat.getDrawable(context, id)?.mutate()
