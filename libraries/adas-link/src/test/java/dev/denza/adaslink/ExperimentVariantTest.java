package dev.denza.adaslink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

/**
 * The arm's experiment variant: its strict schema, its place in the arm, the example files under
 * tools/adas_bridge/variants/. Plain main (AdasLinkTest runs it, so Gradle does too).
 */
public final class ExperimentVariantTest {
    private static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static Map<String, Object> json(String text) {
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) Json.parse(text);
        return m;
    }
    static ExperimentVariant parse(String text) { return ExperimentVariant.parse(json(text)); }
    static void rejects(String text, String why) {
        checks++;
        try { parse(text); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("variant must reject: " + why + " " + text);
    }

    public static void main(String[] args) throws Exception {
        schema();
        arm();
        examples(new File(args.length > 0 ? args[0] : "."));
        System.out.println("ExperimentVariantTest: " + checks + " checks passed");
    }

    static void schema() {
        ExperimentVariant none = parse("{}");
        check(none.isDefault() && !none.overridesLinks() && none.historySpeed(0) == null && none.historySpeed(1) == null,
                "an empty variant is today's build");
        check(none.frame(Coordinates.Frame.WGS84) == Coordinates.Frame.WGS84, "no frame key keeps the default frame");
        Map<String, Object> e = none.effective();
        check("WGS84".equals(e.get("frame")) && "yandex".equals(e.get("link.roadclass")) && "bridge".equals(e.get("startProtocol"))
                && Boolean.FALSE.equals(e.get("naviSt")) && Boolean.FALSE.equals(e.get("instrumentNaviStatus")) && ((java.util.List<?>) e.get("overrides")).isEmpty(),
                "effective variant names every switch at today's value: " + e);
        ExperimentVariant all = parse("{\"id\":\"all\",\"frame\":\"STOCK_GCJ\",\"link.roadclass\":9,\"link.formway\":15,\"link.ownership\":0,"
                + "\"link.direction\":1,\"link.laneNum\":2,\"link.adminCode\":990001,\"link.hasMultiOut\":true,\"link.force\":true,"
                + "\"region\":990001,\"position.ownership\":0,\"traffic.roadClass\":\"link\",\"historySpeed\":{\"mode\":\"fixed\",\"value\":25},"
                + "\"speedLimit\":\"zero\",\"startProtocol\":\"stock\",\"naviSt\":true}");
        check(!all.isDefault() && all.overridesLinks() && all.frame(Coordinates.Frame.WGS84) == Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE,
                "every switch parses");
        check(all.linkRoadClass == 9 && all.linkFormway == 15 && all.linkOwnership == 0 && all.linkDirection == 1 && all.linkLaneNum == 2
                && all.linkAdminCode == 990001L && Boolean.TRUE.equals(all.linkHasMultiOut) && all.linkForce && all.region == 990001
                && all.positionOwnership == 0 && all.trafficRoadClassFromLink && all.speedLimitZero
                && all.startProtocol == ExperimentVariant.StartProtocol.STOCK && all.naviSt, "values as given");
        check(all.historySpeed(0) == 25 && all.historySpeed(7) == 25, "fixed historySpeed on every route");
        check(((java.util.List<?>) all.effective().get("overrides")).size() == 16, "overrides list every key but id");
        ExperimentVariant cn = parse("{\"link.roadclass\":7,\"link.formway\":1,\"link.laneNum\":0,\"link.adminCode\":110000,"
                + "\"region\":110000,\"traffic.roadClass\":7}");
        check(cn.linkRoadClass == 7 && cn.linkFormway == 1 && cn.linkLaneNum == 0 && cn.linkAdminCode == 110000
                && cn.region == 110000 && cn.trafficRoadClass == 7, "original Beijing donor values parse");
        check(parse("{\"link.force\":false}").isDefault() && !parse("{\"link.hasMultiOut\":false}").isDefault(),
                "force defaults off; hasMultiOut is an actual link switch");
        check(parse("{\"instrumentNaviStatus\":true}").instrumentNaviStatus && !parse("{\"instrumentNaviStatus\":true}").naviSt,
                "instrument navigation status is independent of MPC NaviSt");
        check(!parse("{\"naviSt\":true}").instrumentNaviStatus, "MPC switch does not enable the instrument channel");
        check(parse("{\"instrumentNaviStatus\":false}").isDefault(), "instrument switch defaults off");
        rejects("{\"instrumentNaviStatus\":1}", "instrument status number");
        rejects("{\"instrumentNaviStatus\":\"true\"}", "instrument status string");
        ExperimentVariant ab = parse("{\"historySpeed\":{\"mode\":\"alternate\",\"values\":[null,25]}}");
        check(ab.historySpeed(0) == null && ab.historySpeed(1) == 25 && ab.historySpeed(2) == null && ab.historySpeed(3) == 25,
                "alternate: route 1 Yandex's, route 2 the fixed value, and so on");
        check(parse("{\"traffic.roadClass\":9}").trafficRoadClass == 9, "a fixed TrafficInfo class from the stock set");
        check(parse("{\"historySpeed\":{\"mode\":\"yandex\"}}").isDefault(), "explicit yandex historySpeed is the default");
        check(parse("{\"frame\":\"WGS84\"}").frame == ExperimentVariant.Frame.WGS84, "explicit WGS84 frame");

        rejects("{\"unknown\":1}", "unknown key");
        rejects("{\"Frame\":\"WGS84\"}", "key case");
        rejects("{\"frame\":\"GCJ\"}", "frame value");
        rejects("{\"frame\":null}", "null frame");
        rejects("{\"link.roadclass\":5}", "class outside the observed stock sets");
        rejects("{\"link.roadclass\":-1}", "class null marker");
        rejects("{\"link.roadclass\":9.0}", "float class");
        rejects("{\"link.roadclass\":\"9\"}", "string class");
        rejects("{\"link.formway\":2}", "formway outside the observed stock sets");
        rejects("{\"link.ownership\":1}", "ownership outside the stock set");
        rejects("{\"link.direction\":3}", "direction raw value");
        rejects("{\"link.laneNum\":3}", "laneNum outside the observed stock sets");
        rejects("{\"link.adminCode\":110101}", "a Beijing district code");
        rejects("{\"link.hasMultiOut\":1}", "multi-out number");
        rejects("{\"link.force\":1}", "force number");
        rejects("{\"link.force\":null}", "force null");
        rejects("{\"region\":0}", "region zero");
        rejects("{\"position.ownership\":-1}", "unmatched ownership");
        rejects("{\"traffic.roadClass\":\"links\"}", "traffic class word");
        rejects("{\"traffic.roadClass\":5}", "traffic class outside the observed sets");
        rejects("{\"historySpeed\":25}", "historySpeed not an object");
        rejects("{\"historySpeed\":{\"mode\":\"fixed\"}}", "fixed without value");
        rejects("{\"historySpeed\":{\"mode\":\"fixed\",\"value\":0}}", "fixed zero");
        rejects("{\"historySpeed\":{\"mode\":\"fixed\",\"value\":121}}", "fixed over the range");
        rejects("{\"historySpeed\":{\"mode\":\"fixed\",\"value\":25,\"values\":[25]}}", "extra historySpeed key");
        rejects("{\"historySpeed\":{\"mode\":\"alternate\",\"values\":[25]}}", "alternate of one");
        rejects("{\"historySpeed\":{\"mode\":\"alternate\",\"values\":[null,null]}}", "alternate without a fixed value");
        rejects("{\"historySpeed\":{\"mode\":\"alternate\",\"values\":[1,2,3,4,5,6,7,8,9]}}", "alternate too long");
        rejects("{\"historySpeed\":{\"mode\":\"alternate\",\"values\":[null,\"25\"]}}", "alternate string");
        rejects("{\"historySpeed\":{\"mode\":\"yandex\",\"value\":25}}", "yandex with a value");
        rejects("{\"historySpeed\":{\"mode\":\"free\"}}", "unknown mode");
        rejects("{\"speedLimit\":\"20\"}", "speedLimit value");
        rejects("{\"speedLimit\":0}", "speedLimit number");
        rejects("{\"startProtocol\":\"STOCK\"}", "startProtocol case");
        rejects("{\"naviSt\":\"true\"}", "naviSt string");
        rejects("{\"naviSt\":1}", "naviSt number");
        rejects("{\"id\":\"\"}", "empty id");
        rejects("{\"id\":\"has space\"}", "id characters");
        checks++;
        try { ExperimentVariant.parse("{}"); throw new AssertionError("a string is not a variant object"); }
        catch (IllegalArgumentException expected) { /* rejected */ }
    }

    static void arm() {
        Map<String, Object> m = LiveSessionLeaseTest.arm();
        check(LiveSessionLeaseTest.read(m, 1000).variant == ExperimentVariant.NONE, "an arm without a variant is today's build");
        m.put("variant", json("{\"id\":\"H1\",\"frame\":\"STOCK_GCJ\"}"));
        LiveSessionLease a = LiveSessionLeaseTest.read(m, 1000);
        check(a.variant.frame == ExperimentVariant.Frame.STOCK_GCJ && "H1".equals(a.variant.id), "the arm carries its variant");
        check(a.same(LiveSessionLeaseTest.read(m, 5000)), "the same arm with the same variant is the same session");
        Map<String, Object> other = LiveSessionLeaseTest.arm();
        other.put("variant", json("{\"frame\":\"WGS84\"}"));
        check(!a.same(LiveSessionLeaseTest.read(other, 1000)), "a changed variant is a changed arm");
        for (String invalid : Arrays.asList("{\"frame\":\"GCJ\"}", "{\"x\":1}", "{\"region\":1}")) {
            Map<String, Object> bad = LiveSessionLeaseTest.arm();
            bad.put("variant", json(invalid));
            checks++;
            try { LiveSessionLeaseTest.read(bad, 1000); throw new AssertionError("arm with invalid variant accepted: " + invalid); }
            catch (IllegalArgumentException expected) { /* rejected */ }
        }
        Map<String, Object> scalar = LiveSessionLeaseTest.arm();
        scalar.put("variant", "H1");
        checks++;
        try { LiveSessionLeaseTest.read(scalar, 1000); throw new AssertionError("a scalar variant accepted"); }
        catch (IllegalArgumentException expected) { /* rejected */ }
        Map<String, Object> eleven = LiveSessionLeaseTest.arm();
        eleven.put("variant", Json.object()); eleven.put("extra", true);
        checks++;
        try { LiveSessionLeaseTest.read(eleven, 1000); throw new AssertionError("an extra key beside the variant accepted"); }
        catch (IllegalArgumentException expected) { /* rejected */ }
    }

    /** Every example file is valid; each sets exactly the parked chain's variable(s) of its step. */
    static void examples(File root) throws Exception {
        File dir = new File(root, "tools/adas_bridge/variants");
        String[][] expect = {
                {"baseline.json"}, {"H1.json", "frame"}, {"H2a.json", "link.roadclass", "link.formway"}, {"H2b.json", "link.ownership"},
                {"H2c.json", "link.direction"}, {"H2d.json", "link.laneNum"}, {"H2e.json", "link.adminCode", "region"},
                {"H2f.json", "position.ownership", "traffic.roadClass"}, {"H2g.json", "speedLimit"}, {"H3.json", "startProtocol"},
                {"H6.json", "naviSt"}, {"H7.json", "instrumentNaviStatus"}, {"drive-ab-historyspeed.json", "historySpeed"},
                {"compatibility-cn.json", "frame", "link.roadclass", "link.formway", "link.ownership", "link.direction",
                        "link.laneNum", "link.adminCode", "link.hasMultiOut", "link.force", "region", "position.ownership",
                        "traffic.roadClass", "historySpeed", "speedLimit", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-all.json", "frame", "position.ownership", "link.ownership", "link.laneNum", "link.formway", "region",
                        "link.adminCode", "speedLimit", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-frame.json", "frame", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-rest.json", "position.ownership", "link.ownership", "link.laneNum", "link.formway", "region",
                        "link.adminCode", "speedLimit", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-own-lanes.json", "position.ownership", "link.ownership", "link.laneNum", "link.formway", "startProtocol",
                        "naviSt", "instrumentNaviStatus"},
                {"loop-own.json", "position.ownership", "link.ownership", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-lanes.json", "link.laneNum", "link.formway", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-region.json", "region", "link.adminCode", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-limit.json", "speedLimit", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-wire.json", "position.ownership", "region", "speedLimit", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-wire-own.json", "position.ownership", "startProtocol", "naviSt", "instrumentNaviStatus"},
                {"loop-wire-region.json", "region", "startProtocol", "naviSt", "instrumentNaviStatus"}};
        for (String[] row : expect) {
            File f = new File(dir, row[0]);
            check(f.isFile(), "example variant " + f);
            ExperimentVariant v = ExperimentVariant.parse(Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            java.util.List<?> set = (java.util.List<?>) v.effective().get("overrides");
            check(set.equals(Arrays.asList(row).subList(1, row.length)), row[0] + " sets " + set);
        }
        check(parse(new String(Files.readAllBytes(new File(dir, "baseline.json").toPath()), StandardCharsets.UTF_8)).isDefault(),
                "baseline is the build's behaviour");
        ExperimentVariant ab = parse(new String(Files.readAllBytes(new File(dir, "drive-ab-historyspeed.json").toPath()), StandardCharsets.UTF_8));
        check(ab.historySpeed(0) == null && ab.historySpeed(1) == 25, "the drive A/B: there on Yandex's value, back on 25");
        ExperimentVariant loop = parse(new String(Files.readAllBytes(new File(dir, "loop-all.json").toPath()), StandardCharsets.UTF_8));
        check(loop.frame == ExperimentVariant.Frame.STOCK_GCJ && loop.positionOwnership == 0 && loop.linkOwnership == 0
                && loop.linkLaneNum == 1 && loop.linkFormway == 15 && loop.region == 990001 && loop.linkAdminCode == 990001L
                && loop.speedLimitZero && loop.startProtocol == ExperimentVariant.StartProtocol.STOCK && !loop.naviSt
                && !loop.instrumentNaviStatus && loop.overridesLinks(), "loop-all is the stock's values on the settlement loop");
        ExperimentVariant wire = parse(new String(Files.readAllBytes(new File(dir, "loop-wire.json").toPath()), StandardCharsets.UTF_8));
        check(wire.frame == null && wire.positionOwnership == 0 && wire.region == 990001 && wire.speedLimitZero
                && !wire.overridesLinks() && wire.startProtocol == ExperimentVariant.StartProtocol.STOCK && !wire.naviSt
                && !wire.instrumentNaviStatus, "loop-wire: the stock's values during its CNOA holds, no link fill-in, WGS-84");
        ExperimentVariant cn = parse(new String(Files.readAllBytes(new File(dir, "compatibility-cn.json").toPath()), StandardCharsets.UTF_8));
        check(cn.frame == ExperimentVariant.Frame.WGS84 && cn.linkRoadClass == 7 && cn.linkFormway == 1 && cn.linkDirection == 1
                && cn.linkLaneNum == 0 && cn.linkAdminCode == 110000 && Boolean.TRUE.equals(cn.linkHasMultiOut) && cn.linkForce
                && cn.region == 110000 && cn.positionOwnership == 0 && cn.trafficRoadClassFromLink && cn.historySpeed(0) == 25
                && cn.speedLimitZero && cn.startProtocol == ExperimentVariant.StartProtocol.STOCK && cn.naviSt
                && cn.instrumentNaviStatus, "compatibility-cn: maximal Chinese donor, real WGS-84 geometry and full stock lifecycle");
    }
}
