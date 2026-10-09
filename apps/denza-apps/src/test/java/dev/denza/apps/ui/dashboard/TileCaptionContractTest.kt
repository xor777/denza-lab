package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.SimulcastBlocker
import dev.denza.apps.SimulcastCoordinator
import dev.denza.apps.SimulcastEnvironment
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.core.FeatureWords
import dev.denza.apps.feature.cloud.CloudCarState
import dev.denza.apps.feature.cloud.CloudFailure
import dev.denza.apps.feature.cloud.CloudLinkStatus
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.defaultapps.DefaultAppRoleStatus
import dev.denza.apps.feature.defaultapps.DefaultAppRoleUiState
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import dev.denza.apps.feature.fse.FseInstallFailure
import dev.denza.apps.feature.fse.FseInstallResult
import dev.denza.apps.feature.fse.FseInstallStatus
import dev.denza.apps.feature.fse.FseInstallStep
import dev.denza.apps.feature.hud.HudGuidanceStatus
import dev.denza.apps.feature.mirrors.MirrorDisplayReadiness
import dev.denza.apps.feature.navigation.NavigationPhase
import dev.denza.apps.feature.navigation.NavigationSession
import dev.denza.apps.feature.navigation.NavigationStep
import dev.denza.apps.feature.navigation.NavigationWords
import dev.denza.apps.feature.speaker.SpeakerCoverStatus
import dev.denza.apps.ui.components.DenzaTileTone
import dev.denza.disharebridge.AdbFailures
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every caption a feature can write on its tile when it did not settle fits the tile and speaks the
 * product's language.
 *
 * `noTileNeedsASecondLineForItsNameOrItsCaption` builds healthy states only, and so it never saw a
 * caption a feature writes when something is wrong: «Повторите настройку доступа», «Трансляция
 * недоступна на этой системе» and «Дождитесь запуска приложения и повторите» all shipped past it.
 * This asks the producers themselves - their real functions, over every input they branch on and the
 * failures the transport really throws - and reads the captions off the dashboard they reach.
 *
 * Two rules, both from the product: a caption is a state in a few words, within the 17 characters a
 * tile's line holds; and it has no internal names, no exception classes and no English. The Latin
 * the product does use on a tile is «HUD» and «Wi-Fi», and nothing else.
 */
class TileCaptionContractTest {

    @Test
    fun everyUnsettledCaptionFitsItsTileAndSpeaksRussian() {
        // The unsettled ones: waiting, broken, or under way. A settled caption is a reading - an
        // application's name, the language in its own words («English», «Deutsch») - and not ours.
        val captions = states().flatMap { (source, state) ->
            DashboardTiles.of(state)
                .filter { it.id != TileId.SERVICE && it.tone in UNSETTLED }
                .map { tile -> Triple(source, tile.id, tile.state) }
        }
        for ((source, id, caption) in captions) {
            assertTrue("$source → $id: «$caption» is ${caption.length} characters", caption.length <= BUDGET)
            val latin = caption.replace("HUD", "").replace("Wi-Fi", "")
            assertTrue("$source → $id: «$caption» carries Latin", latin.none { it in 'A'..'Z' || it in 'a'..'z' })
        }
        // A contract over nothing passes: hold it to having asked every producer.
        val producers = captions.map { it.first.substringBefore(':') }.toSet()
        assertTrue(producers.toString(), producers.containsAll(PRODUCERS))
    }

