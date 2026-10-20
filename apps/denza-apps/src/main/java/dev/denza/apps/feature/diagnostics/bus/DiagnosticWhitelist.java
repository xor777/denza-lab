package dev.denza.apps.feature.diagnostics.bus;

/**
 * The only requests the diagnostic section may put on the car's diagnostic channel.
 *
 * <p>One class for both sides: the app refuses a request when it is made ({@code DiagRequest}),
 * and the shell-UID helper, which has this file packed beside it, refuses it again before the
 * bus. Every allowed shape only reads - identification, DTC memory, OBD data. No session,
 * reset, clear, security access, communication control, write, I/O control, routine,
 * tester-present, DTC setting, functional request or VIN read
 * (docs/vehicle-diagnostics-architecture.md, 3.4).
 */
public final class DiagnosticWhitelist {
    private DiagnosticWhitelist() {}

    /** Physical request ids; the ECU answers on request + 8, so the last is 0x7F7. */
    public static final int MIN_ADDRESS = 0x700;
    public static final int MAX_ADDRESS = 0x7F7;
    /** One CAN single frame: an OBD 01 with six PIDs is the longest request. */
    public static final int MAX_UDS_LENGTH = 7;

    /** Null when the request is allowed, otherwise why not, in a few words. */
    public static String refusal(int address, byte[] uds) {
        if (address < MIN_ADDRESS || address > MAX_ADDRESS) return "address outside 700..7F7";
        if (address == 0x7DF) return "functional OBD address";
        if (address >= 0x7E8 && address <= 0x7EF) return "OBD response address";
        if (uds == null || uds.length == 0 || uds.length > MAX_UDS_LENGTH) return "UDS length outside 1..7";
        int sid = uds[0] & 0xFF;
        switch (sid) {
            case 0x22:
                return uds.length == 3 && identification(((uds[1] & 0xFF) << 8) | (uds[2] & 0xFF))
                        ? null : "22 only F194, F195, F18A, F197";
            case 0x19:
                return uds.length == 3 && (uds[1] == 0x01 || uds[1] == 0x02)
                        ? null : "19 only 01 or 02 with a status mask";
            case 0x01:
                return uds.length >= 2 ? null : "OBD 01 needs 1..6 PIDs";
            case 0x03:
            case 0x07:
            case 0x0A:
                return uds.length == 1 ? null : "OBD 03, 07 and 0A take no parameter";
            case 0x09:
                return uds.length == 2 && (uds[1] == 0x00 || uds[1] == 0x04 || uds[1] == 0x0A)
                        ? null : "OBD 09 only 00, 04, 0A";
            default:
                return String.format("service %02X not allowed", sid);
        }
    }

    public static boolean allowed(int address, byte[] uds) {
        return refusal(address, uds) == null;
    }

    private static boolean identification(int did) {
        return did == 0xF194 || did == 0xF195 || did == 0xF18A || did == 0xF197;
    }
}
