package dev.denza.adaslink.stocknative;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Focused source-contract checks for the recovered original DEX's non-obvious branches. */
public final class NativeLookaheadTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    @SuppressWarnings("unchecked")
    private static <T> T view(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static Object length(Method method, int value) {
        return method.getReturnType() == long.class ? (Object) Long.valueOf(value) : Integer.valueOf(value);
    }
    private static NativePath.Link link(final int length, final int form, final int type,
                                        final boolean light, final List<NativePath.IntPoint> points) {
        return view(NativePath.Link.class, (o, m, a) -> {
            switch (m.getName()) {
                case "getLength": return length(m, length);
                case "getFormway": return form;
                case "getLinkType": return type;
                case "hasTrafficLight": return light;
                case "getPoints": return points;
                default: throw new AssertionError("Unexpected link getter: " + m.getName());
            }
        });
    }
    private static NativePath.Segment segment(final NativePath.Link... links) {
        return view(NativePath.Segment.class, (o, m, a) -> {
            switch (m.getName()) {
                case "getLinkCount": return (long) links.length;
                case "getLinkInfo": return links[((Number) a[0]).intValue()];
                default: throw new AssertionError("Unexpected segment getter: " + m.getName());
            }
        });
    }
    private static NativePath path(final List<NativePath.Point> lights, final NativePath.Segment... segments) {
        return view(NativePath.class, (o, m, a) -> {
            switch (m.getName()) {
                case "getSegmentCount": return (long) segments.length;
                case "getSegmentInfo": return segments[((Number) a[0]).intValue()];
                case "getAllTrafficLights": return lights;
                default: throw new AssertionError("Unexpected path getter: " + m.getName());
            }
        });
    }
    private static NativePath.Link road(int length, int form, int type) {
        return link(length, form, type, false, Collections.emptyList());
    }
    @Test public void recoveredDexSourceContract() {
        runChecks();
    }
    private static void runChecks() {
        checks = 0;
        remainingAndUnusedPoint(); tunnelBeyondThreeKm(); nullLinksAndSegmentTransition();
        invalidSegmentResets(); overflowRemainsInteger();
    }
    public static void main(String[] args) {
        runChecks();
        System.out.println("NativeLookaheadTest: " + checks + " checks passed");
    }
    private static void remainingAndUnusedPoint() {
        NativePath p = path(Collections.emptyList(), segment(road(100, 3, 0), road(200, 0, 2)));
        FontRoundBean a = new FontRoundBean(), b = new FontRoundBean();
        NativeLookahead.update(a, p, false, 0, 0, 0, 20, 0, 0, 1);
        NativeLookahead.update(b, p, false, 0, 0, Integer.MAX_VALUE, 20, 0, 0, 1);
        check(a.getToRampDist() == 0, "current ramp distance subtracts its full native length, then clamps at zero");
        check(a.getEnterTunnelDist() == 20, "next tunnel begins at the raw current-link remainder");
        check(a.getDstLinkDistance() == 220, "lane-target distance includes its complete link");
        check(a.getDstLinkDistance() == b.getDstLinkDistance()
                && a.getEnterTunnelDist() == b.getEnterTunnelDist(), "curPoint register is unused in original DEX");
    }
    private static void tunnelBeyondThreeKm() {
        NativePath.Link exit = link(100, 6, 0, true,
                Collections.singletonList(new NativePath.IntPoint(3600000, 7200000)));
        NativePath p = path(Collections.singletonList(new NativePath.Point(9.25, 8.5)),
                segment(road(400, 0, 2), road(4000, 0, 2), exit, road(200, 0, 0)));
        FontRoundBean out = new FontRoundBean();
        NativeLookahead.update(out, p, true, 0, 0, 99, 50, 1, 0, 2);
        check(Arrays.equals(out.getFont3kmIdx(), new int[]{0, 2}), "in-tunnel scan passes 3 km until an exit is found");
        check(out.getLeaveTunnelDist() == 4050, "exit distance subtracts the exit link length");
        check(Arrays.equals(out.getTunnelEndLocation(), new double[]{1, 2}), "exit position is its first integer-grid point");
        check(out.getEnterTunnelDist() == -1 && out.getToRampDist() == -1, "in-tunnel entry suppressed and distant ramp suppressed");
        check(out.getDstLinkDistance() == 4150 && out.getLightDist() == 4150, "target/light distances include exit link");
        check(Arrays.equals(out.getLightLocation(), new double[]{9.25, 8.5}), "light coordinate comes from native all-lights list");
    }
    private static void nullLinksAndSegmentTransition() {
        NativePath p = path(Collections.emptyList(), segment(road(10, 0, 0)),
                segment(road(20, 0, 0), null), segment(road(30, 0, 0), null));
        FontRoundBean out = new FontRoundBean();
        NativeLookahead.update(out, p, false, 1, 0, 4, 7, 0, 2, 0);
        check(out.getDstLinkDistance() == 37, "only initial segment/link uses raw remainder");
        check(Arrays.equals(out.getFont3kmIdx(), new int[]{2, 1}), "last visited index includes null links");
    }
    private static void invalidSegmentResets() {
        FontRoundBean out = new FontRoundBean();
        out.setParam(3, 4, 5, 6, new double[]{7, 8}, 9, new double[]{10, 11}, new int[]{12, 13});
        NativeLookahead.update(out, path(Collections.emptyList()), false, 0, 0, 0, 0, 0, -1, -1);
        check(out.getDstLinkDistance() == 0 && out.getToRampDist() == -1
                && out.getEnterTunnelDist() == -1 && out.getLeaveTunnelDist() == -1
                && out.getLightDist() == -1, "out-of-range segment restores original defaults");
        check(Arrays.equals(out.getFont3kmIdx(), new int[2])
                && Arrays.equals(out.getLightLocation(), new double[2]), "default arrays are replaced");
    }
    private static void overflowRemainsInteger() {
        NativePath p = path(Collections.emptyList(), segment(road(1, 0, 2), road(100, 0, 2)));
        FontRoundBean out = new FontRoundBean();
        NativeLookahead.update(out, p, true, 0, 0, 0, Integer.MAX_VALUE, 0, 0, 1);
        check(out.getDstLinkDistance() == Integer.MIN_VALUE + 99, "distance arithmetic retains original 32-bit overflow");
    }
}
