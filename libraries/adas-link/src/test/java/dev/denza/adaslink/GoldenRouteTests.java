package dev.denza.adaslink;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static dev.denza.adaslink.AdasLinkTest.check;
import static dev.denza.adaslink.AdasLinkTest.equal;

/** The car's stock route bytes, reproduced from the PathInfo they describe. */
final class GoldenRouteTests {
    static final String[] CAPTURES = {
            "captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.someip.bin",
            "captures/stock-map-car-lanes-20261023/reports/route-wire/resend-full/route-01.someip.bin"};

    static void run(File root) throws Exception {
        for (String name : CAPTURES) {
            Golden golden = new Golden(new File(root, name));
            StockPath path = golden.path();
            @SuppressWarnings("unchecked") Map<String, Object> send = (Map<String, Object>) Json.parse(golden.payload);
            int routeType = ((Number) send.get("RouteType")).intValue();
            String payload = StockRouteJson.messageSend(path, routeType, StockRouteJson.Strings.EN);
            equal(golden.payload, payload, name + ": MessageSend JSON byte for byte");
            long time = ((Number) golden.envelope.get("time")).longValue();
            List<String> chunks = StockRouteJson.chunks(payload, 1, time);
            equal(1, chunks.size(), name + ": one NaviDataDetail");
            equal(golden.wireJson, chunks.get(0), name + ": NaviDataDetail JSON byte for byte");
            check(Arrays.equals(golden.someip, golden.frame(chunks.get(0), 1)), name + ": native SOME/IP bytes reproduced");
            byte[] binder = StockMessages.route(chunks.get(0), 1);
            @SuppressWarnings("unchecked") Map<String, Object> struct =
                    (Map<String, Object>) Pb.NAVI_SD_ROUTE_NOTIFY.decode(binder).get("naviSDRouteStruct");
            equal(golden.wireJson, struct.get("navigationSDLink2"), name + ": Binder protobuf carries the JSON");
            equal(1L, struct.get("navigationSDLink2Num"), name + ": Binder path count");
            check(!struct.containsKey("checksCRC32") && !struct.containsKey("counter"), name + ": CRC/counter left at 0 as stock");
            // Stock content invariants the converter must keep (F3).
            for (StockPath.Segment s : path.segments) {
                long sum = 0;
                for (StockPath.Link l : s.links) sum += l.length;
                equal(s.length, sum, name + ": golden sum(link len) == navigationLen");
            }
            // A different locale changes the description only.
            String zh = StockRouteJson.messageSend(path, routeType, StockRouteJson.Strings.DEFAULT);
            check(zh.contains("行驶") && !zh.contains("travel"), name + ": default-locale description");
        }
    }
}
