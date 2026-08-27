package dev.denza.apps.feature.vehicle

/**
 * The short allowlist of native `autoservice` feature ids the cluster dashboard
 * is allowed to read, with the decoding rules proven on this car.
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
 * pedal; the cold set is temperatures, charging estimates and warning lamps,
 * which change slowly and would waste a shell round trip at dashboard cadence.
 */
internal enum class VehiclePoll { HOT, COLD }

/**
 * Plausibility gate for a decoded value.
 *
 * The Binder answers a failed or unsupported read with a sentinel rather than an
 * error, and some devices report max-range placeholders. Rather than blacklisting
 * magic numbers (`255` is a legal flag word and an illegal 215 °C reading),
 * every value must land inside the range its unit can physically occupy. A value
 * outside it is dropped, and the dashboard shows a dash — never a decoded sentinel.
 */
internal enum class VehicleKind {
    PERCENT,
    /** Traction pack / bus, hundreds of volts. */
    HIGH_VOLT,
    /** Celsius. `-40` exactly is the vendor's "no sensor" marker, not a reading. */
    TEMPERATURE,
    MILLIVOLT,
    /** Pack power. This car really can pull hundreds of kilowatts. */
    POWER_KW,
    /**
     * Power arriving from a charger, which cannot reach pack-power figures. The
     * separate gate exists because the wider one let a spike through: an early panel
     * showed a three-hundred-kilowatt charge on a car parked on AC.
     */
    CHARGE_POWER,
    /** Odometer, kilometres. */
    DISTANCE_KM,
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
    ;

    fun accepts(value: Double): Boolean = when (this) {
        PERCENT -> value in 0.0..100.0
        HIGH_VOLT -> value in 60.0..1000.0
        TEMPERATURE -> value in -50.0..150.0 && value != -40.0
        MILLIVOLT -> value in 1000.0..5000.0
        POWER_KW -> value in -600.0..600.0
        CHARGE_POWER -> value in -1.0..160.0
        DISTANCE_KM -> value in 0.0..2_000_000.0
        INSULATION -> value in 0.0..100_000.0
        DURATION -> value in 0.0..99.0
        GUN -> value in 0.0..8.0
        RPM -> value in 0.0..9000.0
        FLAG -> value in 0.0..255.0
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
    PACK_VOLT(1009, 0x44400008, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.HIGH_VOLT),
    ODOMETER_KM(1014, 0x4A502010, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.DISTANCE_KM, scale = 0.1),

    // ---- cold: pack, drivetrain, charging ----
    SOH_PERCENT(1014, 0x44400028, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.PERCENT),

    PACK_TEMP_AVG(1014, 0x44700038, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE, offset = -40.0),
    CELL_MIN_MV(1014, 0x44600010, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.MILLIVOLT),
    CELL_MAX_MV(1014, 0x44600030, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.MILLIVOLT),
    INSULATION_KOHM(1039, 0x43A00018, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.INSULATION),
    MOTOR_FRONT_C(1039, 0x46406018, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE),
    INVERTER_C(1039, 0x46406010, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE),
    MOTOR_REAR_LEFT_C(1039, 0x285001A8, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE),
    MOTOR_REAR_RIGHT_C(1039, 0x285001B0, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.TEMPERATURE),

    CHARGE_GUN(1009, 0x34400032, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.GUN),
    CHARGE_KW(1009, 0x32300018, VehicleTransact.FLOAT, VehiclePoll.COLD, VehicleKind.CHARGE_POWER),
    CHARGE_HOURS(1009, 0x32300028, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.DURATION),
    CHARGE_MINUTES(1009, 0x32300030, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.DURATION),

    // ---- combustion side, shown by the cluster dashboard ----
    // Live-verified 2026-08-23 with the engine stopped: every id below answered
    // and none returned a sentinel. Values were the resting ones (zeros), so
    // what is proven is that they are readable —
    // the rpm scale and the generation unit still need a run with the engine on.
    /**
     * Revolutions, with the bus's own "not available" word refused.
     *
     * Read live on 2026-08-25 with the car parked and the engine off: this id
     * answered `0x1FFF` - thirteen bits, all ones - while `ENGINE_RUNNING` read
     * `0`, and the retired panel printed it as **8191 об/мин** on a stopped engine. The
     * 2026-08-23 session read `0` here, so the difference is the engine ECU:
     * awake it reports a real zero, asleep the gateway hands back the invalid
     * pattern. `8191` is inside any plausible rpm range, so no range gate can
     * catch it - only the pattern can.
     */
    ENGINE_RPM(
        1012, 0x14400012, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.RPM,
        invalid = 0x1FFF,
    ),
    ENGINE_RUNNING(1012, 0x10D00038, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.FLAG),

    /**
     * What the engine is putting into the pack. On a series-parallel hybrid this
     * is the figure that says the engine is running as a generator rather than
     * driving the wheels.
     *
     * Not to be confused with `ENGINE_CHARGE_POWER` (`0x2ED00010`), which the
     * catalog names as if it were this and which in fact tracks pack power — it
     * read `-2` against a `+2.5` kW wall charge with the engine stopped.
     */
    GENERATION_KW(1006, 0x2610001F, VehicleTransact.INT, VehiclePoll.HOT, VehicleKind.POWER_KW),
    GENERATION_STATE(1006, 0x34F0000A, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),

    // Warning lamps. Several ids per lamp are generation variants of the same
    // signal, the way the motor temperatures were: reading all of them and
    // taking the worst is cheaper than deciding which one this car uses.
    COOLANT_LEVEL_LOW_A(1007, 0x3D911028, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    COOLANT_LEVEL_LOW_B(1007, 0x3D901030, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    COOLANT_LEVEL_LOW_C(1007, 0x3D95D015, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    COOLANT_LEVEL_LOW_D(1012, 0x05500031, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    COOLANT_TEMP_HIGH(1007, 0x3D901016, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    MOTOR_COOLANT_TEMP_HIGH(1007, 0x3D91102A, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_LEVEL_LAMP(1007, 0x4A508040, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_LEVEL_LOW(1007, 0x3D901032, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_LEVEL_HIGH(1007, 0x3D901033, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_PRESSURE_LOW_A(1007, 0x3D911011, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_PRESSURE_LOW_B(1007, 0x3D901017, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_PRESSURE_LOW_C(1007, 0x29600008, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_PRESSURE_LOW_D(1007, 0x3D95D017, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_MONITOR_FAULT(1007, 0x3D911029, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    OIL_LIFE_DUE(1007, 0x24800014, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    TRANSMISSION_OIL_TEMP_HIGH(1007, 0x3D90102A, VehicleTransact.INT, VehiclePoll.COLD, VehicleKind.FLAG),
    ;

    companion object {
        val HOT: List<VehicleSignal> = entries.filter { it.poll == VehiclePoll.HOT }
        val COLD: List<VehicleSignal> = entries.filter { it.poll == VehiclePoll.COLD }
    }
}

/**
 * The one assumption the dashboard makes about a raw reading, kept in a single
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
