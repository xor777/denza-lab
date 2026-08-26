package dev.denza.apps.ui.dashboard

import dev.denza.apps.core.FeatureId

/**
 * What the dashboard has tiles for.
 *
 * Not the same list as [FeatureId], and the difference is the point. [FeatureId] names the things
 * the runtime coordinates - the ones with a snapshot, a desired state and a coordinator behind them.
 * A tile is anything the driver should be able to reach from the main screen, and some of those are
 * a switch the car already owns (the button on the wheel), a setting inside the stock UI (Russian),
 * or a door rather than a feature at all (service).
 *
 * The first cut used [FeatureId] for both, which worked exactly until the dashboard needed a tile
 * for something the runtime does not model as a feature - and the answer would have been to add
 * fake features to the core enum so the screen could have somewhere to put them.
 */
enum class TileId {
    CLUSTER,
    SIMULCAST,
    MIRRORS,
    SPLIT,
    HUD,
    WEATHER,
    SPEAKERS,
    LOCALE,
    PASSENGER,
    SERVICE,
    ;

    /**
     * Whether a long press on this tile has anywhere to go.
     *
     * Four of the ten. The rest are a single switch whose whole configuration is the press that
     * flips it, or a door that opens its own thing on a short press - and a settings sheet holding
     * one switch the tile has already got is a sheet that teaches the driver the gesture is
     * usually empty. A tile that answers this true wears the press-and-hold mark; the others do
     * not, and their long press does nothing.
     *
     * Split screen is here because its press was given to the thing a driver actually wants from
     * that tile - splitting the screen - which left the launcher icon's switch with nowhere else
     * to live. That is the shape this pair of gestures is for: the useful action in reach, the
     * housekeeping behind a hold.
     */
    val configurable: Boolean
        get() = this == CLUSTER || this == SIMULCAST || this == MIRRORS || this == SPLIT

    /** The runtime feature behind this tile, when there is one. */
    val feature: FeatureId?
        get() = when (this) {
            CLUSTER -> FeatureId.NAVIGATION
            SIMULCAST -> FeatureId.SIMULCAST
            MIRRORS -> FeatureId.MIRRORS
            SPLIT -> FeatureId.SPLIT_SCREEN
            HUD -> FeatureId.HUD_GUIDANCE
            SPEAKERS -> FeatureId.SPEAKER_COVERS
            PASSENGER -> FeatureId.FSE_INSTALLER
            LOCALE, WEATHER, SERVICE -> null
        }
}
