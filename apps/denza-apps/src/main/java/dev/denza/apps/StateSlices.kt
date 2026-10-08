package dev.denza.apps

import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.feature.adb.AdbRescueSnapshot
import dev.denza.apps.feature.adb.AdbRestoreSnapshot
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.locale.SystemLanguageSnapshot
import dev.denza.apps.feature.mirrors.MirrorsPosition

/**
 * The parts of the dashboard's state that are read from the car as a whole.
 *
 * Each slice is the fields one feature owns in [DenzaUiState], read together because they are
 * decided together - a tile's state and the panel rows beside it. A recompute reads slices into
 * [SliceReading]s, which are plain values, and lays them onto the state; two readings of the same
 * car are equal, so laying them again publishes nothing.
 */
enum class StateSlice {
    /**
     * The car's answer to this app's ADB key, and the automatic restore of it. Read with every
     * slice, because it decides whether the others are published at all: behind the gate the tiles
     * keep their last state.
     */
    ADB_ACCESS,
    SIMULCAST,
    MIRRORS,
    NAVIGATION,
    SPLIT_SCREEN,
    HUD_GUIDANCE,
    SPEAKER_COVERS,
    CLOUD_LINK,

    /** The screens the instruments can go to, and which one they go to: the service's page. */
    CLUSTER_DISPLAY,
    WEATHER,
    SYSTEM_LANGUAGE,
    ;

    companion object {
        /**
         * The slice a runtime feature's tile reads, or null for the one that is not read at all:
         * the passenger install publishes its own progress as it goes.
         */
        fun of(feature: FeatureId): StateSlice? = when (feature) {
            FeatureId.SIMULCAST -> SIMULCAST
            FeatureId.MIRRORS -> MIRRORS
            FeatureId.NAVIGATION -> NAVIGATION
            FeatureId.SPLIT_SCREEN -> SPLIT_SCREEN
            FeatureId.HUD_GUIDANCE -> HUD_GUIDANCE
            FeatureId.SPEAKER_COVERS -> SPEAKER_COVERS
            FeatureId.CLOUD_LINK -> CLOUD_LINK
            FeatureId.FSE_INSTALLER -> null
        }
    }
}

/** What one slice read, ready to be laid onto the state. Values only, so equal reads are equal. */
internal sealed interface SliceReading {
    val slice: StateSlice

    fun applyTo(state: DenzaUiState): DenzaUiState
}

/** The car's answer to the key, and where the automatic ADB restore stands: both shown at the gate. */
internal data class AdbAccessReading(
    val adbRescue: AdbRescueSnapshot,
    val adbRestore: AdbRestoreSnapshot = AdbRestoreSnapshot(),
) : SliceReading {
    override val slice get() = StateSlice.ADB_ACCESS

    override fun applyTo(state: DenzaUiState) = state.copy(adbRescue = adbRescue, adbRestore = adbRestore)
}

internal data class SimulcastReading(
    val snapshot: FeatureSnapshot,
    val selectedApps: List<SimulcastAppChoice>,
    val selectedAppCount: Int,
) : SliceReading {
    override val slice get() = StateSlice.SIMULCAST

    override fun applyTo(state: DenzaUiState) = state.copy(
        simulcast = snapshot,
        selectedApps = selectedApps,
        selectedAppLabels = selectedApps.map(SimulcastAppChoice::label),
        selectedAppCount = selectedAppCount,
    )
}

internal data class MirrorsReading(
    val snapshot: FeatureSnapshot,
    val position: MirrorsPosition,
    val processing: Boolean,
) : SliceReading {
    override val slice get() = StateSlice.MIRRORS

    override fun applyTo(state: DenzaUiState) = state.copy(
        mirrors = snapshot,
        mirrorsPosition = position,
        mirrorsProcessing = processing,
    )
}

