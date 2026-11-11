package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.R
import dev.denza.apps.core.AppDecimal
import dev.denza.apps.core.AppText

/**
 * Pieces of the Contour's sentences that nothing drawn reads. The contract tests put the board's
 * phrases back together out of them, cut from the app's own strings so that each stays one record.
 */

/** The unit inside [ContourReadout.tripKm]. */
internal val ContourReadout.UNIT_KM: String
    get() = tripKm("").trim().substringBefore(" ")

/** The tail of [ContourReadout.tripKm], «· ЗА ПОЕЗДКУ». */
internal val ContourReadout.CAPTION_TRIP: String
    get() = tripKm("").substringAfter(UNIT_KM).trim()

/**
 * The petal's unit with the window full, which is what the board prints.
 *
 * The drawn form is [ContourReadout.perHundredKm]. The distance itself is
 * [dev.denza.apps.feature.vehicle.ConsumptionWindow.KM], and `ContourFixturesContractTest` holds the
 * two together.
 */
internal val ContourReadout.UNIT_PER_100KM: String
    get() = AppText.s(R.string.contour_per_100km, "10")

/** What joins the unit to the window it is true over, inside `contour_per_100km`. */
internal val ContourReadout.SEPARATOR: String get() = " · "

/** The two pieces in front of the distance. */
internal val ContourReadout.UNIT_PER_100KM_PREFIX: String
    get() = AppText.s(R.string.contour_per_100km).substringBefore("%1\$s")
internal val ContourReadout.UNIT_PER_100KM_UNIT: String
    get() = UNIT_PER_100KM_PREFIX.substringBefore(SEPARATOR)

/**
 * The widest the unit ever is, which is a distance still filling the window.
 *
 * Jura's figures are not tabular - «1» is 0.37 em and «0» 0.64 - so the template is written in the
 * widest digit rather than in a likely one. `ContourGeometryTest` holds it inside the petal's cut-out
 * behind the widest figure the petal prints.
 */
internal val ContourReadout.UNIT_PER_100KM_FILLING: String
    get() = AppText.s(R.string.contour_per_100km, AppDecimal.tenths(0))
