package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.defaultapps.DefaultAppChoice
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.defaultapps.DefaultAppRoleStatus
import dev.denza.apps.feature.defaultapps.DefaultAppRoleUiState
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import dev.denza.apps.feature.locale.StockRussianLocaleSnapshot
import dev.denza.apps.ui.components.DenzaTileCaption
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
    fun theMainScreenCarriesTheElevenReachableTilesAndNothingElse() {
        // Default applications is a settings door rather than a runtime feature, immediately before
        // the service door so it remains reachable without inventing a fake FeatureId.
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
                TileId.DEFAULT_APPS,
                TileId.SERVICE,
            ),
            DashboardTiles.of(DenzaUiState()).map { it.id },
        )
    }

    @Test
    fun weatherReadsBackWhatTheCarWasGiven() {
        val now = 1_700_000_000_000L
        val tile = DenzaUiState(
            weatherEnabled = true,
            weatherTemperature = 14,
            weatherUpdatedMillis = now - 12 * 60_000L,
        ).tile(TileId.WEATHER)
        assertEquals("+14°", tile.state)

        val off = DenzaUiState(weatherEnabled = false, weatherTemperature = 14)
            .tile(TileId.WEATHER)
        assertEquals("Выключена", off.state)
    }

    /**
     * The switch is not the reading.
     *
     * It shipped lit whenever the alarm stood, so a car that had never once fetched a forecast drew
     * the weather in the accent over "Данных ещё нет" - the tone promising what the caption denied.
     */
    @Test
    fun weatherIsOnlyLitWhenSomethingWasActuallyHandedToTheWidget() {
        val waiting = DenzaUiState(weatherEnabled = true).tile(TileId.WEATHER)
        assertEquals("Данных ещё нет", waiting.state)
        assertEquals(DenzaTileTone.WORKING, waiting.tone)

        val fed = DenzaUiState(
            weatherEnabled = true,
            weatherTemperature = -3,
            weatherUpdatedMillis = 1L,
        ).tile(TileId.WEATHER)
        assertEquals("-3°", fed.state)
        assertEquals(DenzaTileTone.LIVE, fed.tone)

        assertEquals(
            DenzaTileTone.IDLE,
            DenzaUiState(weatherEnabled = false).tile(TileId.WEATHER).tone,
        )
    }

    @Test
    fun theReadingCarriesItsSignAndItsAgeInWords() {
        // Below zero is the whole reason to look at it, so the sign is never dropped.
        assertEquals("+14°", DashboardTiles.degrees(14))
        assertEquals("0°", DashboardTiles.degrees(0))
        assertEquals("-3°", DashboardTiles.degrees(-3))

        assertEquals("только что", DashboardTiles.ago(20_000L))
        assertEquals("1 минуту назад", DashboardTiles.ago(60_000L))
        assertEquals("2 минуты назад", DashboardTiles.ago(2 * 60_000L))
        assertEquals("12 минут назад", DashboardTiles.ago(12 * 60_000L))
        assertEquals("1 час назад", DashboardTiles.ago(60 * 60_000L))
        assertEquals("5 часов назад", DashboardTiles.ago(5 * 60 * 60_000L))
        assertEquals("больше суток назад", DashboardTiles.ago(48L * 60 * 60_000L))
    }

    @Test
    fun serviceCountsWhatIsWrongInsteadOfClaimingNothingIs() {
        // It shipped with "Всё в норме" as a constant string, which made the one tile whose job is
        // to say something is wrong the one tile incapable of saying it.
        val healthy = DashboardTiles.of(DenzaUiState()).first { it.id == TileId.SERVICE }
        assertEquals("Всё в норме", healthy.state)
        assertEquals(DenzaTileTone.IDLE, healthy.tone)

        val waiting = DashboardTiles.of(
            DenzaUiState(
                mirrors = snapshot(FeatureStatus.NEEDS_ACTION),
                hudGuidance = snapshot(FeatureStatus.NEEDS_ACTION),
            ),
        ).first { it.id == TileId.SERVICE }
        assertEquals("2 функции ждут", waiting.state)
        assertEquals(DenzaTileTone.ATTENTION, waiting.tone)

        val hurt = DashboardTiles.of(
            DenzaUiState(
                mirrors = snapshot(FeatureStatus.NEEDS_ACTION),
                simulcast = snapshot(FeatureStatus.ERROR),
            ),
        ).first { it.id == TileId.SERVICE }
        // Broken and waiting count together and read amber, which is what `Attention.dc.html`
        // draws for exactly this pair. Coral here would be the door claiming to be the fault.
        assertEquals("2 функции ждут", hurt.state)
        assertEquals(DenzaTileTone.ATTENTION, hurt.tone)
    }

    /**
     * The number on the door and the list behind it are one list.
     *
     * The face counted the seven coordinated features and the panel filtered all eleven tiles, so a
     * car with a stuck language or an unread application role had a door reading "Всё в норме" over
     * a panel naming the fault - and the door, which is never the thing that broke, put itself in
     * its own list of things that need somebody.
     */
    @Test
    fun theDoorCountsEveryTileButItselfAndPrintsTheSameList() {
        val state = DenzaUiState(
            mirrors = snapshot(FeatureStatus.NEEDS_ACTION),
            stockRussianLocale = StockRussianLocaleSnapshot(
                enabled = false,
                permissionReady = true,
                failed = true,
            ),
            defaultApps = configuredDefaults(3).update(DefaultAppRole.VIDEO) { role ->
                role.copy(status = DefaultAppRoleStatus.ERROR, providerConfirmed = false)
            },
        )

        val needing = DashboardTiles.attentionTiles(state)
        assertEquals(
            listOf(TileId.MIRRORS, TileId.LOCALE, TileId.DEFAULT_APPS),
            needing.map { it.id },
        )
        // Whatever the door says is the length of that list, said in Russian.
        assertEquals("3 функции ждут", state.tile(TileId.SERVICE).state)
        assertEquals(DenzaTileTone.ATTENTION, state.tile(TileId.SERVICE).tone)
    }

    @Test
    fun theDoorIsNeverInItsOwnList() {
        // Every tile at once, which used to be the state in which the panel listed "Сервис · 7
        // функций ждут" among the things that need somebody.
        FeatureStatus.entries.forEach { status ->
            val state = everyFeatureAt(status)
            assertTrue(
                "service listed itself at $status",
                DashboardTiles.attentionTiles(state).none { it.id == TileId.SERVICE },
            )
        }
    }

    @Test
    fun theCountAgreesWithItsNounAndItsVerb() {
        // Russian agrees on the last digit and then makes an exception of the teens, so eleven
        // takes the same form as five and twenty-one the same as one.
        assertEquals("1 функция", DashboardTiles.featureCount(1))
        assertEquals("2 функции", DashboardTiles.featureCount(2))
        assertEquals("5 функций", DashboardTiles.featureCount(5))
        assertEquals("11 функций", DashboardTiles.featureCount(11))
        assertEquals("21 функция", DashboardTiles.featureCount(21))
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
        // "6 приложений" would twin this tile with the one actually named "Приложения".
        assertEquals("Выбрано 6", chosen.tile(TileId.SIMULCAST).state)

        val none = DenzaUiState(simulcast = snapshot(FeatureStatus.READY, enabled = true))
        assertEquals("Не выбрано", none.tile(TileId.SIMULCAST).state)
    }

    /**
     * The press is the switch, and off is a car running its own applications.
     *
     * It used to open the panel, which made the one tile on the screen whose main action was to
     * show its own settings - and left the thing the feature actually does, substituting the
     * applications the car's commands open, with no gesture at all.
     */
    @Test
    fun defaultApplicationsSwitchTheSubstitutionAndNameWhatTheCarIsOn() {
        assertEquals("1 настроено", DashboardTiles.configuredApps(1))
        assertEquals("2 настроены", DashboardTiles.configuredApps(2))
        assertEquals("3 настроены", DashboardTiles.configuredApps(3))

        val tile = DenzaUiState(defaultApps = configuredDefaults(2)).tile(TileId.DEFAULT_APPS)
        assertEquals("Приложения", tile.name)
        assertEquals("2 настроены", tile.state)
        assertEquals(TileAction.TOGGLE, tile.action)
        assertEquals(DenzaTileTone.LIVE, tile.tone)
        assertEquals(null, TileId.DEFAULT_APPS.feature)

        // Switched off with the catalog still installed: the press turns it back on.
        val stock = DenzaUiState(defaultApps = switchedOffDefaults()).tile(TileId.DEFAULT_APPS)
        assertEquals("Штатные", stock.state)
        assertEquals(TileAction.TOGGLE, stock.action)
        assertEquals(DenzaTileTone.IDLE, stock.tone)

        // Nothing installed to switch to, so the press goes where a choice can be made instead of
        // writing the three stock packages back into the three roles already holding them.
        val nothingToSwitchTo = DenzaUiState(
            defaultApps = switchedOffDefaults().copy(
                roles = switchedOffDefaults().roles.map { it.copy(choices = emptyList()) },
            ),
        ).tile(TileId.DEFAULT_APPS)
        assertEquals("Штатные", nothingToSwitchTo.state)
        assertEquals(TileAction.SETTINGS, nothingToSwitchTo.action)

        val unavailable = DenzaUiState(
            defaultApps = configuredDefaults(2).copy(
                roles = configuredDefaults(2).roles.mapIndexed { index, role ->
                    if (index == 0) {
                        role.copy(status = DefaultAppRoleStatus.ERROR, providerConfirmed = false)
                    } else {
                        role
                    }
                },
            ),
        ).tile(TileId.DEFAULT_APPS)
        assertEquals("Не проверено", unavailable.state)
    }

    /**
     * "Проверяем…" is a read the driver is waiting on, and storing a choice is not one.
     *
     * The panel marks the tap before the write leaves, so a tile that announced the write as well
     * spent every selection swapping its caption and its accent for a few hundred milliseconds.
     */
    @Test
    fun storingAChoiceDoesNotMakeTheTileAnnounceAWait() {
        val defaults = configuredDefaults(3)
        val applying = DenzaUiState(
            defaultApps = defaults.update(DefaultAppRole.MUSIC) { role ->
                role.copy(
                    status = DefaultAppRoleStatus.APPLYING,
                    pendingPackageName = "com.spotify.music",
                )
            },
        ).tile(TileId.DEFAULT_APPS)
        assertEquals("3 настроены", applying.state)
        assertEquals(DenzaTileTone.LIVE, applying.tone)

        val reading = DenzaUiState(
            defaultApps = defaults.update(DefaultAppRole.MUSIC) { role ->
                role.copy(status = DefaultAppRoleStatus.LOADING)
            },
        ).tile(TileId.DEFAULT_APPS)
        assertEquals("Проверяем…", reading.state)
        assertEquals(DenzaTileTone.WORKING, reading.tone)
    }

    /**
     * The cluster tile names what is chosen, and says it the same way whether or not it is showing.
     *
     * It used to add "· на экране" while projecting, which made it the one caption that changed
     * length when the feature was used - and since the tile stacks its words up from the bottom
     * edge, the longer version pushed the name up. Being on the cluster is already carried twice:
     * by the tone, and by the accent the caption takes.
     */
    @Test
    fun theClusterCaptionDoesNotChangeWhenItGoesOnTheScreen() {
        val projected = DenzaUiState(
            navigation = snapshot(FeatureStatus.ACTIVE, enabled = true),
            navigationAppLabel = "Приборы",
        )
        val idle = DenzaUiState(navigationAppLabel = "Приборы")

        assertEquals("Приборы", projected.tile(TileId.CLUSTER).state)
        assertEquals(projected.tile(TileId.CLUSTER).state, idle.tile(TileId.CLUSTER).state)
        // What did change is the accent, which is where "on the screen" lives now.
        assertEquals(DenzaTileCaption.READING, projected.tile(TileId.CLUSTER).caption)
        assertEquals(DenzaTileCaption.SETTING, idle.tile(TileId.CLUSTER).caption)
    }

    /**
     * Nothing a tile writes may need a second line.
     *
     * The tile gives a name 147 dp and a caption the same, measured off the wide pane: 1280 dp less
     * two 48 dp margins, six columns with 12 dp between them, less 20 dp of padding either side.
     * In Roboto at 19/500 that is about 15 characters for a name and about 19 for a caption at
     * 15/400 - "Пассажирский экран" measured 194 dp and wrapped, which is how this rule was found.
     *
     * Characters are a proxy for dp and this test knows it. The real measurement is the board:
     * `Main.dc.html` writes the same words at the same sizes in the same width, and `audit.py`
     * reports anything that overflows. This is here to fail fast in the build, not to replace it.
     */
    @Test
    fun noTileNeedsASecondLineForItsNameOrItsCaption() {
        val states = listOf(
            DenzaUiState(),
            DenzaUiState(
                navigation = snapshot(FeatureStatus.ACTIVE, enabled = true),
                navigationAppLabel = "Яндекс Навигатор",
                simulcast = snapshot(FeatureStatus.READY, enabled = true),
                selectedAppCount = 6,
                mirrors = snapshot(FeatureStatus.READY, enabled = true),
                splitScreen = snapshot(FeatureStatus.READY, enabled = true),
                hudGuidance = snapshot(FeatureStatus.READY, enabled = true),
                speakerCovers = snapshot(FeatureStatus.READY, enabled = true),
                weatherEnabled = true,
                weatherTemperature = -14,
                weatherUpdatedMillis = 1L,
            ),
        )
        for (state in states) {
            for (tile in DashboardTiles.of(state)) {
                assertTrue("name too long: ${tile.name}", tile.name.length <= NAME_BUDGET)
                assertTrue("caption too long: ${tile.state}", tile.state.length <= STATE_BUDGET)
            }
        }
    }

    /**
     * A refusal is not a setting.
     *
     * A failed switch keeps the wish it was handed, so the split tile read its caption off
     * `desiredEnabled` and said "Включено" in coral - the words claiming exactly what the colour
     * was there to deny.
     */
    @Test
    fun aSplitThatRefusedDoesNotClaimToBeOn() {
        val refused = DenzaUiState(
            splitScreen = snapshot(
                FeatureStatus.ERROR,
                enabled = true,
                message = "Не включилось",
            ),
        ).tile(TileId.SPLIT)
        assertEquals("Не включилось", refused.state)
        assertEquals(DenzaTileTone.BROKEN, refused.tone)

        val silent = DenzaUiState(splitScreen = snapshot(FeatureStatus.ERROR, enabled = true))
            .tile(TileId.SPLIT)
        assertEquals("Не переключилось", silent.state)

        val on = DenzaUiState(splitScreen = snapshot(FeatureStatus.READY, enabled = true))
            .tile(TileId.SPLIT)
        assertEquals("Включено", on.state)
    }

    /**
     * The stock language has no runtime feature behind it and still has to be able to fail.
     *
     * Its refusals used to write a message nothing read, over a tile that took its colour from the
     * saved value alone - so the car saying no looked exactly like a language nobody had asked for.
     */
    @Test
    fun aLanguageTheCarRefusedIsVisibleOnItsFaceAndInTheDoorsList() {
        val refused = DenzaUiState(
            stockRussianLocale = StockRussianLocaleSnapshot(
                enabled = false,
                permissionReady = true,
                failed = true,
                message = "Язык не переключился",
            ),
        )
        assertEquals("Не переключился", refused.tile(TileId.LOCALE).state)
        assertEquals(DenzaTileTone.ATTENTION, refused.tile(TileId.LOCALE).tone)
        assertTrue(DashboardTiles.attentionTiles(refused).any { it.id == TileId.LOCALE })

        // Nothing was attempted at all: the app has no way into the car yet, which is a different
        // thing to say and the only one of the two a driver can act on.
        val blocked = DenzaUiState(
            stockRussianLocale = StockRussianLocaleSnapshot(failed = true, permissionReady = false),
        )
        assertEquals("Нужен доступ", blocked.tile(TileId.LOCALE).state)

        val quiet = DenzaUiState(stockRussianLocale = StockRussianLocaleSnapshot(enabled = true))
        assertEquals("Включён", quiet.tile(TileId.LOCALE).state)
        assertEquals(DenzaTileTone.LIVE, quiet.tile(TileId.LOCALE).tone)
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

    /** Every role read, every one of them on the car's own application, catalog still installed. */
    private fun switchedOffDefaults(): DefaultAppsUiState = DefaultAppsUiState(
        roles = DefaultAppRole.entries.map { role ->
            val known = role.knownThirdPartyApps.first()
            DefaultAppRoleUiState(
                role = role,
                selectedPackageName = role.stockPackageName,
                selectedLabel = "Штатное",
                choices = listOf(
                    DefaultAppChoice(
                        packageName = known.packageName,
                        label = known.fallbackLabel,
                        icon = null,
                        selected = false,
                        known = true,
                        stock = false,
                    ),
                ),
                status = DefaultAppRoleStatus.READY,
                providerConfirmed = true,
            )
        },
    )

    private fun configuredDefaults(count: Int): DefaultAppsUiState = DefaultAppsUiState(
        roles = DefaultAppRole.entries.mapIndexed { index, role ->
            val definition = role.knownThirdPartyApps.first()
            DefaultAppRoleUiState(
                role = role,
                selectedPackageName = definition.packageName.takeIf { index < count },
                selectedLabel = definition.fallbackLabel.takeIf { index < count } ?: "Не выбрано",
                status = DefaultAppRoleStatus.READY,
                providerConfirmed = true,
            )
        },
    )
}

/*
 * How many characters a tile's two lines may hold.
 *
 * A character count is a proxy for a width and the proxy is what these numbers are for: the tile
 * elides at one line each, and the registry is supposed to write captions that fit rather than
 * captions that get cut. The full screen is the tightest of the three widths - 187.3 dp a tile
 * less 20 either side is 147.3 dp of words, against 148.0 in the two-thirds pane and 150.0 in the
 * narrow one - so it is the one these are set against.
 *
 * Measured in a browser against Roboto, because there is no text engine in a unit test: at 19/500
 * "Экран водителя" is 145.2 dp over 14 characters, and at 15/400 Cyrillic runs about 8.3 dp a
 * character, which puts the ceiling at 17. Both were two rungs looser and the looseness was real:
 * an 18-character caption had already been written and measures 150.
 */
private const val NAME_BUDGET = 14
private const val STATE_BUDGET = 17
