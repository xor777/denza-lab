package dev.denza.apps.feature.diagnostics.bus;

/**
 * The only requests the diagnostic section may put on the car's diagnostic channel.
 *
 * <p>One class for both sides: the app refuses a request when it is made ({@code DiagRequest}),
 * and the shell-UID helper, which has this file packed beside it, refuses it again before the
 * bus. Every allowed shape only reads - identification and the VIN, DTC memory, a code's snapshot
 * and extended data, OBD data, freeze frame and test results. No session, reset, clear, security
 * access, communication control, write, I/O control, routine, tester-present, DTC setting or
 * functional request (docs/vehicle-diagnostics-architecture.md, 3.4). The VIN, `19 04`, `19 06`,
 * OBD `02`, `06` and `09 02` are read since the owner's decision of 2026-10-23 (7.3).
 */
public final class DiagnosticWhitelist {
    private DiagnosticWhitelist() {}

    /** Physical request ids; the ECU answers on request + 8, so the last is 0x7F7. */
    public static final int MIN_ADDRESS = 0x700;
    public static final int MAX_ADDRESS = 0x7F7;
    /**
     * The 5xx routes of the complete factory map that answered F194 on this car on 2026-10-20
     * (research/vehicle-diagnostics/factory-candidates-20261020.json), one by one, not a range.
     */
    private static final int[] FACTORY_5XX = {
        0x582, 0x590, 0x595, 0x597, 0x5A0, 0x5A7, 0x5B0, 0x5B1, 0x5B2, 0x5E0, 0x5E4, 0x5E7, 0x5F4,
    };
    /**
     * Software codes a parent block reports for a module behind it, as exact {address, DID} pairs:
     * the seat massage modules through 750, 7B5 and 7D2, the rear lamps through 726. Each answered
     * on this car on 2026-10-21 with the code the stock cache keeps
     * (research/vehicle-diagnostics/child-identities-live-20261021.json); the owner approved
     * reading them. Only at that parent, never as a range.
     */
    private static final int[][] CHILD_IDENTITIES = {
        {0x750, 0xF8C4}, {0x7B5, 0xF8D4}, {0x7D2, 0xF8E4}, {0x7D2, 0xF8F4},
        {0x726, 0xF204}, {0x726, 0xF214}, {0x726, 0xF064}, {0x726, 0xF234},
    };
    /**
     * A block's own identification beyond the standard DIDs, as exact {address, DID} pairs: the passenger
     * computer's combined software code F294 and its ASCII build version F295 at 5E7, the pair the stock
     * updater reads for it, answered on this car on 2026-10-21
     * (research/vehicle-diagnostics/ota-assy-fields-live-20261021.json); the owner approved reading them.
     */
    private static final int[][] OWN_IDENTITIES = {
        {0x5E7, 0xF294}, {0x5E7, 0xF295},
    };
    /** One CAN single frame: an OBD 01 with six PIDs is the longest request. */
    public static final int MAX_UDS_LENGTH = 7;

    /** Null when the request is allowed, otherwise why not, in a few words. */
    public static String refusal(int address, byte[] uds) {
        if ((address < MIN_ADDRESS || address > MAX_ADDRESS) && !factory5xx(address)) {
            return "address outside 700..7F7 and the answering 5xx routes";
        }
        if (address == 0x7DF) return "functional OBD address";
        if (address >= 0x7E8 && address <= 0x7EF) return "OBD response address";
        if (uds == null || uds.length == 0 || uds.length > MAX_UDS_LENGTH) return "UDS length outside 1..7";
        int sid = uds[0] & 0xFF;
        switch (sid) {
            case 0x22: {
                if (uds.length != 3) return "22 reads one DID";
                int did = ((uds[1] & 0xFF) << 8) | (uds[2] & 0xFF);
                return identification(did) || did == VIN || pair(CHILD_IDENTITIES, address, did) || pair(OWN_IDENTITIES, address, did)
                        ? null : "22 only F190, F193, F194, F195, F18A, F197, the child codes at their parent and 5E7's F294, F295";
            }
            case 0x19:
                return memory(uds) || codeRecord(uds)
                        ? null : "19 only 01 or 02 with a status mask, 04 or 06 with a code and a record number";
            case 0x01:
                return uds.length >= 2 ? null : "OBD 01 needs 1..6 PIDs";
            case 0x02:
                // Freeze frame: PID and frame number in pairs, one to three of them.
                return uds.length == 3 || uds.length == 5 || uds.length == 7 ? null : "OBD 02 takes 1..3 PID and frame pairs";
            case 0x03:
            case 0x07:
            case 0x0A:
                return uds.length == 1 ? null : "OBD 03, 07 and 0A take no parameter";
            case 0x06:
                return uds.length == 2 ? null : "OBD 06 takes one MID";
            case 0x09:
                return uds.length == 2 && (uds[1] == 0x00 || uds[1] == 0x02 || uds[1] == 0x04 || uds[1] == 0x0A)
                        ? null : "OBD 09 only 00, 02, 04, 0A";
            default:
                return String.format("service %02X not allowed", sid);
        }
    }

    public static boolean allowed(int address, byte[] uds) {
        return refusal(address, uds) == null;
    }

    private static boolean factory5xx(int address) {
        for (int allowed : FACTORY_5XX) if (allowed == address) return true;
        return false;
    }

    /**
     * ISO 14229's identification DIDs the section reads at every block: the supplier's software code,
     * software version and hardware version (F193, answered positively at 64 addresses on 2026-10-21),
     * the supplier and the system name.
     */
    private static boolean identification(int did) {
        return did == 0xF193 || did == 0xF194 || did == 0xF195 || did == 0xF18A || did == 0xF197;
    }

    /** ISO 14229's VIN DID, read at every block the list lets through (owner's decision 2026-10-23). */
    private static final int VIN = 0xF190;

    /** {@code 19 01 <mask>} and {@code 19 02 <mask>}: the number and the list of the DTCs a status mask matches. */
    private static boolean memory(byte[] uds) {
        return uds.length == 3 && (uds[1] == 0x01 || uds[1] == 0x02);
    }

    /**
     * {@code 19 04 <DTC> <record>} and {@code 19 06 <DTC> <record>}: one code's snapshot and extended data,
     * a record number or FF for all of them. Six bytes, one CAN frame; {@code 19 03} is not needed.
     */
    private static boolean codeRecord(byte[] uds) {
        return uds.length == 6 && (uds[1] == 0x04 || uds[1] == 0x06);
    }

    private static boolean pair(int[][] pairs, int address, int did) {
        for (int[] pair : pairs) if (pair[0] == address && pair[1] == did) return true;
        return false;
    }
}
