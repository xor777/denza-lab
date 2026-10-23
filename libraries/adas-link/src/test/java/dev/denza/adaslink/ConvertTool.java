package dev.denza.adaslink;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Host tool: route snapshot JSON to the stock MessageSend JSON, for the equivalence check against
 * tools/adas_bridge/stock.py. Usage: ConvertTool route.json out.json [wgs84|gcj] [pathId] [roads.bin match.txt].
 * With a road store the route is matched onto it, and match.txt gets the match as exact double
 * bits: one "cut BITS" line per cut, "piece BEGIN END FRAGMENT REVERSE" per piece, "speed BITS" and
 * "legal KMH" per link.
 */
public final class ConvertTool {
    public static void main(String[] args) throws Exception {
        Map<String, Object> snapshot = ConverterTests.read(new File(args[0]));
        Coordinates.Frame frame = args.length > 2 && args[2].equals("wgs84") ? Coordinates.Frame.WGS84
                : Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;
        RoadNetwork roads = args.length > 4 ? new RoadStore(ByteBuffer.wrap(Files.readAllBytes(new File(args[4]).toPath()))) : null;
        RouteConverter.Converted c = RouteConverter.convert(YandexRoute.fromSnapshot(snapshot), frame, roads);
        c.path.pathId = args.length > 3 ? Long.parseLong(args[3]) : 100002;
        String json = StockRouteJson.messageSend(c.path, 1, StockRouteJson.Strings.EN);
        try (FileOutputStream out = new FileOutputStream(args[1])) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
        if (args.length > 5) try (PrintStream out = new PrintStream(new FileOutputStream(args[5]), true, "UTF-8")) {
            RoadMatcher.Match m = c.roadMatch;
            if (m != null) {
                out.println("stats " + m.samples + " " + m.matchedSamples + " " + m.runs + " " + m.keptRuns + " " + bits(m.matchedM));
                for (double s : m.cuts) out.println("cut " + bits(s));
                for (double[] p : m.pieces) out.println("piece " + bits(p[0]) + " " + bits(p[1]) + " " + (int) p[2] + " " + (int) p[3]);
            }
            for (double s : c.linkSpeedKmh) out.println("speed " + bits(s));
            for (int v : c.linkLegalKmh) out.println("legal " + v);
        }
        System.out.println(c.path.segments.size() + " segments, " + c.path.linkCount() + " links, " + json.length() + " chars");
    }

    static String bits(double d) { return Long.toHexString(Double.doubleToLongBits(d)); }
}
