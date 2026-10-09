package dev.denza.apps.ui.dashboard

import dev.denza.apps.AdbAccessReading
import dev.denza.apps.ClusterDisplayReading
import dev.denza.apps.CloudLinkReading
import dev.denza.apps.DenzaUiState
import dev.denza.apps.FeatureSlices
import dev.denza.apps.HudGuidanceReading
import dev.denza.apps.MirrorsReading
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.NavigationReading
import dev.denza.apps.SimulcastReading
import dev.denza.apps.SliceReading
import dev.denza.apps.SpeakerCoversReading
import dev.denza.apps.SplitScreenReading
import dev.denza.apps.StateSlice
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbRescueSnapshot
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.locale.SystemLanguageSnapshot
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.weather.WeatherSnapshot
import dev.denza.apps.withReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Every tile reads what a slice lays, so marking that slice moves it.
 *
 * Until wave 1 every event recomputed the whole dashboard, so a tile whose feature never said it
 * had changed was still redrawn by somebody else's event. Now a feature marks its own slice, and a
 * tile reading a field no slice lays would show its first value forever. This holds every tile to
 * one of two answers: its slice lays exactly what it reads, or it is published by its own path and
 * is named here as such.
 *
 * Half of "no tile freezes", not all of it: the other half is that every writer marks, which this
 * cannot see. `StateMarksTest` holds the writers that run on the JVM; the rest are listed in
 * docs/feature-map.md, "Shared plumbing".
 */
class TileSliceContractTest {

    /** Tiles whose state no slice reads: each is published by the one path that changes it. */
    private val publishedByTheirOwnPath = mapOf(
        // The passenger install reports its own progress as it goes, on the install's thread.
        TileId.PASSENGER to "FseInstallRuntime.install",
        // The roles are read and written on the default-apps thread, each write claimed.
        TileId.DEFAULT_APPS to "DefaultAppsRuntime.refresh",
        // A door: its count is the other tiles', and its report is built while it is open.
        TileId.SERVICE to "ServiceReport",
    )

    /** Tiles with no runtime feature that a slice still reads. */
    private val readWithoutAFeature = mapOf(
        TileId.WEATHER to StateSlice.WEATHER,
        TileId.LOCALE to StateSlice.SYSTEM_LANGUAGE,
    )

    @Test
    fun everyRuntimeFeatureIsReadBySliceOrPublishesItself() {
        val unread = FeatureId.entries.filter { StateSlice.of(it) == null }
        assertEquals(listOf(FeatureId.FSE_INSTALLER), unread)
        assertEquals(TileId.PASSENGER.feature, FeatureId.FSE_INSTALLER)
    }

    @Test
    fun everyTileIsEitherReadByASliceOrNamedAsPublishingItself() {
        val readBySlice = TileId.entries.filter { sliceOf(it) != null }.toSet()
        assertEquals(TileId.entries.toSet(), readBySlice + publishedByTheirOwnPath.keys)
        assertEquals(emptySet<TileId>(), readBySlice intersect publishedByTheirOwnPath.keys)
    }

    @Test
    fun aMarkedSliceMovesTheTileThatReadsIt() {
        val base = DenzaUiState()
        TileId.entries.forEach { tile ->
            val slice = sliceOf(tile) ?: return@forEach
            val read = base.withReadings(listOf(changedReading(slice)))

            assertNotEquals(
                "$tile reads nothing $slice lays: marking $slice would leave it as it was",
                face(tile, base),
                face(tile, read),
            )
            tile.feature?.let { feature ->
                assertEquals(
                    "$tile reads the snapshot of ${feature.name}, and $slice lays it",
                    marked(feature),
                    DashboardPress.snapshotOf(tile, read),
                )
            }
        }
    }

    @Test
    fun everySliceLaysSomething() {
        val base = DenzaUiState()
        StateSlice.entries.forEach { slice ->
            val reading = changedReading(slice)
            assertEquals(slice, reading.slice)
            assertNotEquals("$slice lays nothing", base, base.withReadings(listOf(reading)))
        }
    }

    private fun sliceOf(tile: TileId): StateSlice? =
        tile.feature?.let(StateSlice::of) ?: readWithoutAFeature[tile]

    private fun face(tile: TileId, state: DenzaUiState): DashboardTile =
        DashboardTiles.of(state).single { it.id == tile }

    private fun marked(feature: FeatureId) = FeatureSnapshot(
        id = feature,
        desiredEnabled = true,
        status = FeatureStatus.ERROR,
        message = "Отказ ${feature.name}",
    )

    /** A reading of [slice] that differs from a fresh state in everything it lays. */
    private fun changedReading(slice: StateSlice): SliceReading = when (slice) {
        StateSlice.ADB_ACCESS -> AdbAccessReading(AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED))
        StateSlice.SIMULCAST -> SimulcastReading(marked(FeatureId.SIMULCAST), emptyList(), 0)
        StateSlice.MIRRORS -> MirrorsReading(marked(FeatureId.MIRRORS), MirrorsPosition.CENTER, false)
        StateSlice.NAVIGATION -> NavigationReading(
            snapshot = marked(FeatureId.NAVIGATION),
            buttonLabel = "С приборки",
            steeringWheelButton = true,
            steeringWheelButtonReady = true,
            steeringWheelButtonRepairing = false,
            placement = ClusterMapPlacement.entries.last(),
            placements = listOf(ClusterMapPlacement.entries.last()),
            appChoice = NavigationAppChoice("ru.yandex.yandexnavi", "Навигатор", selected = true),
        )
        StateSlice.SPLIT_SCREEN -> SplitScreenReading(marked(FeatureId.SPLIT_SCREEN))
        StateSlice.HUD_GUIDANCE -> HudGuidanceReading(marked(FeatureId.HUD_GUIDANCE))
        StateSlice.SPEAKER_COVERS -> SpeakerCoversReading(marked(FeatureId.SPEAKER_COVERS), reporting = true)
        StateSlice.CLOUD_LINK -> CloudLinkReading(marked(FeatureId.CLOUD_LINK), wifiRetained = true, busy = true)
        StateSlice.CLUSTER_DISPLAY -> ClusterDisplayReading(
            candidates = listOf(ClusterDisplayDescriptor(2, "ClusterDisplay", 1920, 720, 160, 1, 0)),
            label = "Экран 1",
            override = 2,
            automatic = "Экран 1",
        )
        StateSlice.WEATHER -> FeatureSlices.WEATHER.reading(
            WeatherSnapshot(enabled = true, temperature = -7, updatedMillis = 1_000L),
        )
        StateSlice.SYSTEM_LANGUAGE ->
            FeatureSlices.SYSTEM_LANGUAGE.reading(SystemLanguageSnapshot(name = "Қазақ тілі"))
    }
}
