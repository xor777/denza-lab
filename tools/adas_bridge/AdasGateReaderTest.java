package dev.denza.adasprobe;

/** The integer-get reply must never reinterpret an exception, float or parcel offset as a gate. */
public final class AdasGateReaderTest {
    public static void main(String[] args) {
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 00000001   '........')\n") == 1);
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 00000000   '........')") == 0);
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 ffffd8e5   '........')") == -10011);
        check(AdasGateReader.parseInteger("Result: Parcel(NULL)") == null);
        check(AdasGateReader.parseInteger("Result: Parcel(ffffffff 00000001 '........')") == null);
        check(AdasGateReader.parseInteger("Result: Parcel(\n0x00000000: 00000000 00000001 '........'\n)") == null);
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 00000001 00000002 '............')") == null);
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 00000001 '........')\nResult: Parcel(00000000 00000002 '........')") == null);
        check(AdasGateReader.parseInteger("permission denied") == null);
        check(AdasGateReader.stockAbsent("NAME\ninit\nru.yandex.yandexnavi\n"));
        check(!AdasGateReader.stockAbsent("NAME\ncom.byd.launchermap\n"));
        check(!AdasGateReader.stockAbsent("NAME\ncom.byd.launchermap:service\n"));
        check(!AdasGateReader.stockAbsent("PID NAME\n123 init\n"));
        check(!AdasGateReader.stockAbsent("NAME\n"));
        check(!AdasGateReader.stockAbsent("permission denied"));
        check(!AdasGateReader.stockAbsent("NAME\nru.yandex.yandex\n"));
        check(!AdasGateReader.stockAbsent("NAME\ninit\n"));
        String heartbeat = "{\"schema\":\"denza-adas-consumer-v1\",\"consumerSession\":\"consumer\",\"bootId\":\"boot\",\"elapsedMs\":1001}";
        long[] clock = {1000};
        check(AdasGateReader.consumerAlive("consumer", "boot", () -> { clock[0]=1002; return heartbeat; }, () -> clock[0]));
        check(AdasGateReader.consumerAlive("consumer", "boot", () -> heartbeat, () -> 13001)); // a 12 s restart keeps the reader
        check(AdasGateReader.consumerAlive("consumer", "boot", () -> heartbeat, () -> 61001));
        check(!AdasGateReader.consumerAlive("consumer", "boot", () -> heartbeat, () -> 61002));
        check(!AdasGateReader.consumerAlive("consumer", "boot", () -> heartbeat, () -> 1000));
        check(!AdasGateReader.consumerAlive("other", "boot", () -> heartbeat, () -> 1002));
        check(!AdasGateReader.consumerAlive("consumer", "other", () -> heartbeat, () -> 1002));
        check(!AdasGateReader.consumerAlive("consumer", "boot", () -> "{}", () -> 1002));
        System.out.println("AdasGateReaderTest: 24 checks passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("parcel integer decode"); }
}
