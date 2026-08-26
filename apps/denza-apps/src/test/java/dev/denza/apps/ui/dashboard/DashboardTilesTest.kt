package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.ui.components.DenzaTileTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard decides itself, and the decisions are checkable without a screen.
 *
 * That is the whole reason the registry holds names for its actions instead of lambdas: "pressing
 * Projection while it is switched off turns it on rather than trying to start it" is a product
 * decision, and it should fail here when somebody changes it by accident rather than on the car.
 */
class DashboardTilesTest {

    @Test
    fun theMainScreenCarriesTheTenReachableTilesAndNothingElse() {
        // The wheel button is a switch inside the driver's own screen rather than a feature of its
        // own - the owner moved it there - so the board's eleven tiles are ten here. Weather was
        // the other absence: it had no user control until it was given one.
        assertEquals(
            listOf(
                TileId.CLUSTER,
                TileId.SIMULCAST,
                TileId.MIRRORS,
                TileId.SPLIT,
                TileId.HUD,
                TileId.WEATHER,
                TileId.SPEAKERS,
                TileId.LOCALE,
                TileId.PASSENGER,
                TileId.SERVICE,
            ),
            DashboardTiles.of(DenzaUiState()).map { it.id },
        )
    }

    @Test
    fun aFeatureThatIsSwitchedOffDoesNotLookLikeAFeatureThatBroke() {
        // The screen this replaces drew both in the same muted grey, so "I turned that off" and
        // "that failed" were one picture.
        assertEquals(DenzaTileTone.IDLE, DashboardTiles.toneOf(snapshot(FeatureStatus.OFF)))
        assertEquals(DenzaTileTone.BROKEN, DashboardTiles.toneOf(snapshot(FeatureStatus.ERROR)))
        assertEquals(
            DenzaTileTone.BROKEN,
            DashboardTiles.toneOf(snapshot(FeatureStatus.UNAVAILABLE)),
        )
        assertEquals(
            DenzaTileTone.ATTENTION,
            DashboardTiles.toneOf(snapshot(FeatureStatus.NEEDS_ACTION)),
        )
    }

    @Test
    fun readyMeansLiveOnlyIfTheDriverAskedForIt() {
        // NAVIGATION rests at READY with nothing projected, which is not the same as working.
        assertEquals(
            DenzaTileTone.IDLE,
            DashboardTiles.toneOf(snapshot(FeatureStatus.READY, enabled = false)),
        )
        assertEquals(
            DenzaTileTone.LIVE,
            DashboardTiles.toneOf(snapshot(FeatureStatus.READY, enabled = true)),
        )
        assertEquals(DenzaTileTone.LIVE, DashboardTiles.toneOf(snapshot(FeatureStatus.ACTIVE)))
    }

    @Test
    fun aBusyFeatureSaysSoRatherThanLookingBroken() {
        assertEquals(DenzaTileTone.WORKING, DashboardTiles.toneOf(snapshot(FeatureStatus.STARTING)))
        assertEquals(
            DenzaTileTone.WORKING,
            DashboardTiles.toneOf(snapshot(FeatureStatus.RECOVERING)),
        )
    }

    @Test
    fun pressingAProjectionThatIsOffTurnsItOnRatherThanTryingToStartIt() {
        val off = DenzaUiState(simulcast = snapshot(FeatureStatus.OFF, enabled = false))
        assertEquals(TileAction.TOGGLE, off.tile(TileId.SIMULCAST).action)

        val on = DenzaUiState(
            simulcast = snapshot(FeatureStatus.READY, enabled = true),
            selectedAppCount = 2,
        )
        assertEquals(TileAction.SIMULCAST_LAUNCH, on.tile(TileId.SIMULCAST).action)
    }

    @Test
    fun aFeatureWaitingOnAChoiceAnswersThePressWithThatChoice() {
        // Asking it to run instead would only fail and put the same words back on the tile.
        val waiting = DenzaUiState(
            simulcast = snapshot(
                FeatureStatus.NEEDS_ACTION,
                enabled = true,
                resolution = FeatureResolution.SELECT_APPS,
            ),
        )
        assertEquals(TileAction.RESOLVE, waiting.tile(TileId.SIMULCAST).action)
    }

