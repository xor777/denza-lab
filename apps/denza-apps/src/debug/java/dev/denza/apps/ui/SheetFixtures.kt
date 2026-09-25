package dev.denza.apps.ui

import androidx.compose.runtime.Composable
import dev.denza.apps.DenzaUiState
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbRescueSnapshot
import dev.denza.apps.feature.adb.AdbStartupGatePolicy
import dev.denza.apps.feature.adb.AdbSystemSwitch
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.defaultapps.DefaultAppChoice
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.defaultapps.DefaultAppRoleStatus
import dev.denza.apps.feature.defaultapps.DefaultAppRoleUiState
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.ui.dashboard.DashboardActions
import dev.denza.apps.ui.dashboard.CloudPairConfirmationDialog
import dev.denza.apps.ui.dashboard.DefaultAppsSheet
import dev.denza.apps.ui.dashboard.FeatureSheet
import dev.denza.apps.ui.dashboard.TileId
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Luminofor `sheet-*` board's scene as the app's own settings panel. Debug builds only.
 *
 * The board draws the panel block by block; its fixture also carries `state`, the handful of facts
 * the real panel is built from - which tile, which applications, which switches are on. This turns
 * `state` into a [DenzaUiState] and hands it to the panel the long press opens, [FeatureSheet] or
 * [DefaultAppsSheet], so a screenshot of the app is the app's own panel and not a copy of the board.
 * The applications have no icons, so the app draws the initial the board draws in their place.
 */
internal object SheetFixtures {

    @Composable
    fun Sheet(fixture: JSONObject, compact: Boolean) {
        val s = fixture.getJSONObject("state")
        val tile = TileId.valueOf(s.getString("tile"))
        if (tile == TileId.SERVICE) {
            ServicePanel(
                state = service(s),
                compactLayout = compact,
                onOpenFeature = {},
                onSelectClusterDisplay = {},
                onCheckAdbAccess = {},
                onRequestAdbAuthorizationOnce = {},
                onAllowNewAdbAuthorizationAttempt = {},
                onSetCloudReportExport = {},
                onDismiss = {},
                firstPage = when (s.optString("page")) {
                    "screen" -> ServicePage.SCREEN
                    "technical" -> ServicePage.TECHNICAL
                    "journal" -> ServicePage.JOURNAL
                    else -> ServicePage.MAIN
                },
                // The board's `scroll: 'end'`: the page at the end of its scroll, where the report's
                // split section is.
                firstPageAtEnd = s.optString("scroll") == "end",
                version = "Denza Apps ${s.getString("version")} · сборка ${s.getInt("build")}",
            )
            return
        }
        if (tile == TileId.DEFAULT_APPS) {
            DefaultAppsSheet(
                state = defaultApps(s),
                compact = compact,
                onRefresh = {},
                onSelect = { _, _ -> },
                onSetEnabled = {},
                onDismiss = {},
            )
            return
        }
        FeatureSheet(
            id = tile,
            state = state(s),
            actions = NOOP,
            compact = compact,
            onDismiss = {},
            choosingAppsFirst = s.optString("page") == "apps",
            previewCloudPilot = s.optString("cloudMode") == "custom" ||
                (tile == TileId.CLOUD && s.optString("cloudMode") == "factory"),
        )
    }

    /** A `modal-*` board: the ADB gate the startup policy draws for the fixture's phase. */
    @Composable
    fun Modal(fixture: JSONObject, compact: Boolean) {
        val s = fixture.getJSONObject("state")
        if (s.optBoolean("cloudPairGenerate")) {
            CloudPairConfirmationDialog(generating = true, compact = compact,
                onConfirm = {}, onDismiss = {})
            return
        }
        val snapshot = AdbRescueSnapshot(
            phase = AdbRescuePhase.valueOf(s.getString("gate")),
            systemSwitch = AdbSystemSwitch.valueOf(s.optString("systemSwitch", "UNKNOWN")),
        )
        AdbStartupOverlay(
            model = AdbStartupGatePolicy.overlay(snapshot),
            compact = compact,
            onPrimaryAction = {},
            onOpenRecovery = {},
            onOpenExplainer = {},
        )
    }

