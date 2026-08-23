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
    GUN;

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
    ;

    companion object {
        val HOT: List<VehicleSignal> = entries.filter { it.poll == VehiclePoll.HOT }
        val COLD: List<VehicleSignal> = entries.filter { it.poll == VehiclePoll.COLD }
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
