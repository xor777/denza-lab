package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The variant's start protocol on the production guard, feed and driver with a memory-only
 * transport: the stock's ODD request and 100 -> 106 -> 101 phase before the route under
 * {@code startProtocol: stock}, the request admitted only there and only once, 13/0x0001 never.
 */
public final class LiveVariantTest {
    private static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    /** Service 13 method 0x0001, the DNP/ICC switch: built here only to prove nothing sends it. */
    static final long DNP_SWITCH_REQUEST = (2L << 48) | (13L << 32) | (13L << 16) | 0x0001;

    static final class Live {
        final ManualScheduler clock = new ManualScheduler(1_000_000);
        final LiveDriveRegressionTest.Transport transport = new LiveDriveRegressionTest.Transport(clock);
        final LiveDriveRegressionTest.Audit audit = new LiveDriveRegressionTest.Audit();
        final List<Long> requests = new ArrayList<>(), requestTimes = new ArrayList<>();
        final LiveEgressGuard guard;
        final StockFeed feed;
        final NavigatorDriver driver;
        long stamp = 1;
        Live(ExperimentVariant variant) {
            StockFeed[] feeds = {null};
            guard = new LiveEgressGuard(transport, audit, clock,
                    now -> new LiveEgressGuard.Readiness(1, now, now + 2500, true, true, true, true, true, true, true, true),
                    id -> feeds[0].onPathSelectConfirm(StockMessages.pathSelectConfirm(id, 200)), true);
            guard.setWallClock(this::wall);
            if (variant.startProtocol == ExperimentVariant.StartProtocol.STOCK)
                guard.admitStockStart(topic -> { requests.add(topic); requestTimes.add(clock.now()); return 0; });
            StockFeed.Config config = FieldDefaults.navigator(new StockFeed.Config(), variant);
            long[] ids = {1_792_000_000L}; config.livePathIds = () -> ++ids[0];
            feed = new StockFeed(clock, guard, config); feeds[0] = feed;
            driver = new NavigatorDriver(feed, config.frame, config.variant, clock);
            feed.start(true, true, true); clock.drain();
        }
        long wall() { return 1_792_836_000_000L + clock.now(); }
        void tick(YandexRoute route, double scalar, boolean copy) {
            NavigatorDriver.Observation o = DynamicTests.guidance(clock.now(), route.routeId, route, scalar, 10, ++stamp);
            if (!copy) o.route = null;
            o.fixAgeMs = 0; o.stamp = clock.now();
            driver.observe(o);
            guard.fineFix(guard.currentPathId(), FixFreshness.capture(clock.now(), wall(), clock.now(), clock.now(), wall()), "FINE");
            clock.drain();
        }
        void ack() {
            check(guard.onAck(1, Topics.PATH_SELECT_CONFIRM, clock.now(), StockMessages.pathSelectConfirm(guard.currentPathId(), 200))
                    == RealPathAck.Decision.ACCEPTED, "real selection ACK accepted"); clock.drain();
        }
        void advance(long ms) { clock.advanceTo(clock.now() + ms); }
        /** The transport's sends as a timeline: NavigationStatus values, R(oute), S(election), P(osition). */
        List<String> timeline() {
            List<String> out = new ArrayList<>();
            for (LiveDriveRegressionTest.Sent s : transport.sent) {
                if (s.topic == Topics.NAVIGATION_STATUS) out.add(Long.toString(LiveDriveRegressionTest.status(s.bytes)));
                else if (s.topic == Topics.ROUTE) out.add("R");
                else if (s.topic == Topics.PATH_SELECTED) out.add("S");
                else if (s.topic == Topics.SD_VEHICLE_LOCATION) out.add("P");
            }
            return out;
        }
        long firstAt(long topic) {
            for (LiveDriveRegressionTest.Sent s : transport.sent) if (s.topic == topic) return s.at;
            return -1;
        }
    }

    public static void main(String[] args) {
        bridgeStart();
        stockStart();
        guardAdmission();
        System.out.println("LiveVariantTest: " + checks + " checks passed");
    }

    static void drive(Live live, YandexRoute route, int seconds) {
        live.tick(route, 0.02, true);
        for (int i = 1; i <= seconds; i++) {
            live.advance(1000);
            live.tick(route, 0.02 + 0.001 * i, false);
            if (live.guard.currentPathId() > 0 && live.guard.confirmedPathId() != live.guard.currentPathId()
                    && live.transport.of(Topics.PATH_SELECTED).size() > 0) live.ack();
        }
    }

    /** Today: 101 from the start, the route at once, no request at all. */
    static void bridgeStart() {
        Live live = new Live(ExperimentVariant.NONE);
        long navStart = live.clock.now();
        drive(live, LiveDriveRegressionTest.straight("bridge"), 4);
        List<String> t = live.timeline();
        check(t.indexOf("R") == 0 && !t.contains("100") && !t.contains("106"), "bridge start: the route first, no 100/106: " + t);
        check(live.firstAt(Topics.ROUTE) == navStart, "bridge start: the route at the start");
        check(live.requests.isEmpty() && live.guard.requestsSent() == 0, "bridge start: no ODD request");
        check(live.guard.request(Topics.ODD_REGION_REQUEST) == LiveEgressGuard.REJECTED && live.requests.isEmpty(),
                "without the switch the guard refuses the ODD request");
    }

