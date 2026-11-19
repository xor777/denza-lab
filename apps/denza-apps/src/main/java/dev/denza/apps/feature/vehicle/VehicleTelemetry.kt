package dev.denza.apps.feature.vehicle

/** Why the cluster dashboard has no numbers, when it has none. */
internal enum class VehicleAccess {
    /** Nothing read yet this session. */
    STARTING,

    /** At least one sweep answered. */
    READY,

    /**
     * The shell channel is closed to us; [VehicleTelemetry.message] says why.
     *
     * Declared by [VehicleLink], not by any one failed read: a missing key closes it at once,
     * anything else only once the reads have kept failing past the link's horizon. Before that a
     * failed read is a dropped one ([VehicleTelemetry.dropped]), and the panel's own staleness
     * rule takes the figures down.
     */
    UNAVAILABLE,
}

/**
 * One immutable reading of the car, published by [VehicleTelemetryHub] and drawn
 * by the cluster dashboard.
 *
 * A signal missing from [values] is missing on purpose: the call went
 * unanswered, or the value could not be true for its unit. The dashboard draws
 * a dash for it. Nothing here is ever defaulted to zero.
 */
internal data class VehicleTelemetry(
    val access: VehicleAccess = VehicleAccess.STARTING,
    val message: String = "",
    val values: Map<VehicleSignal, Double> = emptyMap(),
    /** The closed buckets of the last ten kilometres, oldest first. */
    val consumption: List<ConsumptionSample> = emptyList(),
    /**
     * And those same ten kilometres as the twenty bins both screens draw.
     *
     * Built once per sweep by [VehicleTelemetryHub] rather than per frame by whoever is drawing:
     * one history of one quantity, on the cluster and on the car page alike
     * (`docs/energy-display-contract.md` §2.3).
     */
    val chart: ConsumptionChartSnapshot = ConsumptionChartSnapshot.EMPTY,
    /** The engine's newest run: what the pack took while it ran, a point a second ([EngineCharge]). */
    val engineCharge: EngineChargeSnapshot = EngineChargeSnapshot.EMPTY,
    /** What this trip has cost so far, integrated by [TripEnergyLedger]. */
    val trip: TripEnergy = TripEnergy(),
    /**
     * The read behind this snapshot failed, and the link is not closed yet ([VehicleLink]).
     *
     * Such a snapshot carries no hot value - nothing answered - and the cold values and the history
     * as the last answered sweep left them ([VehicleDroppedRead]), so the car page loses its live
     * figures and keeps its captions (`docs/energy-display-contract.md` §4, «link lost / a dropped
     * read»). It is **not a packet**: the Contour's ages keep running across it, which is what takes
     * the cluster's figures down on their own horizons.
     */
    val dropped: Boolean = false,
    /**
     * When the sweep whose hot readings are in [values] was read: `SystemClock.elapsedRealtime()`
     * on the hub's thread, just after the reply was decoded. Zero when the snapshot carries no hot
     * reading of its own - starting, a dropped read, a closed link.
     *
     * A snapshot is published only when a sweep ends, so a reader that blocks - a helper starting,
     * a shell timing out - leaves the last one standing with nothing in it to say how old it is.
     * The Contour ages its readings on its own clock; a screen that reads each snapshot as it comes
     * needs this to apply the same horizon ([VehiclePoll.HOT]).
     */
    val hotReadAtMillis: Long = 0L,
) {

    operator fun get(signal: VehicleSignal): Double? = values[signal]

    /**
     * Whether the hot readings in [values] have outlived their horizon at [nowMillis]
     * (`elapsedRealtime`): [HOT_HORIZON_MS] since the sweep that read them, the Contour's own
     * `ContourScene.STALE_SECONDS`. False for a snapshot with no stamp ([hotReadAtMillis] zero),
     * which carries no hot reading of its own to age.
     */
    fun hotExpired(nowMillis: Long): Boolean =
        hotReadAtMillis > 0L && nowMillis - hotReadAtMillis >= HOT_HORIZON_MS

    /**
     * This snapshot once its hot readings have expired: what the hub publishes for a dropped read
     * ([VehicleDroppedRead]) - no hot value, the cold ones, the history and the trip - so a screen
     * that reads each snapshot as it comes draws an expired reading the way it draws an absent one.
     */
    fun withoutHot(): VehicleTelemetry = VehicleDroppedRead.snapshot(
        previous = access,
        cold = values.filterKeys { it.poll == VehiclePoll.COLD },
        consumption = consumption,
        chart = chart,
        engineCharge = engineCharge,
        trip = trip,
    )

    /**
     * A gun is in **and** the charger has agreed, which is two facts and used to be half of one.
     *
     * This read `gun >= 1` until 2026-09-06, on the strength of one parked session where the gun
     * answered `2` with 2.4 kW flowing - and the catalog's own note says only that **2 is AC
     * connected**, which is not the same as "anything from 1 up means connected". On the car it is
     * not: the head unit's second page printed «В БАТАРЕЮ ОТ ЗАРЯДКИ» over a pack that was plainly
     * discharging on the road, so this id reads at or above 1 with no charger anywhere.
     *
     * So the gate is the value the catalog stands behind and a charger that is actually doing
     * something. Both screens read this - the cluster's countdown hangs off it too - and the
     * failure it prevents is the worst kind this app has: a sentence that is wrong with confidence.
     *
     * Three conditions, each with its own job: the gun value the catalog stands behind, the
     * charger saying something of its own - kilowatts or an estimate, either will do, both come
     * from the same device - and a pack that is not being emptied, which is the physical check
     * that catches a gun decode this car has already proved wrong once.
     *
     * The conservative half of the trade is that a DC charge answering none of the charger's own
     * readings would go unnoticed. Missing a scene costs a driver nothing; naming the wrong one
     * costs the panel its credibility. `docs/vehicle-data-findings.md` carries what to capture at
     * the next charge to widen it.
     */
    val charging: Boolean
        get() = this[VehicleSignal.CHARGE_GUN] == GUN_AC_CONNECTED &&
            ((chargeKw ?: 0.0) > 0.0 || chargeMinutesLeft != null) &&
            (loadKw ?: 0.0) <= 0.0

    val chargeKw: Double? get() = this[VehicleSignal.CHARGE_KW]

    /**
     * What the car thinks is left of the charge, in minutes.
     *
     * The two ids are one estimate in two words, so either alone is still an answer; only both
     * missing means the car is not estimating.
     */
    val chargeMinutesLeft: Int?
        get() {
            val hours = this[VehicleSignal.CHARGE_HOURS]
            val minutes = this[VehicleSignal.CHARGE_MINUTES]
            if (hours == null && minutes == null) return null
            return ((hours ?: 0.0) * 60 + (minutes ?: 0.0)).toInt()
        }

    /** Pack power as load: positive leaves the battery. See [VehicleConvention]. */
    val loadKw: Double? get() = VehicleConvention.load(this[VehicleSignal.POWER_KW])

    /**
     * Whether the selector is in P, or null while nothing has answered.
     *
     * Null is not "moving": a trip is bounded by a switch we can read, and a switch that did not
     * answer bounds nothing.
     */
    val parked: Boolean? get() = this[VehicleSignal.GEARBOX_PARK]?.let { it >= 1.0 }

    /** Cell spread has no feature id of its own; it is max minus min. */
    val cellSpreadMv: Double?
        get() {
            val min = this[VehicleSignal.CELL_MIN_MV] ?: return null
            val max = this[VehicleSignal.CELL_MAX_MV] ?: return null
            return max - min
        }

    /**
     * The car's three drive motors in layout order: front, rear left, rear
     * right. The rear pair is per-side, not per-axle, so three separate
     * readings is the honest shape — one of them running hotter than the others
     * is exactly what the row is there to show.
     *
     * A field rather than a `get()`. A snapshot arrives four times a second and the cluster reads
     * it sixty, so a property that builds a list is fifteen collections per answer, thrown away
     * inside the frame that made them.
     */
    val motorTemps: List<Double?> = listOf(
        values[VehicleSignal.MOTOR_FRONT_C],
        values[VehicleSignal.MOTOR_REAR_LEFT_C],
        values[VehicleSignal.MOTOR_REAR_RIGHT_C],
    )

    /**
     * The window's own consumption, worked out once here rather than per frame.
     *
     * [consumption] is already the window - the hub puts the tail in the snapshot - so this is one
     * pass per sweep instead of a filter, a list and an average per frame. Net energy over known
     * road, signed: see [ConsumptionWindow.mean].
     */
    val consumptionMean: Double? = ConsumptionWindow.mean(consumption)

    /** And the road it is the mean of, which is what the unit beside it names. */
    val consumptionKm: Double = ConsumptionWindow.coveredKm(consumption)

    // ------------------------------------------------------------- combustion

    /**
     * Null when the signal has not answered yet, which is not the same as
     * stopped. Measured on the car across a full start/stop cycle this reads `0`
     * stopped and `3` running, including while spinning down, and `1` for the
     * cranking second (docs/vehicle-data-findings.md, 2026-09-18), which already
     * counts as running.
     */
    val engineRunning: Boolean? get() = this[VehicleSignal.ENGINE_RUNNING]?.let { it >= 1.0 }

    val engineRpm: Double? get() = this[VehicleSignal.ENGINE_RPM]

    companion object {
        /** The one gun value this car has been read at, on AC, with power flowing. */
        private const val GUN_AC_CONNECTED = 2.0

        /**
         * How long a hot reading stays true after its sweep: [VehiclePoll.HOT]'s horizon, the one
         * number the Contour ages its readings by too, in the milliseconds of [hotReadAtMillis].
         */
        val HOT_HORIZON_MS: Long = (VehiclePoll.HOT.staleSeconds * 1000f).toLong()
    }
}
