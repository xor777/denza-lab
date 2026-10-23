package dev.denza.adaslink;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Entry for the JUnit wrapper and the oracles: runs the suite, returns the failure count. */
public final class AdasLinkTestAccess {
    public static int run(File root) throws Exception {
        AdasLinkTest.checks = 0;
        AdasLinkTest.failures = 0;
        AdasLinkTest.failed.clear();
        AdasLinkTest.run(root);
        return AdasLinkTest.failures;
    }

    public static List<String> failed() { return new ArrayList<>(AdasLinkTest.failed); }

    /** A converted recorded route, for the oracles. */
    public static StockPath route(File root, String name) throws Exception {
        RouteConverter.Converted c = RouteConverter.convert(YandexRoute.fromSnapshot(ConverterTests.read(new File(root, name))),
                Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
        c.path.pathId = 100002;
        return c.path;
    }

    public static String description(StockPath.Segment segment, String next) {
        return StockRouteJson.description(segment, next, StockRouteJson.Strings.EN);
    }

    public static String lastRoadName(StockPath.Segment segment) { return StockRouteJson.lastRoadName(segment); }

    private AdasLinkTestAccess() {}
}
