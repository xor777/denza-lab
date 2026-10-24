package dev.denza.adaslink;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-main tests of the adas-link library: run by tools/yandex_hud_build/build.py with the
 * repository root as the argument, and by Gradle through {@code AdasLinkJUnitTest}. Golden inputs
 * come from the git-ignored corpus under captures/; they are required, a missing file fails.
 */
public final class AdasLinkTest {
    static int checks, failures;
    static final List<String> failed = new ArrayList<>();

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; failed.add(what); System.out.println("FAIL " + what); }
    }

    static void equal(Object expected, Object actual, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            String e = String.valueOf(expected), a = String.valueOf(actual);
            int i = 0;
            while (i < Math.min(e.length(), a.length()) && e.charAt(i) == a.charAt(i)) i++;
            what += " (first difference at " + i + ": expected «" + e.substring(Math.max(0, i - 40), Math.min(e.length(), i + 60))
                    + "» got «" + a.substring(Math.max(0, i - 40), Math.min(a.length(), i + 60)) + "»)";
        }
        check(ok, what);
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        run(root);
        System.out.println("adas-link: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static void run(File root) throws Exception {
        GoldenRouteTests.run(root);
        ConverterTests.run(root);
        DynamicTests.run(root);
        RoadTests.run();
        GraphTileTest.main(new String[]{root.getAbsolutePath()});
        GraphTileCacheTest.main(new String[]{root.getAbsolutePath()});
        GraphRoadNetworkTest.main(new String[0]);
        LiveDriveRegressionTest.main(new String[0]);
    }
}