internal data class NavigationReading(
    val snapshot: FeatureSnapshot,
    val buttonLabel: String,
    val steeringWheelButton: Boolean,
    val steeringWheelButtonReady: Boolean,
    val steeringWheelButtonRepairing: Boolean,
    val placement: ClusterMapPlacement,
    val placements: List<ClusterMapPlacement>,
    val appChoice: NavigationAppChoice,
) : SliceReading {
    override val slice get() = StateSlice.NAVIGATION

    override fun applyTo(state: DenzaUiState) = state.copy(
        navigation = snapshot,
        navigationButtonLabel = buttonLabel,
        navigationSteeringWheelButton = steeringWheelButton,
        navigationSteeringWheelButtonReady = steeringWheelButtonReady,
        navigationSteeringWheelButtonRepairing = steeringWheelButtonRepairing,
        navigationPlacement = placement,
        navigationPlacements = placements,
        navigationAppLabel = appChoice.label,
        navigationAppChoice = appChoice,
    )
}

internal data class SplitScreenReading(val snapshot: FeatureSnapshot) : SliceReading {
    override val slice get() = StateSlice.SPLIT_SCREEN

    override fun applyTo(state: DenzaUiState) = state.copy(splitScreen = snapshot)
}

internal data class HudGuidanceReading(val snapshot: FeatureSnapshot) : SliceReading {
    override val slice get() = StateSlice.HUD_GUIDANCE

    override fun applyTo(state: DenzaUiState) = state.copy(hudGuidance = snapshot)
}

internal data class SpeakerCoversReading(
    val snapshot: FeatureSnapshot,
    val reporting: Boolean,
) : SliceReading {
    override val slice get() = StateSlice.SPEAKER_COVERS

    override fun applyTo(state: DenzaUiState) = state.copy(
        speakerCovers = snapshot,
        speakerCoversReporting = reporting,
    )
}

internal data class CloudLinkReading(
    val snapshot: FeatureSnapshot,
    val wifiRetained: Boolean?,
    val busy: Boolean,
) : SliceReading {
    override val slice get() = StateSlice.CLOUD_LINK

    override fun applyTo(state: DenzaUiState) = state.copy(
        cloudLink = snapshot,
        cloudWifiRetained = wifiRetained,
        cloudLinkBusy = busy,
    )
}

internal data class ClusterDisplayReading(
    val candidates: List<ClusterDisplayDescriptor>,
    val label: String,
    val override: Int?,
    val automatic: String?,
) : SliceReading {
    override val slice get() = StateSlice.CLUSTER_DISPLAY

    override fun applyTo(state: DenzaUiState) = state.copy(
        clusterCandidates = candidates,
        clusterDisplayLabel = label,
        clusterDisplayOverride = override,
        clusterDisplayAutomatic = automatic,
    )
}

internal data class WeatherReading(
    val enabled: Boolean,
    val temperature: Int?,
    val updatedMillis: Long,
) : SliceReading {
    override val slice get() = StateSlice.WEATHER

    override fun applyTo(state: DenzaUiState) = state.copy(
        weatherEnabled = enabled,
        weatherTemperature = temperature,
        weatherUpdatedMillis = updatedMillis,
    )
}

internal data class SystemLanguageReading(val snapshot: SystemLanguageSnapshot) : SliceReading {
    override val slice get() = StateSlice.SYSTEM_LANGUAGE

    override fun applyTo(state: DenzaUiState) = state.copy(systemLanguage = snapshot)
}

/**
 * Reads [slices] one at a time through [read]. A slice that throws is left out and handed to
 * [failed]; the others are read and laid all the same, so one broken source - a component the
 * package manager no longer knows, a store that will not load - cannot hold every tile back.
 */
internal fun readEach(
    slices: Collection<StateSlice>,
    read: (StateSlice) -> SliceReading,
    failed: (StateSlice, RuntimeException) -> Unit,
): List<SliceReading> = slices.mapNotNull { slice ->
    try {
        read(slice)
    } catch (error: RuntimeException) {
        failed(slice, error)
        null
    }
}

/** [readings] laid onto the state in order. */
internal fun DenzaUiState.withReadings(readings: List<SliceReading>): DenzaUiState =
    readings.fold(this) { state, reading -> reading.applyTo(state) }
