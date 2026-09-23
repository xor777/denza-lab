package dev.denza.apps.ui

import androidx.compose.runtime.Composable
import dev.denza.apps.DenzaUiState
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.core.FeatureId
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
            DiagnosticsDialog(
                state = service(s),
                compactLayout = compact,
                onSelectClusterDisplay = {},
                onCheckAdbAccess = {},
                onRequestAdbAuthorizationOnce = {},
                onAllowNewAdbAuthorizationAttempt = {},
                onDismiss = {},
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
        )
    }

    /** A `modal-*` board: the ADB gate the startup policy draws for the fixture's phase. */
    @Composable
    fun Modal(fixture: JSONObject, compact: Boolean) {
        val s = fixture.getJSONObject("state")
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

    /** The service panel's state: the car's access, the instruments' screen and the displays. */
    private fun service(s: JSONObject): DenzaUiState {
        val displays = s.optJSONArray("displays") ?: JSONArray()
        return DenzaUiState(
            adbRescue = AdbRescueSnapshot(
                phase = AdbRescuePhase.TRUSTED,
                message = s.optString("adb"),
                details = s.optString("adbDetails").ifEmpty { null },
            ),
            clusterDisplayLabel = s.optString("cluster"),
            clusterCandidates = (0 until displays.length()).map {
                val d = displays.getJSONArray(it)
                ClusterDisplayDescriptor(d.getInt(0), "ClusterDisplay", d.getInt(1), d.getInt(2), 160, 0, 0)
            },
        )
    }

    private fun state(s: JSONObject): DenzaUiState {
        var state = DenzaUiState()
        s.optJSONArray("navigation")?.let { nav ->
            state = state.copy(
                navigationAppChoices = names(nav).mapIndexed { i, (label, selected) ->
                    NavigationAppChoice("fixture.navigation.$i", label, null, selected)
                },
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
        onOpenSettings = {},
    )
}