    /** startProtocol stock: ODD request, 100 100 106 106 106, then the route and 101 at the stock's timing. */
    static void stockStart() {
        Live live = new Live(ExperimentVariant.parse(Json.parse("{\"startProtocol\":\"stock\"}")));
        long navStart = live.clock.now();
        drive(live, LiveDriveRegressionTest.straight("stock"), 9);
        List<String> t = live.timeline();
        check(live.requests.equals(Arrays.asList(Topics.ODD_REGION_REQUEST)) && live.requestTimes.get(0) == navStart,
                "one ODD region request at the start: " + live.requests);
        check(t.size() > 7 && t.subList(0, 7).equals(Arrays.asList("100", "100", "106", "106", "106", "R", "S")),
                "the stock's start: 100 x2, 106 x3, then the route and its selection: " + t);
        check(live.firstAt(Topics.ROUTE) == navStart + ExperimentVariant.stockStartMs(), "the route follows the phase by 5 s");
        int first101 = t.indexOf("101");
        check(first101 > t.indexOf("S") && t.indexOf("P") > t.indexOf("S"), "101 and positions only after the route and its ACK: " + t);
        check(live.guard.startStatusesSent() == 5 && !live.guard.startPending(), "the start is complete");
        check(live.guard.request(Topics.ODD_REGION_REQUEST) == LiveEgressGuard.REJECTED && live.requests.size() == 1,
                "a second ODD request in the same guidance is refused");
        check(live.transport.of(Topics.NAVIGATION_STATUS).size() > 6, "the status loop continues");
        // A reroute (a new Yandex route ID while guiding) is no new start.
        int before = live.requests.size();
        drive(live, LiveDriveRegressionTest.straight("stock-reroute"), 3);
        check(live.requests.size() == before, "a reroute sends no new ODD request");
        boolean noted = false;
        for (String r : live.audit.reasons) noted |= r.startsWith("liveEgress.start:");
        check(noted, "the guard's journal records the stock start");
        for (LiveDriveRegressionTest.Sent s : live.transport.sent) check(s.topic != DNP_SWITCH_REQUEST, "13/0x0001 never sent");
    }

    /** The guard admits exactly the ODD request, only under the switch, and the start's statuses only in order. */
    static void guardAdmission() {
        Live off = new Live(ExperimentVariant.NONE);
        off.guard.navigationStarting();
        check(!off.guard.startPending(), "no switch: navigationStarting admits nothing");
        check(off.guard.request(Topics.ODD_REGION_REQUEST) == LiveEgressGuard.REJECTED, "no switch: no ODD request");
        check(off.guard.fire(Topics.NAVIGATION_STATUS, StockMessages.navigationStatus(100), off.clock.now()) == LiveEgressGuard.REJECTED,
                "no switch: no 100 before a route");

        Live on = new Live(ExperimentVariant.parse(Json.parse("{\"startProtocol\":\"stock\"}")));
        check(on.guard.request(DNP_SWITCH_REQUEST) == LiveEgressGuard.REJECTED, "13/0x0001 refused");
        check(on.guard.request(Topics.ODD_REGION_REQUEST) == LiveEgressGuard.REJECTED, "the ODD request only inside a stock start");
        on.guard.navigationStarting();
        check(on.guard.startPending(), "a stock start is pending");
        check(on.guard.request(DNP_SWITCH_REQUEST) == LiveEgressGuard.REJECTED && on.requests.isEmpty(),
                "13/0x0001 refused inside the stock start too, nothing reaches the channel");
        check(on.guard.request(Topics.NAVIGATION_STATUS) == LiveEgressGuard.REJECTED, "no other topic is a request");
        check(on.guard.request(Topics.ODD_REGION_REQUEST) == 0 && on.requests.size() == 1, "the ODD request admitted once");
        check(on.guard.request(Topics.ODD_REGION_REQUEST) == LiveEgressGuard.REJECTED && on.requests.size() == 1, "and only once");
        check(on.guard.fire(Topics.NAVIGATION_STATUS, StockMessages.navigationStatus(106), on.clock.now()) == LiveEgressGuard.REJECTED,
                "the start's statuses only in order");
        check(on.guard.fire(Topics.NAVIGATION_STATUS, StockMessages.navigationStatus(101), on.clock.now()) == LiveEgressGuard.REJECTED,
                "101 is not a start status");
        for (int status : ExperimentVariant.stockStartStatuses())
            check(on.guard.fire(Topics.NAVIGATION_STATUS, StockMessages.navigationStatus(status), on.clock.now()) == 0, "start status " + status);
        check(!on.guard.startPending(), "start sequence consumed");
        check(on.guard.fire(Topics.NAVIGATION_STATUS, StockMessages.navigationStatus(100), on.clock.now()) == LiveEgressGuard.REJECTED,
                "after the start 100 is an end status again");
        on.guard.navigationStarting();
        on.guard.navigationStopped();
        check(!on.guard.startPending() && on.guard.request(Topics.ODD_REGION_REQUEST) == LiveEgressGuard.REJECTED,
                "guidance ended during the start: no request, no statuses");
        check(ExperimentVariant.stockStartMs() == 5000 && Arrays.equals(ExperimentVariant.stockStartStatuses(), new int[]{100, 100, 106, 106, 106}),
                "scenario.py's STOCK_PRE_ROUTE_PHASE at 1 Hz");
    }
}