    @Test
    fun aFeatureThisCarDoesNotHaveOpensItsSettingsBecauseNothingElseCanSayWhy() {
        val absent = DenzaUiState(mirrors = snapshot(FeatureStatus.UNAVAILABLE))
        assertEquals(TileAction.SETTINGS, absent.tile(TileId.MIRRORS).action)
    }

    @Test
    fun aWaitingFeatureWithNothingToResolveStillOffersItsMainAction() {
        // NEEDS_ACTION without a resolution is a state the reducer can produce; sending the press
        // to a chooser that does not exist would leave the tile inert.
        val stuck = DenzaUiState(mirrors = snapshot(FeatureStatus.NEEDS_ACTION, enabled = true))
        assertEquals(TileAction.TOGGLE, stuck.tile(TileId.MIRRORS).action)
    }

    @Test
    fun theCaptionSaysWhatIsConfiguredRatherThanNamingTheState() {
        val chosen = DenzaUiState(
            simulcast = snapshot(FeatureStatus.READY, enabled = true),
            selectedAppCount = 6,
        )
        assertEquals("6 приложений", chosen.tile(TileId.SIMULCAST).state)

        val none = DenzaUiState(simulcast = snapshot(FeatureStatus.READY, enabled = true))
        assertEquals("Выберите приложения", none.tile(TileId.SIMULCAST).state)
    }

    @Test
    fun theClusterTileSaysWhatIsOnItRatherThanRepeatingItsOwnName() {
        val projected = DenzaUiState(
            navigation = snapshot(FeatureStatus.ACTIVE, enabled = true),
            navigationAppLabel = "Приборы",
        )
        assertEquals("Приборы · на экране", projected.tile(TileId.CLUSTER).state)

        val idle = DenzaUiState(navigationAppLabel = "Приборы")
        assertEquals("Приборы", idle.tile(TileId.CLUSTER).state)
    }

    @Test
    fun aWaitingFeatureSaysWhatItIsWaitingFor() {
        val waiting = DenzaUiState(
            mirrors = snapshot(
                FeatureStatus.NEEDS_ACTION,
                enabled = true,
                message = "Выберите экран",
            ),
        )
        assertEquals("Выберите экран", waiting.tile(TileId.MIRRORS).state)
    }

    @Test
    fun russianAgreesTheNounWithTheLastDigitAndMakesAnExceptionOfTheTeens() {
        assertEquals("1 приложение", DashboardTiles.applications(1))
        assertEquals("2 приложения", DashboardTiles.applications(2))
        assertEquals("4 приложения", DashboardTiles.applications(4))
        assertEquals("5 приложений", DashboardTiles.applications(5))
        // The teens all take the plural, including eleven, which its last digit would not predict.
        assertEquals("11 приложений", DashboardTiles.applications(11))
        assertEquals("14 приложений", DashboardTiles.applications(14))
        // And past them the last digit rules again: twenty-one agrees like one.
        assertEquals("21 приложение", DashboardTiles.applications(21))
        assertEquals("22 приложения", DashboardTiles.applications(22))
        assertEquals("25 приложений", DashboardTiles.applications(25))
    }

    @Test
    fun everyTileSaysSomethingWhateverStateItIsIn() {
        // A blank caption reads as a rendering failure, not as calm.
        FeatureStatus.entries.forEach { status ->
            DashboardTiles.of(everyFeatureAt(status)).forEach { tile ->
                assertTrue(
                    "${tile.id} says nothing at $status",
                    tile.name.isNotBlank() && tile.state.isNotBlank(),
                )
            }
        }
    }

    private fun DenzaUiState.tile(id: TileId): DashboardTile =
        DashboardTiles.of(this).first { it.id == id }

    private fun snapshot(
        status: FeatureStatus,
        enabled: Boolean = true,
        message: String = "",
        resolution: FeatureResolution? = null,
    ) = FeatureSnapshot(
        id = FeatureId.SIMULCAST,
        desiredEnabled = enabled,
        status = status,
        message = message,
        resolution = resolution,
    )

    private fun everyFeatureAt(status: FeatureStatus) = DenzaUiState(
        simulcast = snapshot(status),
        mirrors = snapshot(status),
        navigation = snapshot(status),
        splitScreen = snapshot(status),
        hudGuidance = snapshot(status),
        speakerCovers = snapshot(status),
        fseInstaller = snapshot(status),
    )
}
