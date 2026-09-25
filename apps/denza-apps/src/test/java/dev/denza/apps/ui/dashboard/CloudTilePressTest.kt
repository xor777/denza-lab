package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.feature.cloud.CloudCarState
import dev.denza.apps.feature.cloud.CloudLinkStatus
import dev.denza.apps.feature.cloud.CloudSimMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a press on «Облако» asks for.
 *
 * The press is the switch - until the car has refused one. Then the tile says «Не включилось» or
 * «Не выключилось» over a wish that has not changed, and the press asks the car again; answering
 * «Не включилось» by switching the link off would be the tile doing the opposite of its own words.
 */
class CloudTilePressTest {

    private val asked = mutableListOf<Boolean>()
    private var opened = 0

    @Test
    fun firstTapOpensSettingsWithoutSwitching() {
        press(DenzaUiState())
        assertEquals(1, opened)
        assertEquals(emptyList<Boolean>(), asked)
    }

    @Test
    fun anotherPressDuringAWriteDoesNotQueueAnOldToggle() {
        press(state(enabled = true, failure = null).copy(cloudLinkBusy = true))
        assertEquals(emptyList<Boolean>(), asked)
        press(state(enabled = true, failure = null))
        assertEquals(listOf(false), asked)
    }

    @Test
    fun aPressFlipsTheLink() {
        press(state(enabled = false, failure = null))
        press(state(enabled = true, failure = null))
        assertEquals(listOf(true, false), asked)
    }

    @Test
    fun aPressOnARefusalAsksForTheSameThingAgain() {
        press(state(enabled = true, failure = "Не включилось"))
        press(state(enabled = false, failure = "Не выключилось"))
        assertEquals(listOf(true, false), asked)
    }

    private fun state(enabled: Boolean, failure: String?) = DenzaUiState(
        cloudLink = CloudLinkStatus.snapshot(enabled, CloudCarState(connected = false), network = true, failure),
        cloudMode = CloudSimMode.FACTORY,
    )

    private fun press(state: DenzaUiState) {
        val tile = DashboardTiles.of(state).first { it.id == TileId.CLOUD }
        DashboardPress.perform(tile, state, actions)
    }

    private val actions = DashboardActions(
        onToggleSimulcast = {},
        onLaunchSimulcast = {},
        onRepairSimulcast = {},
        onChooseApps = {},
        onLoadAppChoices = {},
        onToggleApp = {},
        onToggleMirrors = {},
        onMirrorsPosition = {},
        onMirrorsProcessing = {},
        onPreviewMirrors = {},
        onNavigationAction = {},
        onNavigationPlacement = {},
        onNavigationSteeringWheelButton = {},
        onChooseNavigationApp = {},
        onLoadNavigationAppChoices = {},
        onSelectNavigationApp = {},
        onToggleSplitScreen = {},
        onLaunchSplitScreen = {},
        onSetWeatherEnabled = {},
        onToggleHudGuidance = {},
        onToggleSpeakerCovers = {},
        onRaiseSpeakerCovers = {},
        onToggleCloudLink = { asked += it },
        onSetCloudWifiRetained = {},
        onOpenSystemLanguage = {},
        onSetDefaultAppsEnabled = {},
        onChooseFseApp = {},
        onOpenClusterPicker = {},
        onOpenService = {},
        onOpenSettings = { opened++ },
    )
}
