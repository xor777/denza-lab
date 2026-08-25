package dev.denza.apps.feature.vehicle

/**
 * The short allowlist of native `autoservice` feature ids the vehicle panel is
 * allowed to read, with the decoding rules proven on this car.
 *
 * Everything here comes from docs/vehicle-data-findings.md, "autoservice FID
 * protocol" and "Widget allowlist". Two rules the rest of the feature depends
 * on:
 *
 *  - reads only. Transact `5` is `getInt`, transact `7` is `getFloat`. Transact
 *    `6` (`setInt`) must never appear anywhere in this app.
 *  - shell identity only. These calls are issued through the local ADB client;
 *    the app process itself has no `BYDAUTO_*` permission and must not gain one.
 *
 * The catalog is deliberately not the vendor's ~8000-constant list: a widget
 * polls this handful and nothing else.
 */
internal enum class VehicleTransact(val code: Int) {
    /** `getInt(dev, fid)` */
    INT(5),

    /** `getFloat(dev, fid)` — IEEE-754 bits arrive in the parcel int. */
    FLOAT(7),
}

/**
 * How often a signal is worth re-reading. The hot set is what moves with the
 * pedal; the cold set is temperatures, tyres and charging state, which change
 * over minutes and would waste a shell round trip at panel cadence.
 */
internal enum class VehiclePoll { HOT, COLD }

/**
 * Plausibility gate for a decoded value.
 *
 * The Binder answers a failed or unsupported read with a sentinel rather than an
 * error, and some devices report max-range placeholders. Rather than blacklisting
 * magic numbers (2.55 bar is a real tyre pressure, `255` is a real raw reading),
 * every value must land inside the range its unit can physically occupy. A value
 * outside it is dropped, and the panel shows a dash — never a decoded sentinel.
 */
internal enum class VehicleKind {
    PERCENT,
    /** Traction pack / bus, hundreds of volts. */
    HIGH_VOLT,
    /** The 12 V rail. */
    LOW_VOLT,
    /** Celsius. `-40` exactly is the vendor's "no sensor" marker, not a reading. */
    TEMPERATURE,
    MILLIVOLT,
    /** Pack power. This car really can pull hundreds of kilowatts. */
    POWER_KW,
    /**
     * Power arriving from a charger, which cannot reach pack-power figures. The
     * separate gate exists because the wider one let a spike through: the panel
     * showed a three-hundred-kilowatt charge on a car parked on AC.
     */
    CHARGE_POWER,
    /** Range or odometer, kilometres. */
    DISTANCE_KM,
    COUNT,
    /** Kilo-ohms of insulation resistance. */
    INSULATION,
    /** Hours or minutes of a charging estimate. */
    DURATION,
    /** Charge gun state; 2 is "AC connected" on this car. */
    GUN,
    /** Engine revolutions. Scale unproven: read `0` with the engine stopped. */
    RPM,
    /** A warning lamp. No number — the cluster would either light it or not. */
    FLAG,
    /** Engine displacement in litres. */
    LITRES;

    fun accepts(value: Double): Boolean = when (this) {
        PERCENT -> value in 0.0..100.0
        HIGH_VOLT -> value in 60.0..1000.0
        LOW_VOLT -> value in 6.0..18.0
        TEMPERATURE -> value in -50.0..150.0 && value != -40.0
        MILLIVOLT -> value in 1000.0..5000.0
        POWER_KW -> value in -600.0..600.0
        CHARGE_POWER -> value in -1.0..160.0
        DISTANCE_KM -> value in 0.0..2_000_000.0
        COUNT -> value in 1.0..1000.0
        INSULATION -> value in 0.0..100_000.0
        DURATION -> value in 0.0..99.0
        GUN -> value in 0.0..8.0
        RPM -> value in 0.0..9000.0
        FLAG -> value in 0.0..255.0
        LITRES -> value in 0.3..10.0
    }
}

/**
 * One readable vehicle value: where it lives on the native Binder, how to turn
 * the parcel word into a number, and how often to ask.
 *
 * `device` values come from `android.hardware.bydauto.BYDAutoConstants`; the
 * comment on each entry is the session reading that proved it on this car.
 */
