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
    SPEAKERS,
    LOCALE,
    PASSENGER,
    SERVICE,
    ;

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
            LOCALE, SERVICE -> null
        }
}
