package dev.denza.adaslink;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Host tool: route snapshot JSON to the stock MessageSend JSON, for the equivalence check against
 * tools/adas_bridge/stock.py. Usage: ConvertTool route.json out.json [wgs84|gcj] [pathId].
 */
public final class ConvertTool {
    public static void main(String[] args) throws Exception {
        Map<String, Object> snapshot = ConverterTests.read(new File(args[0]));
        Coordinates.Frame frame = args.length > 2 && args[2].equals("wgs84") ? Coordinates.Frame.WGS84
                : Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;
        RouteConverter.Converted c = RouteConverter.convert(YandexRoute.fromSnapshot(snapshot), frame);
        c.path.pathId = args.length > 3 ? Long.parseLong(args[3]) : 100002;
        String json = StockRouteJson.messageSend(c.path, 1, StockRouteJson.Strings.EN);
        try (FileOutputStream out = new FileOutputStream(args[1])) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
        System.out.println(c.path.segments.size() + " segments, " + c.path.linkCount() + " links, " + json.length() + " chars");
    }
}
