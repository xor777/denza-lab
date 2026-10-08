package dev.denza.apps

import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.locale.SystemLanguageSnapshot
import dev.denza.apps.feature.mirrors.MirrorsPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class StateSlicesTest {

    /**
     * The defect: every application on a chooser or a row carried a `Drawable`, which the package
     * manager hands out fresh on every read and which compares by identity - so no two recomputes
     * were ever equal, and each one redrew the whole dashboard. Two reads of the same car are now
     * two equal states, and the state flow, which drops an equal value, publishes nothing.
     */
    @Test
    fun `two recomputes from the same inputs produce equal states`() {
        val base = DenzaUiState()

        val first = base.withReadings(readingsOfOneCar())
        val second = base.withReadings(readingsOfOneCar())

        assertNotSame(first, second)
        assertEquals(first, second)
        assertEquals(first, first.withReadings(readingsOfOneCar()))
    }

    @Test
    fun `every slice is read, and no two slices lay the same field`() {
        val readings = readingsOfOneCar()
        assertEquals(StateSlice.entries.toSet(), readings.map(SliceReading::slice).toSet())

        // A field two readings both wrote would come out of the two orders differently.
        val base = DenzaUiState()
        assertEquals(base.withReadings(readings), base.withReadings(readings.reversed()))

        val mirrorsOnly = base.withReadings(readings.filter { it.slice == StateSlice.MIRRORS })
        assertEquals(
            base.copy(
                mirrors = mirrorsOnly.mirrors,
                mirrorsPosition = MirrorsPosition.CENTER,
                mirrorsProcessing = false,
            ),
            mirrorsOnly,
        )
    }

    /** One car's readings, built afresh on every call as a recompute would read them. */
    private fun readingsOfOneCar(): List<SliceReading> {
        val apps = listOf("ru.rutube.app" to "Rutube", "org.videolan.vlc" to "VLC").map { (pkg, label) ->
            SimulcastAppChoice(packageName = pkg, label = label, selected = true)
        }
        return listOf(
            SimulcastReading(FeatureReducer.ready(FeatureId.SIMULCAST, active = false), apps, apps.size),
            MirrorsReading(
                FeatureSnapshot(FeatureId.MIRRORS, desiredEnabled = true, status = FeatureStatus.READY),
                MirrorsPosition.CENTER,
                processing = false,
            ),
            NavigationReading(
                snapshot = FeatureSnapshot(FeatureId.NAVIGATION, desiredEnabled = false, status = FeatureStatus.READY),
                buttonLabel = "На приборку",
                steeringWheelButton = true,
                steeringWheelButtonReady = true,
                steeringWheelButtonRepairing = false,
                placement = ClusterMapPlacement.FULL,
                placements = ClusterMapPlacement.entries.toList(),
                appChoice = NavigationAppChoice("ru.yandex.yandexnavi", "Навигатор", selected = true),
            ),
            SplitScreenReading(FeatureReducer.disabled(FeatureId.SPLIT_SCREEN)),
            HudGuidanceReading(FeatureReducer.disabled(FeatureId.HUD_GUIDANCE)),
            SpeakerCoversReading(FeatureReducer.disabled(FeatureId.SPEAKER_COVERS), reporting = false),
            CloudLinkReading(FeatureReducer.disabled(FeatureId.CLOUD_LINK), wifiRetained = true, busy = false),
            ClusterDisplayReading(
                candidates = listOf(ClusterDisplayDescriptor(2, "ClusterDisplay", 1920, 720, 160, 1, 0)),
                label = "Определён сам: Экран 1",
                override = null,
                automatic = "Экран 1",
            ),
            WeatherReading(enabled = true, temperature = 14, updatedMillis = 1_000L),
            SystemLanguageReading(SystemLanguageSnapshot()),
        )
    }
}
