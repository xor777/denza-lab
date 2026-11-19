package dev.denza.apps.feature.simulcast

/** What one request to stop the projection stops. */
enum class SimulcastStop {
    /** The share this process started and still holds. */
    OWNED_BRIDGE,

    /** Whatever DiShare is sharing now, read from DiShare and stopped by its session id. */
    DISHARE_CURRENT,

    /** Nothing: the share on the screens, if any, is not ours to end. */
    NOTHING,
}

/** Who asks [SimulcastOverlayService] to stop. */
enum class SimulcastStopRequest {
    /**
     * The red exit control (and the debug build's stop broadcast). It is on screen only while
     * this process has a cast on record, so pressing it means "end the share I see".
     */
    EXIT_CONTROL,

    /**
     * «Поддержка трансляции» switched off. That is a setting for our row and exit control, not a
     * stop button for DiShare: a share the driver started from the stock dialog stays.
     */
    SWITCH_OFF,
}

object SimulcastStopPolicy {
    /**
     * Until 2026-11-19 the switch going off took the exit control's path, whose fallback reads
     * DiShare's current session and stops it: with no bridge of ours, that ended a share started
     * from the stock app.
     */
    @JvmStatic
    fun decide(request: SimulcastStopRequest, ownsBridge: Boolean): SimulcastStop = when {
        ownsBridge -> SimulcastStop.OWNED_BRIDGE
        request == SimulcastStopRequest.EXIT_CONTROL -> SimulcastStop.DISHARE_CURRENT
        else -> SimulcastStop.NOTHING
    }
}