    /**
     * The service panel's state: the car's access, the instruments' screen, the displays, the
     * report and the split's journal. `trouble` puts two features in the states the app really shows
     * as waiting on the driver and as broken - HUD guidance that lost its access, a cloud link the
     * car refused.
     */
    private fun service(s: JSONObject): DenzaUiState {
        val displays = s.optJSONArray("displays") ?: JSONArray()
        val technical = s.optJSONArray("technical") ?: JSONArray()
        val journal = s.optJSONArray("journal") ?: JSONArray()
        var state = DenzaUiState(
            adbRescue = AdbRescueSnapshot(
                phase = AdbRescuePhase.valueOf(s.optString("adbPhase", "TRUSTED")),
                message = s.optString("adb"),
                details = s.optString("adbDetails").ifEmpty { null },
            ),
            clusterDisplayLabel = s.optString("cluster"),
            clusterDisplayAutomatic = "Экран 1 · 1920×720",
            clusterCandidates = (0 until displays.length()).map {
                val d = displays.getJSONArray(it)
                ClusterDisplayDescriptor(d.getInt(0), "ClusterDisplay", d.getInt(1), d.getInt(2), 160, 0, 0)
            },
            technicalDetails = (0 until technical.length()).joinToString("\n") { technical.getString(it) },
            splitJournal = (0 until journal.length()).joinToString("\n") { journal.getString(it) },
            cloudReportExportEnabled = s.optBoolean("cloudExport", false),
        )
        if (s.optBoolean("trouble", false)) {
            state = state.copy(
                hudGuidance = FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                    "Повторите настройку доступа",
                    resolution = FeatureResolution.RETRY,
                ),
                cloudLink = FeatureSnapshot(FeatureId.CLOUD_LINK, true, FeatureStatus.ERROR, message = "Не включилось"),
            )
        }
        return state
    }

    private fun state(s: JSONObject): DenzaUiState {
        var state = DenzaUiState()
        s.optJSONArray("navigation")?.let { nav ->
            val choices = (0 until nav.length()).map { i ->
                val o = nav.getJSONObject(i)
                NavigationAppChoice(
                    "fixture.navigation.$i", o.getString("name"), null,
                    o.optBoolean("selected", false), instruments = o.optBoolean("instruments", false),
                )
            }
            state = state.copy(
                navigationAppChoices = choices,
                navigationAppChoice = choices.firstOrNull { it.selected } ?: state.navigationAppChoice,
                navigationPlacements = s.optJSONArray("placements")?.let { p ->
                    (0 until p.length()).map { ClusterMapPlacement.valueOf(p.getString(it)) }
                } ?: ClusterMapPlacement.entries,
                navigationPlacement = ClusterMapPlacement.valueOf(s.optString("placement", "FULL")),
                navigationSteeringWheelButton = s.optBoolean("wheel", false),
                navigationButtonLabel = s.optString("buttonLabel", "На приборку"),
            )
        }
        if (s.has("mirrors")) {
            state = state.copy(
                mirrors = snapshot(FeatureId.MIRRORS, s.getBoolean("mirrors"), s),
                mirrorsPosition = MirrorsPosition.valueOf(s.optString("position", "SIDES")),
                mirrorsProcessing = s.optBoolean("processing", false),
            )
        }
        if (s.has("simulcast")) {
            val apps = names(s.getJSONArray("apps")).mapIndexed { i, (label, selected) ->
                SimulcastAppChoice("fixture.cast.$i", label, null, selected, true)
            }
            state = state.copy(
                simulcast = snapshot(FeatureId.SIMULCAST, s.getBoolean("simulcast"), s),
                appChoices = apps,
                selectedApps = apps.filter { it.selected },
                selectedAppCount = apps.count { it.selected },
                selectedAppLabels = apps.filter { it.selected }.map { it.label },
            )
        }
        if (s.has("cloud")) {
            state = state.copy(
                cloudLink = snapshot(FeatureId.CLOUD_LINK, s.getBoolean("cloud"), s),
                cloudWifiRetained = if (s.has("wifi")) s.getBoolean("wifi") else null,
                cloudMode = when (s.optString("cloudMode")) {
                    "factory" -> dev.denza.apps.feature.cloud.CloudSimMode.FACTORY
                    "custom" -> dev.denza.apps.feature.cloud.CloudSimMode.CUSTOM
                    else -> null
                },
                cloudIdentity = null,
            )
        }
        if (s.has("speakers")) {
            state = state.copy(speakerCovers = snapshot(FeatureId.SPEAKER_COVERS, s.getBoolean("speakers"), s))
        }
        return state
    }

    /** A feature on, at rest - or broken with the fixture's words when it says so. */
    private fun snapshot(id: FeatureId, on: Boolean, s: JSONObject): FeatureSnapshot =
        if (s.has("error")) {
            FeatureSnapshot(id, on, FeatureStatus.ERROR, message = s.getString("error"))
        } else {
            FeatureSnapshot(id, on, if (on) FeatureStatus.READY else FeatureStatus.OFF)
        }

    private fun defaultApps(s: JSONObject): DefaultAppsUiState {
        val roles = s.getJSONObject("roles")
        return DefaultAppsUiState(
            roles = DefaultAppRole.entries.map { role ->
                val label = roles.optString(role.name, "")
                if (label.isEmpty()) {
                    DefaultAppRoleUiState(role, status = DefaultAppRoleStatus.READY)
                } else {
                    val pkg = "fixture.role.${role.name.lowercase()}"
                    DefaultAppRoleUiState(
                        role = role,
                        selectedPackageName = pkg,
                        selectedLabel = label,
                        choices = listOf(DefaultAppChoice(pkg, label, null, true, known = true, stock = false)),
                        status = DefaultAppRoleStatus.READY,
                        providerConfirmed = true,
                    )
                }
            },
        )
    }

    private fun names(array: JSONArray): List<Pair<String, Boolean>> = (0 until array.length()).map {
        val o = array.getJSONObject(it)
        o.getString("name") to o.optBoolean("selected", false)
    }

    private val NOOP = DashboardActions(
        onToggleSimulcast = {}, onLaunchSimulcast = {}, onRepairSimulcast = {}, onChooseApps = {},
        onLoadAppChoices = {}, onToggleApp = {}, onToggleMirrors = {}, onMirrorsPosition = {},
        onMirrorsProcessing = {}, onPreviewMirrors = {}, onNavigationAction = {}, onNavigationPlacement = {},
        onNavigationSteeringWheelButton = {}, onChooseNavigationApp = {}, onSelectNavigationApp = {},
        onToggleSplitScreen = {}, onLaunchSplitScreen = {}, onSetWeatherEnabled = {}, onToggleHudGuidance = {},
        onToggleSpeakerCovers = {}, onRaiseSpeakerCovers = {}, onOpenSystemLanguage = {},
        onSetDefaultAppsEnabled = {}, onChooseFseApp = {}, onOpenClusterPicker = {}, onOpenService = {},
        onOpenSettings = {}, onLoadNavigationAppChoices = {}, onToggleCloudLink = {}, onSetCloudWifiRetained = {},
    )
}
