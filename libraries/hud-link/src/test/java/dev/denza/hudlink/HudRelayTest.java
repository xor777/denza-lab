package dev.denza.hudlink;

import static org.junit.Assert.assertEquals;

import dev.denza.hudlink.HudProtocol.Road;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Where a source's calls go: its own compositor, nowhere while a host is bound, or the host. */
public final class HudRelayTest {
    private final HudSessionTest.TestClock clock = new HudSessionTest.TestClock();
    private final RecordingSink own = new RecordingSink();
    private final List<byte[]> sent = new ArrayList<>();
    private boolean hostAnswers = true;
    private final HudRelay.Remote host = batch -> {
        sent.add(batch);
        return hostAnswers;
    };
    private final HudRelay relay = new HudRelay(clock, own);

    @Test
    public void withoutAHostTheSourceWritesItself() {
        hint(250);

        assertEquals(HudRelay.State.LOCAL, relay.state());
        assertEquals(Arrays.asList("fields MODULE MANEUVER 9=250 ttl=1500"), own.calls);
    }

    @Test
    public void whileTheHostIsBeingBoundNothingIsWrittenAndTheHostGetsItAll() {
        relay.waitForHost();
        hint(250);
        map();
        clock.now = 400;

        relay.attach(host);

        assertEquals(Arrays.asList("hand over"), own.calls);
        assertEquals(Arrays.asList(
                "layer MODULE map MAP 0,0 300×180 300x180 1.0 ttl=1100",
                "fields MODULE MANEUVER 9=250 ttl=1100"), played(sent.get(0)));
    }

    @Test
    public void aHostThatNeverAnswersLeavesTheSourceWritingWhatIsAlive() {
        relay.waitForHost();
        hint(250);
        clock.now = 1_000;

        relay.useLocal();

        assertEquals(Arrays.asList("fields MODULE MANEUVER 9=250 ttl=500"), own.calls);
    }

    @Test
    public void aSourceAlreadyWritingKeepsWritingUntilTheHostAttaches() {
        hint(250);
        relay.waitForHost();
        hint(200);

        assertEquals(HudRelay.State.LOCAL, relay.state());
        relay.attach(host);

        assertEquals(Arrays.asList(
                "fields MODULE MANEUVER 9=250 ttl=1500",
                "fields MODULE MANEUVER 9=200 ttl=1500",
                "hand over"), own.calls);
        assertEquals(Arrays.asList("fields MODULE MANEUVER 9=200 ttl=1500"), played(sent.get(0)));
    }

    @Test
    public void callsOfOneTurnGoToTheHostAsOneBatch() {
        relay.attach(host);
        sent.clear();

        hint(250);
        map();
        relay.flush();
        relay.flush();

        assertEquals(1, sent.size());
        assertEquals(Arrays.asList(
                "fields MODULE MANEUVER 9=250 ttl=1500",
                "layer MODULE map MAP 0,0 300×180 300x180 1.0 ttl=1500"), played(sent.get(0)));
    }

    @Test
    public void aHostThatGoesAwayLeavesTheSourceWritingAtOnceWithWhatIsAlive() {
        relay.attach(host);
        hint(250);
        map();
        relay.flush();
        clock.now = 500;
        hint(200);
        hostAnswers = false;

        relay.flush();

        assertEquals(HudRelay.State.LOCAL, relay.state());
        assertEquals(Arrays.asList(
                "hand over",
                "layer MODULE map MAP 0,0 300×180 300x180 1.0 ttl=1000",
                "fields MODULE MANEUVER 9=200 ttl=1500"), own.calls);
    }

    @Test
    public void whatDiedOrWasTakenAwayIsNotPlayedAgain() {
        relay.waitForHost();
        hint(250);
        map();
        relay.withdraw(HudSource.MODULE, HudRole.MAP);
        clock.now = 1_600;

        relay.attach(host);

        // Nothing alive, nothing sent.
        assertEquals(0, sent.size());
        assertEquals(Arrays.asList("hand over"), own.calls);
    }

    private void hint(int metres) {
        relay.fields(HudSource.MODULE, HudRole.MANEUVER,
                new HudFields.Builder().number(Road.MANEUVER_METERS, metres).build(), 1_500L);
    }

    private void map() {
        relay.layer(HudSource.MODULE, "map", HudRole.MAP, HudRect.whole(HudWindow.MAP),
                Pngs.image(300, 180), 1f, 1_500L);
    }

    private static List<String> played(byte[] batch) {
        RecordingSink host = new RecordingSink();
        HudHostProtocol.replay(batch, HudSource.MODULE, host);
        return host.calls;
    }
}
