package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The 2026-10-24 drive's engine failures (captures/adas-live-drive-20261024/ANALYSIS.md), each
 * replayed on the production guard, feed and driver with a memory-only transport. No Android.
 */
public final class LiveDriveRegressionTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    static final class Sent { final long topic, at; final byte[] bytes; Sent(long t, long a, byte[] b) { topic = t; at = a; bytes = b; } }
    static final class Transport implements Publisher, AutoCloseable {
        final List<Sent> sent = new ArrayList<>(); final ManualScheduler clock; int closes;
        Transport(ManualScheduler clock) { this.clock = clock; }
        @Override public BridgeStatus.Mode mode() { return BridgeStatus.Mode.LIVE; }
        @Override public int startService(long service) { return 0; }
        @Override public int fire(long topic, byte[] payload, long now) { sent.add(new Sent(topic, clock.now(), payload)); return 0; }
        @Override public void note(String kind, Map<String, Object> fields, long now) { }
        @Override public void close() { closes++; }
        List<Sent> of(long topic) { List<Sent> out = new ArrayList<>(); for (Sent s : sent) if (s.topic == topic) out.add(s); return out; }
    }
    static final class Audit implements Publisher {
        final List<String> reasons = new ArrayList<>();
        @Override public BridgeStatus.Mode mode() { return BridgeStatus.Mode.DRY_RUN; }
        @Override public int startService(long service) { return 0; }
        @Override public int fire(long topic, byte[] payload, long now) { throw new AssertionError("audit fire"); }
        @Override public void note(String kind, Map<String, Object> fields, long now) {
            Object reason = fields.get("reason"); if (reason != null) reasons.add(kind + ":" + reason);
        }
    }

    /** The production arrangement: terminal guard, live feed, driver, one clock. */
    static final class Live {
        final ManualScheduler clock = new ManualScheduler(1_000_000);
        final Transport transport = new Transport(clock);
        final Audit audit = new Audit();
        boolean ready = true;
        final LiveEgressGuard guard;
        final StockFeed feed;
        final NavigatorDriver driver;
        long stamp = 1;
        Live() {
            StockFeed[] feeds = {null};
            guard = new LiveEgressGuard(transport, audit, clock,
                    now -> ready ? new LiveEgressGuard.Readiness(1, now, now + 2500, true, true, true, true, true, true, true, true) : null,
                    id -> feeds[0].onPathSelectConfirm(StockMessages.pathSelectConfirm(id, 200)), true);
            guard.setWallClock(this::wall);
            StockFeed.Config config = FieldDefaults.navigator(new StockFeed.Config());
            long[] ids = {1_792_000_000L}; config.livePathIds = () -> ++ids[0];
            feed = new StockFeed(clock, guard, config); feeds[0] = feed;
            driver = new NavigatorDriver(feed, config.frame, clock);
            feed.start(true, true, true); clock.drain();
        }
        long wall() { return 1_792_836_000_000L + clock.now(); }
        /** One module tick with a new FINE fix at {@code scalar}, with or without a route copy. */
        void tick(YandexRoute route, double scalar, double speed, boolean copy) {
            NavigatorDriver.Observation o = DynamicTests.guidance(clock.now(), route.routeId, route, scalar, speed, ++stamp);
            if (!copy) o.route = null;
            o.fixAgeMs = 0; o.stamp = clock.now();
            driver.observe(o);
            guard.fineFix(guard.currentPathId(), FixFreshness.capture(clock.now(), wall(), clock.now(), clock.now(), wall()), "FINE");
            clock.drain();
        }
        void ack() { check(guard.onAck(1, Topics.PATH_SELECT_CONFIRM, clock.now(), StockMessages.pathSelectConfirm(guard.currentPathId(), 200))
                == RealPathAck.Decision.ACCEPTED, "real selection ACK accepted"); clock.drain(); }
        void advance(long ms) { clock.advanceTo(clock.now() + ms); }
        List<Long> statuses() {
            List<Long> out = new ArrayList<>();
            for (Sent s : transport.of(Topics.NAVIGATION_STATUS)) out.add(status(s.bytes));
            return out;
        }
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> body(long topic, byte[] bytes) {
        Map<String, Object> decoded = Topics.schema(topic).decode(bytes);
        return (Map<String, Object>) decoded.values().iterator().next();
    }
    static long status(byte[] bytes) { return ((Number) body(Topics.NAVIGATION_STATUS, bytes).get("navigationStatus")).longValue(); }
    static long number(Map<String, Object> m, String key) { Object v = m.get(key); return v == null ? 0 : ((Number) v).longValue(); }

    /** 2 km straight east-west route, one section: room for many links and fixes. */
    static YandexRoute straight(String id) {
        double m = GraphRoadNetworkTest.METRES;
        return new YandexRoute(id, new double[]{37.0, 37.0 + 1000 / m, 37.0 + 2000 / m}, new double[]{55.5, 55.5, 55.5},
                java.util.Arrays.asList(new YandexRoute.Section(0, 2, "STRAIGHT", "Киевское шоссе", 100, 72)), new Double[]{null, null},
                new double[0], java.util.Collections.<double[]>emptyList(), java.util.Collections.<double[]>emptyList(), new double[0],
                java.util.Collections.<YandexRoute.Camera>emptyList());
    }

    public static void main(String[] args) {
        transientKeepsConfirmedPath();
        transientNeverMislabelledAsEpoch();
        stopSendsStockEndSequence();
        arrivalSendsStockEndSequence();
        graphRefreshNeverRepublishes();
        oneHertzPositionsWithTheirOwnIndices();
        firstPositionFollowsAck();
        conversionOffTheSendThread();
        System.out.println("LiveDriveRegressionTest: " + checks + " checks passed");
    }

    /** A.4: at the shift into Drive a transient dropped the confirmed path; 42.66 s of silence followed. */
    static void transientKeepsConfirmedPath() {
        Live live = new Live(); YandexRoute route = straight("a4");
        live.tick(route, 0.01, 0, true); live.advance(150); live.ack(); live.advance(1000);
        live.tick(route, 0.02, 1.3, false);
        long path = live.guard.currentPathId(); int before = live.transport.of(Topics.SD_VEHICLE_LOCATION).size();
        check(before > 0 && live.guard.confirmedPathId() == path, "confirmed route is streaming before the transient");
        live.advance(1000); live.ready = false; live.tick(route, 0.03, 1.3, false);
        check(!live.guard.isClosed(), "a transient prerequisite loss does not end the session");
        check(live.guard.currentPathId() == path && live.guard.confirmedPathId() == path, "a transient keeps the confirmed path and its ACK");
        live.advance(1000); live.ready = true; live.tick(route, 0.04, 1.3, false);
        check(live.transport.of(Topics.SD_VEHICLE_LOCATION).size() > before, "positions resume on the same path at the next fresh fix");
        check(live.transport.of(Topics.ROUTE).size() == 1, "recovery needs no republication");
    }

    /** F: "transport epoch changed" was logged when only the ACK token was reset. */
    static void transientNeverMislabelledAsEpoch() {
        Live live = new Live(); YandexRoute route = straight("f-epoch");
        live.tick(route, 0.01, 0, true); live.advance(150); live.ack();
        live.advance(1000); live.ready = false; live.tick(route, 0.02, 1, false);
        live.advance(1000); live.ready = true; live.tick(route, 0.03, 1, false);
        for (String reason : live.audit.reasons) check(!reason.contains("transport epoch changed"), "unchanged epoch is never reported as changed: " + reason);
    }

    /** F/B: a stop left ADAS holding the route; the stock's status loop sends 100 at 1 Hz after guidance ends. */
    static void stopSendsStockEndSequence() {
        Live live = new Live(); YandexRoute route = straight("stop");
        live.tick(route, 0.01, 0, true); live.advance(150); live.ack(); live.advance(1000);
        live.tick(route, 0.02, 1, false);
        int at = live.statuses().size();
        live.feed.onNavStop(); live.clock.drain(); live.advance(5000);
        List<Long> after = live.statuses().subList(at, live.statuses().size());
        check(after.size() >= 3 && after.get(0) == 100 && after.get(1) == 100 && after.get(2) == 100, "guidance end sends the stock's 100 at 1 Hz: " + after);
    }

    /** The stock's MmtSomeIpDataManager.d: arrival arms three 110s, then 100. */
    static void arrivalSendsStockEndSequence() {
        Live live = new Live(); YandexRoute route = straight("arrive");
        live.tick(route, 0.01, 0, true); live.advance(150); live.ack(); live.advance(1000);
        live.tick(route, 0.02, 1, false);
        int at = live.statuses().size();
        live.feed.onNaviArrive(); live.feed.onNavStop(); live.clock.drain(); live.advance(7000);
        List<Long> after = live.statuses().subList(at, live.statuses().size());
        check(after.size() >= 4 && after.get(0) == 110 && after.get(1) == 110 && after.get(2) == 110 && after.get(3) == 100,
                "arrival sends 110 ×3 then 100: " + after);
    }

    /** R19/D: graph refreshes republished one Yandex route as 15 paths in 601 s. */
    static void graphRefreshNeverRepublishes() {
        YandexRoute route = GraphRoadNetworkTest.route("STRAIGHT", "Route name", 25.0);
        GraphRoadNetwork junction = GraphRoadNetworkTest.network(route, GraphRoadNetworkTest.line(11, 100, 1, 2, 0, 0, 100, 0),
                GraphRoadNetworkTest.tile(12, 100, 2, 1, 0, 0, true, GraphRoadNetworkTest.points(0, 0, 100, 0), "Graph road", 15f, 0, 0, 0),
                GraphRoadNetworkTest.line(13, 101, 2, 3, 100, 0, 200, 0), GraphRoadNetworkTest.line(14, 102, 2, 4, 100, 0, 100, 100));
        check(GraphRoadNetworkTest.links(RouteConverter.convert(route, Coordinates.Frame.WGS84, junction)).size() == 2,
                "fixture: the graph adds a junction cut to the same Yandex route");
        Live live = new Live();
        live.tick(route, 0.1, 5, true); live.advance(150); live.ack(); live.advance(1000);
        long path = live.guard.currentPathId();
        live.driver.setRoads(junction); live.tick(route, 0.2, 5, true); live.advance(1000); live.tick(route, 0.3, 5, true);
        check(live.transport.of(Topics.ROUTE).size() == 1 && live.transport.of(Topics.PATH_SELECTED).size() == 1,
                "a graph refresh of the same Yandex route publishes no new path");
        check(live.guard.currentPathId() == path && live.guard.confirmedPathId() == path, "the published path and its ACK stay for the route's life");
        YandexRoute rerouted = GraphRoadNetworkTest.route("STRAIGHT", "Route name", 25.0);
        rerouted = new YandexRoute("synthetic-reroute", rerouted.lon, rerouted.lat, rerouted.sections, rerouted.speedLimits, new double[0],
                java.util.Collections.<double[]>emptyList(), java.util.Collections.<double[]>emptyList(), new double[0], java.util.Collections.<YandexRoute.Camera>emptyList());
        live.advance(1000); live.tick(rerouted, 0.1, 5, true);
        check(live.transport.of(Topics.ROUTE).size() == 2 && live.guard.currentPathId() != path, "a real new Yandex route is a new path");
    }

    /** F/A.2: 4.75 Hz positions carried 1 Hz indices (remaining distance off by up to 36.57 m). */
    static void oneHertzPositionsWithTheirOwnIndices() {
        Live live = new Live(); YandexRoute route = straight("cadence");
        live.tick(route, 0.0, 0, true); live.advance(150); live.ack(); live.advance(1000);
        int before = live.transport.of(Topics.SD_VEHICLE_LOCATION).size();
        double scalar = 0.01;
        for (int i = 0; i < 50; i++) { live.advance(200); scalar += 20.0 * 0.2 / 1000; live.tick(route, scalar, 20, false); }
        List<Sent> positions = live.transport.of(Topics.SD_VEHICLE_LOCATION);
        int sent = positions.size() - before;
        check(sent >= 9 && sent <= 11, "positions at the stock's 1 Hz while moving, not per 200 ms tick: " + sent);
        RouteConverter.Converted table = RouteConverter.convert(route, Coordinates.Frame.WGS84);
        for (Sent s : positions.subList(before, positions.size())) {
            Map<String, Object> p = body(Topics.SD_VEHICLE_LOCATION, s.bytes);
            double lon = ((Number) p.get("locationLongitudeAssociateRoad")).doubleValue();
            double along = (lon - 37.0) * GraphRoadNetworkTest.METRES * Math.cos(Math.toRadians(55.5));
            int[] ni = table.naviInfo(along);
            check(Math.abs(number(p, "linkOffset") - ni[3]) <= 2, "remaining link distance belongs to the position sent: " + number(p, "linkOffset") + " vs " + ni[3]);
        }
    }

    /** D: conversion and graph matching ran on the sending thread; a prepared conversion is now used as made. */
    static void conversionOffTheSendThread() {
        Live live = new Live(); YandexRoute route = straight("prepared");
        NavigatorDriver.Observation o = DynamicTests.guidance(live.clock.now(), route.routeId, route, 0.01, 5, ++live.stamp);
        o.fixAgeMs = 0; o.stamp = live.clock.now();
        NavigatorDriver.prepare(o, Coordinates.Frame.WGS84, null, null);
        live.driver.observe(o); live.clock.drain();
        check(Boolean.TRUE.equals(live.driver.conversionSummary().get("preparedAhead")) && live.transport.of(Topics.ROUTE).size() == 1,
                "the route prepared on the worker is published without converting again");
        Live other = new Live();
        NavigatorDriver.Observation stale = DynamicTests.guidance(other.clock.now(), route.routeId, route, 0.01, 5, ++other.stamp);
        NavigatorDriver.prepare(stale, Coordinates.Frame.WGS84, null, null);
        stale.route = straight("prepared");  // a different copy than the one prepared
        other.driver.observe(stale); other.clock.drain();
        check(Boolean.FALSE.equals(other.driver.conversionSummary().get("preparedAhead")) && other.transport.of(Topics.ROUTE).size() == 1,
                "a conversion prepared for other inputs is not used");
    }

    /** D/F: after a real new path the first position waited 1.06–1.40 s although the ACK came after ~0.2 s. */
    static void firstPositionFollowsAck() {
        Live live = new Live(); YandexRoute route = straight("first");
        live.tick(route, 0.01, 10, true);
        long published = live.clock.now();
        live.advance(200); live.ack();
        long ackAt = live.clock.now();
        for (int i = 0; i < 10 && live.transport.of(Topics.SD_VEHICLE_LOCATION).isEmpty(); i++) { live.advance(200); live.tick(route, 0.01 + i * 0.002, 10, false); }
        List<Sent> positions = live.transport.of(Topics.SD_VEHICLE_LOCATION);
        check(!positions.isEmpty() && positions.get(0).at - ackAt <= 250, "the first position follows the ACK at the next fix: "
                + (positions.isEmpty() ? "none" : (positions.get(0).at - ackAt) + " ms after ACK, " + (positions.get(0).at - published) + " ms after route"));
    }
}
