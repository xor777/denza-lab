package dev.denza.apps.core

import android.content.Context

/**
 * A tile's feature in the shape the read tiles are moving to: its state is one value, [S], read
 * from the car as one slice of the dashboard's state, and its commands sit beside that reading -
 * none of it in the repository, and nothing of the rest of the state within its reach.
 *
 * Wave 1 made every tile's state a slice the publisher reads (`StateSlice`), but what each slice
 * read, and every command that changed it, still lived in `DenzaAppRepository`. A feature in this
 * shape reads itself ([read]), starts what it watches when the runtime starts ([start]), and says
 * what changed through its [SliceHandle]. The repository holds it and reads it when its slice is
 * marked; the screen's actions (`AppActions`) call its commands. «Погода» and «Язык системы» were
 * the first two, on 2026-10-09; `docs/feature-map.md`, "Adding a tile", is the recipe.
 *
 * Its tile and its panel stay where every tile's are, in `DashboardTiles` and `FeatureSheets`: the
 * Luminofor boards hold the faces as one table, and the contract tests read them as one.
 */
interface SliceFeature<S : Any> {
    /** Its state as the car has it now, read on the publisher's thread. Equal cars, equal values. */
    fun read(context: Context): S

    /** Once per runtime start, after the car trusts the app: what it watches, and a first mark. */
    fun start(context: Context) = Unit
}

/**
 * How a [SliceFeature] reaches its slice: [mark] it to be read again, or [publish] a value that is
 * not a reading - a switch's new position - in its turn on the publisher's queue, so no read taken
 * before it can put the old value back.
 */
interface SliceHandle<S : Any> {
    fun mark(cause: String)

    /** [transform] may run more than once if another writer races it, so it must be pure. */
    fun publish(cause: String, transform: (S) -> S)
}
