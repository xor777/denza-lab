package dev.denza.adaslink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The car's own stock route (captures/stock-map-car-lanes-20261023, git-ignored corpus) read back
 * into a {@link StockPath}, and the IVI's native SOME/IP framing of 43/0x8002 for comparing bytes.
 */
final class Golden {
    final byte[] someip;
    final String wireJson;
    final Map<String, Object> envelope;
    final String payload;

    Golden(File someipFile) throws IOException {
        someip = Files.readAllBytes(someipFile.toPath());
        ByteBuffer b = ByteBuffer.wrap(someip);
        if ((b.getShort(0) & 0xFFFF) != 43 || (b.getShort(2) & 0xFFFF) != 0x8002) throw new IllegalStateException("not 43/0x8002");
        int strlen = b.getInt(16 + 7);
        byte[] string = new byte[strlen];
        System.arraycopy(someip, 27, string, 0, strlen);
        if ((string[0] & 255) != 0xEF || string[strlen - 1] != 0) throw new IllegalStateException("no BOM/NUL");
        wireJson = new String(string, 3, strlen - 4, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked") Map<String, Object> env = (Map<String, Object>) Json.parse(wireJson);
        envelope = env;
        payload = (String) env.get("payload");
    }

    /** The IVI's framing of a Binder route event: header from the capture, CRC/counter/count/len, BOM, JSON, NUL. */
    byte[] frame(String json, int pathCount) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteBuffer fixed = ByteBuffer.allocate(11);
        fixed.putInt(0).put((byte) 0).putShort((short) pathCount).putInt(text.length + 4);
        body.write(fixed.array(), 0, 11);
        body.write(0xEF); body.write(0xBB); body.write(0xBF);
        body.write(text, 0, text.length);
        body.write(0);
        byte[] bodyBytes = body.toByteArray();
        ByteBuffer out = ByteBuffer.allocate(16 + bodyBytes.length);
        out.put(someip, 0, 4).putInt(bodyBytes.length + 8).put(someip, 8, 8).put(bodyBytes);
        return out.array();
    }

    /** The NaviToEHPData of the payload as the PathInfo it was made from (all time on segment 0). */
    @SuppressWarnings("unchecked")
    StockPath path() {
        Map<String, Object> send = (Map<String, Object>) Json.parse(payload);
        Map<String, Object> data = (Map<String, Object>) ((List<Object>) send.get("data")).get(0);
        List<Object> links = (List<Object>) data.get("links"), pnts = (List<Object>) data.get("pnts");
        Map<String, Object> group = (Map<String, Object>) ((List<Object>) data.get("guideGroups")).get(0);
        StockPath path = new StockPath();
        path.pathId = ((Number) data.get("pathID")).longValue();
        for (Object t : (List<Object>) data.get("trafficLights")) {
            Map<String, Object> m = (Map<String, Object>) t;
            path.trafficLights.add(new double[]{((Number) m.get("x")).doubleValue(), ((Number) m.get("y")).doubleValue()});
        }
        boolean first = true;
        for (Object o : (List<Object>) group.get("segments")) {
            Map<String, Object> s = (Map<String, Object>) o;
            StockPath.Segment segment = new StockPath.Segment();
            segment.length = num(s, "navigationLen");
            segment.travelTime = first ? num(group, "groupTime") : 0;
            first = false;
            segment.mainAction = (int) num(s, "navigationMainAction");
            segment.assistantAction = (int) num(s, "navigationAssitAction");
            segment.trafficLightNum = num(s, "trafficLightNum");
            int begin = (int) num(s, "linkBegIdx"), count = (int) num(s, "crntSegmLinkCnt");
            for (int i = begin; i < begin + count; i++) {
                Map<String, Object> l = (Map<String, Object>) links.get(i);
                StockPath.Link link = new StockPath.Link();
                link.topoId = num(l, "linkID");
                link.roadClass = (int) num(l, "roadclass");
                link.roadName = (String) l.get("roadname");
                link.length = (int) ((Number) l.get("len")).doubleValue();
                link.linkType = (int) num(l, "linktype");
                link.formway = (int) num(l, "formway");
                link.hasTrafficLight = (Boolean) l.get("hasTrafficLight");
                link.laneNum = (int) num(l, "laneNum");
                link.hasMultiOut = (Boolean) l.get("hasMultiOut");
                link.mainAction = (int) num(l, "mainAction");
                link.assistantAction = (int) num(l, "assistantAction");
                link.adcode = num(l, "adminCode");
                link.ownership = (int) num(l, "ownership");
                link.hasParallel = (Boolean) l.get("hasParallel");
                link.direction = (int) num(l, "direction");
                link.hasMixFork = (Boolean) l.get("hasMixFork");
                int pb = (int) num(l, "pntBegIdx"), pc = (int) num(l, "pntCnt");
                link.lon = new int[pc]; link.lat = new int[pc];
                for (int k = 0; k < pc; k++) {
                    Map<String, Object> p = (Map<String, Object>) pnts.get(pb + k);
                    link.lon[k] = (int) Math.round(((Number) p.get("x")).doubleValue() * StockPath.SCALE);
                    link.lat[k] = (int) Math.round(((Number) p.get("y")).doubleValue() * StockPath.SCALE);
                }
                segment.links.add(link);
            }
            path.segments.add(segment);
        }
        return path;
    }

    static long num(Map<String, Object> m, String key) { return ((Number) m.get(key)).longValue(); }
}
