package dev.denza.apps.core

/** User-facing features owned by the consolidated Denza Apps runtime. */
enum class FeatureId {
    SIMULCAST,
    MIRRORS,
    NAVIGATION,
    SPLIT_SCREEN,
    HUD_GUIDANCE,
    SPEAKER_COVERS,
    FSE_INSTALLER,
}

/**
 * Small, non-technical states used by both the UI and the runtime coordinator.
 * Detailed errors remain available through [FeatureSnapshot.details].
 */
enum class FeatureStatus {
    OFF,
    STARTING,
    READY,
    ACTIVE,
    RECOVERING,
    NEEDS_ACTION,
    UNAVAILABLE,
    ERROR,
}

/** Known user resolutions. A resolution does not imply a new UI button. */
enum class FeatureResolution {
    SELECT_APPS,
    SELECT_CLUSTER_DISPLAY,
    SELECT_NAVIGATION_APP,
    CONFIRM_ON_CAR,
    ENABLE_CAR_DEBUGGING,
    RETRY,
}

data class FeatureSnapshot(
    val id: FeatureId,
    val desiredEnabled: Boolean,
    val status: FeatureStatus,
    val message: String = "",
    val details: String? = null,
    val resolution: FeatureResolution? = null,
) {
    val isWorking: Boolean
        get() = status == FeatureStatus.READY || status == FeatureStatus.ACTIVE
}

/**
 * Pure transition helpers. A recoverable runtime failure never changes the user's
 * desired setting; the coordinator can retry until the observed state catches up.
 */
object FeatureReducer {
    fun disabled(id: FeatureId): FeatureSnapshot = FeatureSnapshot(
        id = id,
        desiredEnabled = false,
        status = FeatureStatus.OFF,
    )

    fun starting(id: FeatureId): FeatureSnapshot = FeatureSnapshot(
        id = id,
        desiredEnabled = true,
        status = FeatureStatus.STARTING,
    )

    fun ready(id: FeatureId, active: Boolean = false): FeatureSnapshot = FeatureSnapshot(
        id = id,
        desiredEnabled = true,
        status = if (active) FeatureStatus.ACTIVE else FeatureStatus.READY,
    )

    fun recovering(
        previous: FeatureSnapshot,
        message: String,
        details: String? = null,
    ): FeatureSnapshot {
        if (!previous.desiredEnabled) return previous.copy(status = FeatureStatus.OFF)
        return previous.copy(
            status = FeatureStatus.RECOVERING,
            message = message,
            details = details,
            resolution = null,
        )
    }

    fun needsAction(
        previous: FeatureSnapshot,
        message: String,
        details: String? = null,
        resolution: FeatureResolution? = null,
    ): FeatureSnapshot = previous.copy(
        status = FeatureStatus.NEEDS_ACTION,
        message = message,
        details = details,
        resolution = resolution,
    )

    fun failed(
        previous: FeatureSnapshot,
        message: String,
        details: String? = null,
    ): FeatureSnapshot = previous.copy(
        status = FeatureStatus.ERROR,
        message = message,
        details = details,
        resolution = null,
    )
}
