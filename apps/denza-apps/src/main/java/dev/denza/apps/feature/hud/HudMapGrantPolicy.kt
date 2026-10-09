package dev.denza.apps.feature.hud

/** What asked for the screen-capture grant to be looked at. */
enum class HudMapGrantTrigger {
    /** The driver turned the map or the hints on: the panel is open, so our Activity is on screen. */
    SWITCH,

    /** `MainActivity` came to the front - opened, or its split pane focused again. */
    RESUME,
}

/** What the grant should do now. */
enum class HudMapGrantAction {
    NOTHING,

    /** Op 46 is allowed: ask SystemUI, which answers at once without a dialog. */
    REQUEST,

    /** Op 46 is not allowed: allow it over the local ADB shell first, then ask. */
    ALLOW_OP_THEN_REQUEST,

    /** The map or the hints went off: stop the projection, and with it the foreground type. */
    RELEASE,
}

/** Why there is no projection, for «Сервис»; [LIVE] when there is one. */
enum class HudMapGrantReason(val words: String) {
    LIVE("есть"),
    OFF("карта выключена"),
    WAITING_APP("откройте Denza Apps: разрешение берётся, пока приложение на экране"),
    REQUESTING("запрашиваю разрешение"),
    NO_ADB("нет разрешения на захват экрана, и ADB недоступен, чтобы его дать"),
    REFUSED("система не дала разрешения на захват экрана"),
    NO_SERVICE("служба захвата экрана не запустилась"),
    STOPPED("захват экрана остановлен системой или другой трансляцией"),
}

/**
 * When to ask for the screen-capture grant, as plain decisions over plain facts.
 *
 * The grant is asked for only from our own Activity on screen - the switch pressed in its panel, or
 * the Activity resumed - because SystemUI's `MediaProjectionPermissionActivity` names the caller by
 * `getCallingPackage()` and so must be started for a result by an Activity of ours. It is asked for
 * only with op 46 (`PROJECT_MEDIA`) allowed, which is what makes SystemUI answer at once
 * (`hasProjectionPermission`); the driver never sees the system's consent dialog. Without the op,
 * the local ADB shell allows it first.
 *
 * A request that failed is not repeated on every resume: a split pane resumes its Activity each
 * time it is focused, and the grant would loop. A resume asks again [RESUME_RETRY_MS] after the
 * last attempt; a press of the switch is the driver asking and goes after [SWITCH_RETRY_MS].
 */
object HudMapGrantPolicy {
    const val RESUME_RETRY_MS = 60_000L
    const val SWITCH_RETRY_MS = 3_000L

    fun decide(
        trigger: HudMapGrantTrigger,
        hintsOn: Boolean,
        mapOn: Boolean,
        live: Boolean,
        inFlight: Boolean,
        opAllowed: Boolean,
        lastAttemptMs: Long?,
        nowMs: Long,
    ): HudMapGrantAction {
        if (!hintsOn || !mapOn) {
            return if (live || inFlight) HudMapGrantAction.RELEASE else HudMapGrantAction.NOTHING
        }
        if (live || inFlight) return HudMapGrantAction.NOTHING
        val wait = when (trigger) {
            HudMapGrantTrigger.SWITCH -> SWITCH_RETRY_MS
            HudMapGrantTrigger.RESUME -> RESUME_RETRY_MS
        }
        if (lastAttemptMs != null && nowMs - lastAttemptMs < wait) return HudMapGrantAction.NOTHING
        return if (opAllowed) HudMapGrantAction.REQUEST else HudMapGrantAction.ALLOW_OP_THEN_REQUEST
    }

    /**
     * What «Сервис» says when [decide] chose nothing: off with the map or the hints off, waiting for
     * our Activity with no capture, no request under way and nobody to ask from, and otherwise
     * [current] stands - a refusal, a missing ADB, a capture stopped by the system - until the next
     * attempt replaces it.
     */
    fun idleReason(
        hintsOn: Boolean,
        mapOn: Boolean,
        live: Boolean,
        inFlight: Boolean,
        activityResumed: Boolean,
        current: HudMapGrantReason,
    ): HudMapGrantReason = when {
        !hintsOn || !mapOn -> HudMapGrantReason.OFF
        live -> HudMapGrantReason.LIVE
        inFlight -> HudMapGrantReason.REQUESTING
        !activityResumed && (current == HudMapGrantReason.OFF || current == HudMapGrantReason.REQUESTING) ->
            HudMapGrantReason.WAITING_APP
        else -> current
    }
}
