package dev.denza.adaslink.stocknative;

import java.util.List;

/**
 * Literal type-1 lookahead from {@code k.h.a0.d.c.c}, classes14.dex.
 * The original DEX consumes native getters while scanning; no route snapshot or geometry
 * projection substitutes for those reads. {@code curPoint} is present in the original
 * signature but its register is never read by the method.
 */
public final class NativeLookahead {
    private NativeLookahead() { }

    public static void update(FontRoundBean out, NativePath path, boolean inTunnel,
                              int curSeg, int curLink, int curPoint, int linkRemain,
                              int remainLights, int targetSeg, int targetLink) {
        long segmentCount = path.getSegmentCount();
        if ((long) curSeg >= segmentCount) {
            out.setParam(0, -1, -1, -1, new double[2], -1, new double[2], new int[2]);
            return;
        }
        double[] tunnelEnd = new double[2];
        double[] lightLocation = new double[2];
        int[] fontIndex = new int[2];
        int distance = 0, exitDistance = -1, lightDistance = -1;
        int rampDistance = -1, enterDistance = -1, targetDistance = 0;
        int firstLink = curLink;
        scan:
        for (int seg = curSeg; (long) seg < segmentCount; seg++) {
            NativePath.Segment segment = path.getSegmentInfo((long) seg);
            long linkCount = segment.getLinkCount();
            for (int link = firstLink; (long) link < linkCount; link++) {
                NativePath.Link item = segment.getLinkInfo((long) link);
                fontIndex[0] = seg;
                fontIndex[1] = link;
                if (item == null) continue;
                int length = (int) item.getLength();
                int formway = item.getFormway();
                int type = item.getLinkType();
                distance += link == firstLink && curSeg == seg ? linkRemain : length;
                if (type != 2 && inTunnel && exitDistance == -1) {
                    List<NativePath.IntPoint> points = item.getPoints();
                    if (points != null && points.size() > 0) {
                        tunnelEnd[0] = ((double) points.get(0).lon) / 3600000.0d;
                        tunnelEnd[1] = ((double) points.get(0).lat) / 3600000.0d;
                        exitDistance = Math.max(distance - length, -1);
                    }
                }
                if (type == 2 && enterDistance == -1)
                    enterDistance = Math.max(distance - length, -1);
                if (isRamp(formway) && rampDistance == -1)
                    rampDistance = Math.max(distance - length, 0);
                if (seg == targetSeg && link == targetLink) targetDistance = distance;
                if (lightDistance == -1 && item.hasTrafficLight()) {
                    List<NativePath.Point> lights = path.getAllTrafficLights();
                    int index = lights.size() - remainLights;
                    if (index < lights.size() && index >= 0) {
                        NativePath.Point point = lights.get(index);
                        lightLocation[0] = point.lon;
                        lightLocation[1] = point.lat;
                        lightDistance = distance;
                    }
                }
                if (distance >= 3000 && (!inTunnel || exitDistance != -1)) break scan;
            }
            firstLink = 0;
        }
        if (rampDistance > 2000) rampDistance = -1;
        if (inTunnel || enterDistance > 2000) enterDistance = -1;
        out.setParam(targetDistance, rampDistance, enterDistance, exitDistance, tunnelEnd,
                lightDistance, lightLocation, fontIndex);
    }

    private static boolean isRamp(int formway) {
        return formway == 3 || formway == 6 || formway == 8 || formway == 9 || formway == 10;
    }
}