    /** Each producer's outputs, as the dashboard state they would be published into. */
    private fun states(): List<Pair<String, DenzaUiState>> = buildList {
        // Трансляция: every branch of evaluate, and every repair outcome the repository publishes.
        for (env in simulcastEnvironments()) {
            add("simulcast:evaluate" to DenzaUiState(simulcast = SimulcastCoordinator.evaluate(env)))
        }
        for (failure in FAILURES) {
            val problem = SimulcastCoordinator.setupProblem(failure)
            val snapshot = FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                problem.message,
                resolution = problem.resolution,
            )
            add("simulcast:repair" to DenzaUiState(simulcast = snapshot))
            add(
                "hud:repair" to DenzaUiState(
                    hudGuidance = FeatureReducer.needsAction(
                        FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                        problem.message,
                        resolution = problem.resolution,
                    ),
                ),
            )
        }
        // HUD Подсказки and Динамики: every combination of their preconditions.
        for (enabled in BOOLS) for (navigator in BOOLS) for (service in BOOLS) for (connected in BOOLS) {
            val hud = HudGuidanceStatus.snapshot(enabled, navigator, service, connected, active = false) { "" }
            add("hud:status" to DenzaUiState(hudGuidance = hud))
        }
        for (enabled in BOOLS) for (sessions in BOOLS) {
            add("speakers:status" to DenzaUiState(speakerCovers = SpeakerCoverStatus.snapshot(enabled, sessions)))
        }
        // Зеркала: every selection of the cameras' screen.
        for (selection in SELECTIONS) for (active in BOOLS) {
            add("mirrors:readiness" to DenzaUiState(mirrors = MirrorDisplayReadiness.snapshot(selection, active)))
        }
        // Экран водителя: the screens, the choice, and every step with every failure.
        val navigation = buildList {
            SELECTIONS.filter { it !is ClusterDisplaySelection.Selected }.forEach { add(NavigationWords.display(it)) }
            add(NavigationWords.notChosen)
            for (step in NavigationStep.entries) for (failure in FAILURES) add(NavigationWords.failed(step, failure))
        }
        for (problem in navigation) {
            val session = NavigationSession(
                phase = NavigationPhase.NEEDS_ACTION,
                message = problem.message,
                resolution = problem.resolution,
            )
            add("navigation:words" to DenzaUiState(navigation = session.snapshot()))
        }
        // Экран справа: every way an install ends, and every word it says while it runs.
        for (failure in FseInstallFailure.entries) {
            add("fse:failure" to DenzaUiState(fseInstaller = FseInstallStatus.of(FseInstallResult.Failed(failure))))
        }
        for (failure in FAILURES.filterIsInstance<Exception>()) {
            val words = FseInstallFailure.of(failure)
            add("fse:failure" to DenzaUiState(fseInstaller = FseInstallStatus.of(FseInstallResult.Failed(words))))
        }
        val progress = FseInstallStep.entries.map { it.words } + (0..100).map(FseInstallStep::copying)
        for (words in progress) add("fse:progress" to DenzaUiState(fseInstaller = FseInstallStatus.progress(words)))
        // Разделение: a refused switch, both ways.
        for (enabled in BOOLS) {
            val refused = FeatureReducer.failed(
                FeatureSnapshot(FeatureId.SPLIT_SCREEN, enabled, FeatureStatus.READY),
                FeatureWords.refused(enabled),
            )
            add("split:refused" to DenzaUiState(splitScreen = refused))
        }
        // Shortcuts and the fallbacks every feature tile has when its feature says nothing.
        for (status in DefaultAppRoleStatus.entries) {
            val roles = DefaultAppRole.entries.map { DefaultAppRoleUiState(role = it, status = status) }
            add("shortcuts:status" to DenzaUiState(defaultApps = DefaultAppsUiState(roles = roles)))
        }
        for (status in listOf(FeatureStatus.UNAVAILABLE, FeatureStatus.ERROR)) {
            add(
                "fallbacks:silent" to DenzaUiState(
                    simulcast = FeatureSnapshot(FeatureId.SIMULCAST, true, status),
                    hudGuidance = FeatureSnapshot(FeatureId.HUD_GUIDANCE, true, status),
                    mirrors = FeatureSnapshot(FeatureId.MIRRORS, true, status),
                ),
            )
        }
        add("weather:waiting" to DenzaUiState(weatherEnabled = true))
        // Облако: every flag its status reads, with every kind of failure it keeps.
        val failures = listOf<CloudFailure?>(null) + CloudFailure.Kind.entries.map { CloudFailure(it, "Check failed.") }
        for (enabled in BOOLS) for (connected in listOf(null, false, true)) for (network in BOOLS)
            for (press in failures) for (pass in failures) for (flag in 0..5) {
                val snapshot = CloudLinkStatus.snapshot(
                    enabled = enabled,
                    car = CloudCarState(connected = connected),
                    network = network,
                    failure = press,
                    readingFailed = flag == 1,
                    pendingDisable = flag == 2,
                    stalled = flag == 3,
                    profileDrift = flag == 4,
                    registrationFailure = "Облако отклонило регистрацию (код 3)".takeIf { flag == 5 },
                    automaticFailure = pass,
                )
                add("cloud:status" to DenzaUiState(cloudLink = snapshot))
            }
    }

    private fun simulcastEnvironments(): List<SimulcastEnvironment> = buildList {
        for (blocker in listOf<SimulcastBlocker?>(null) + SimulcastBlocker.entries)
            for (overlay in BOOLS) for (service in BOOLS) for (connected in BOOLS) {
                add(
                    SimulcastEnvironment(
                        desired = true,
                        blocker = blocker,
                        overlayAllowed = overlay,
                        accessibilityEnabled = service,
                        accessibilityConnected = connected,
                        active = false,
                    ),
                )
            }
    }

    private companion object {
        /** The tile's line: see `noTileNeedsASecondLineForItsNameOrItsCaption`. */
        const val BUDGET = 17

        val BOOLS = listOf(false, true)

        val UNSETTLED = setOf(DenzaTileTone.ATTENTION, DenzaTileTone.BROKEN, DenzaTileTone.WORKING)

        val PRODUCERS = setOf(
            "simulcast", "hud", "speakers", "mirrors", "navigation", "fse", "split", "shortcuts",
            "fallbacks", "weather", "cloud",
        )

        val DISPLAY = ClusterDisplayDescriptor(2, "ClusterDisplay", 1920, 720, 160, 0, 0)

        val SELECTIONS = listOf(
            ClusterDisplaySelection.Selected(DISPLAY),
            ClusterDisplaySelection.NeedsVerification(listOf(DISPLAY)),
            ClusterDisplaySelection.Missing,
        )

        /** No failure, the transport's own, and failures of the feature's own making. */
        val FAILURES: List<Throwable?> = listOf(
            null,
            AdbFailures.authorizationRequired(),
            AdbFailures.authorizationPending(),
            AdbFailures.noHosts(),
            SocketTimeoutException("Read timed out"),
            ConnectException("failed to connect to /127.0.0.1 (port 5555): ECONNREFUSED (Connection refused)"),
            GeneralSecurityException("bad key"),
            IllegalStateException("navigation command returned no result"),
            IllegalStateException("APK copy failed: dd exit=1 block=3"),
            IllegalStateException("FSE storage is not mounted"),
            IOException("the resident helper refused: busy"),
        )
    }
}