internal enum class VehicleSignal(
    val device: Int,
    val fid: Int,
    val transact: VehicleTransact,
    val poll: VehiclePoll,
    val kind: VehicleKind,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    /**
     * Read only while the engine page is the one being looked at.
     *
     * The combustion set is a third of the whole poll and none of it appears on
     * the other page, so paying for it there would slow the power figure down
     * for nothing.
     */
    val engineOnly: Boolean = false,
    /**
     * The raw word this one signal uses for "no reading", on top of the Binder
     * sentinels in [AutoserviceShell] that every signal shares.
     *
     * A CAN signal says "not available" by setting every bit of its own field,
     * so the pattern is a property of the signal's width rather than of its
     * unit: `0x1FFF` is nothing at all in a 13-bit field and an ordinary number
     * in a 16-bit one. Checked against the word before any scale or offset,
     * because that is what the bus actually sent.
     */
    val invalid: Int? = null,
) {
    // ---- hot: what the right foot changes ----
    POWER_KW(1012, 0x14400020, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.POWER_KW),
    SOC_PERCENT(1014, 0x4A505038, VehicleTransact.FLOAT, VehiclePoll.HOT, VehicleKind.PERCENT),
    PACK_VOLT(1009, 0x44400008, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.HIGH_VOLT),
    RAIL_12V(1001, 0x43400028, VehicleTransact.FLOAT, VehiclePoll.HOT, VehicleKind.LOW_VOLT),
    ODOMETER_KM(1014, 0x4A502010, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.DISTANCE_KM, scale = 0.1),

    // ---- cold: pack, drivetrain, charging ----
    SOH_PERCENT(1014, 0x44400028, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.PERCENT),

    /**
     * The BMS's own state of charge, in tenths of a percent.
     *
     * Two live sessions settle what this feature id is: it read `432` against a
     * 43 % display and `616` against a 62 % display. It is not remaining energy,
     * and the "43.2 kWh / 0.43 = 100 kWh pack" reading in an earlier note was
     * circular — dividing a state of charge by itself always lands near 100.
     * The owner puts this car's pack under 40 kWh.
     */
    BMS_SOC_PERCENT(1014, 0x44700028, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.PERCENT, scale = 0.1),
    PACK_TEMP_AVG(1014, 0x44700038, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE, offset = -40.0),
    PACK_TEMP_MIN(1014, 0x44700010, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE, offset = -40.0),
    PACK_TEMP_MAX(1014, 0x44700020, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE, offset = -40.0),
    CELL_MIN_MV(1014, 0x44600010, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.MILLIVOLT),
    CELL_MAX_MV(1014, 0x44600030, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.MILLIVOLT),
    CELL_COUNT(1001, 0x43A00008, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.COUNT),
    RANGE_KM(1014, 0x4A50203E, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.DISTANCE_KM),
    INSULATION_KOHM(1039, 0x43A00018, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.INSULATION),
    MOTOR_FRONT_C(1039, 0x46406018, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE),
    INVERTER_C(1039, 0x46406010, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE),
    MOTOR_REAR_LEFT_C(1039, 0x285001A8, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE),
    MOTOR_REAR_RIGHT_C(1039, 0x285001B0, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE),

    CHARGE_GUN(1009, 0x34400032, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.GUN),
    CHARGE_KW(1009, 0x32300018, VehicleTransact.FLOAT, VehiclePoll.COLD, VehicleKind.CHARGE_POWER),
    CHARGE_HOURS(1009, 0x32300028, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.DURATION),
    CHARGE_MINUTES(1009, 0x32300030, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.DURATION),

    // ---- combustion side, read only on the engine page ----
    // Live-verified 2026-08-23 with the engine stopped: every id below answered
    // and none returned a sentinel. Values were the resting ones (zeros and a
    // 2.0 litre displacement), so what is proven is that they are readable —
    // the rpm scale and the generation unit still need a run with the engine on.
    /**
     * Revolutions, with the bus's own "not available" word refused.
     *
     * Read live on 2026-08-25 with the car parked and the engine off: this id
     * answered `0x1FFF` - thirteen bits, all ones - while `ENGINE_RUNNING` read
     * `0`, and the panel printed it as **8191 об/мин** on a stopped engine. The
     * 2026-08-23 session read `0` here, so the difference is the engine ECU:
     * awake it reports a real zero, asleep the gateway hands back the invalid
     * pattern. `8191` is inside any plausible rpm range, so no range gate can
     * catch it - only the pattern can.
     */
    ENGINE_RPM(
        1012, 0x14400012, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.RPM,
        engineOnly = true, invalid = 0x1FFF,
    ),
    ENGINE_RUNNING(1012, 0x10D00038, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.FLAG, engineOnly = true),

    /**
     * What the engine is putting into the pack. On a series-parallel hybrid this
     * is the figure that says the engine is running as a generator rather than
     * driving the wheels.
     *
     * Not to be confused with `ENGINE_CHARGE_POWER` (`0x2ED00010`), which the
     * catalog names as if it were this and which in fact tracks pack power — it
     * read `-2` against a `+2.5` kW wall charge with the engine stopped.
     */
    GENERATION_KW(1006, 0x2610001F, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.POWER_KW, engineOnly = true),
    GENERATION_STATE(1006, 0x34F0000A, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    ENGINE_LITRES(1012, 0x40D00008, VehicleTransact.FLOAT, VehiclePoll.COLD, VehicleKind.LITRES, engineOnly = true),

    /**
     * The tank, which on a hybrid is the half of the drivetrain nothing else here reports.
     *
     * Both read on this car in the read-only sweep of 2026-08-23 and held steady across a full
     * engine start/stop cycle: level `53`, range `491` km, consistent with each other, and `488` km
     * against the same `53` two days later. A later paragraph of the same findings page says no
     * plain tank-level constant was found; that paragraph predates this reading and is wrong.
     *
     * A third id, `0x4A507027` on device `1007`, was read here as a low-fuel alarm and is now gone.
     * It read `0` on 2026-08-23 and `1` on 2026-08-25 against an unchanged `53 %` tank, which is
     * decisive: whatever it is, it is not this tank's alarm. It was driving the fuel figure to the
     * alert colour on a half-full tank. Nothing invented replaces it - the stock cluster has its own
     * low-fuel lamp, and a second opinion on one panel is worse than none.
     */
    FUEL_PERCENT(1014, 0x4A507040, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.PERCENT, engineOnly = true),
    FUEL_RANGE_KM(1014, 0x4A504038, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.DISTANCE_KM, engineOnly = true),

    // Warning lamps. Several ids per lamp are generation variants of the same
    // signal, the way the motor temperatures were: reading all of them and
    // taking the worst is cheaper than deciding which one this car uses.
    COOLANT_LEVEL_LOW_A(1007, 0x3D911028, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    COOLANT_LEVEL_LOW_B(1007, 0x3D901030, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    COOLANT_LEVEL_LOW_C(1007, 0x3D95D015, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    COOLANT_LEVEL_LOW_D(1012, 0x05500031, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    COOLANT_TEMP_HIGH(1007, 0x3D901016, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    MOTOR_COOLANT_TEMP_HIGH(1007, 0x3D91102A, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_LEVEL_LAMP(1007, 0x4A508040, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_LEVEL_LOW(1007, 0x3D901032, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_LEVEL_HIGH(1007, 0x3D901033, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_PRESSURE_LOW_A(1007, 0x3D911011, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_PRESSURE_LOW_B(1007, 0x3D901017, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_PRESSURE_LOW_C(1007, 0x29600008, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_PRESSURE_LOW_D(1007, 0x3D95D017, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_MONITOR_FAULT(1007, 0x3D911029, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    OIL_LIFE_DUE(1007, 0x24800014, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    TRANSMISSION_OIL_TEMP_HIGH(1007, 0x3D90102A, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG, engineOnly = true),
    ;

    companion object {
        val HOT: List<VehicleSignal> = entries.filter { it.poll == VehiclePoll.HOT }
        val COLD: List<VehicleSignal> = entries.filter { it.poll == VehiclePoll.COLD }

        /** The sweep for a given page: the engine set joins only when it shows. */
        fun sweep(poll: VehiclePoll, engine: Boolean): List<VehicleSignal> {
            val all = if (poll == VehiclePoll.HOT) HOT else COLD
            return if (engine) all else all.filter { !it.engineOnly }
        }
    }
}

/**
 * The one assumption the panel makes about a raw reading, kept in a single
 * place so a drive capture can overturn it with a one-line change.
 *
 * Sign of [VehicleSignal.POWER_KW]: parked on AC charge this car reported `-2`
 * on the power feature id while the charging device reported `+2.4` kW, so
 * positive is taken to mean energy leaving the battery. That is the likely
 * convention, not a proven one — docs/vehicle-data-findings.md lists the moving
 * capture that settles it. If acceleration turns out to read negative, flip
 * [POWER_POSITIVE_IS_DISCHARGE]; nothing else in the feature needs to change.
 */
internal object VehicleConvention {
    const val POWER_POSITIVE_IS_DISCHARGE = true

    /** Raw pack power as load: positive out of the battery, negative into it. */
    fun load(rawKw: Double?): Double? = when {
        rawKw == null -> null
        POWER_POSITIVE_IS_DISCHARGE -> rawKw
        else -> -rawKw
    }
}
