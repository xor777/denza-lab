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
    private int abandoned;
    private final HudRelay.Remote host = new HudRelay.Remote() {
        @Override
        public boolean send(byte[] batch) {
            sent.add(batch);
            return hostAnswers;
        }

        @Override
        public void abandon() {
            abandoned++;
        }
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

        // The host's death (HudHostClient's listener) is not a batch it did not take.
        relay.useLocal();

        assertEquals(HudRelay.State.LOCAL, relay.state());
        assertEquals(Arrays.asList(
                "hand over",
                "layer MODULE map MAP 0,0 300×180 300x180 1.0 ttl=1000",
                "fields MODULE MANEUVER 9=200 ttl=1500"), own.calls);
    }

    /**
     * A batch the live host did not take (a full Binder buffer): until 2026-11-19 the source
     * started writing at once while the host went on showing what it had, two writers on one HUD.
     */
    @Test
    public void aBatchTheHostDidNotTakeGoesAgainWholeAndTheSourceDoesNotWriteBesideIt() {
        relay.attach(host);
        hint(250);
        map();
        relay.flush();
        clock.now = 500;
        hint(200);
        hostAnswers = false;

        relay.flush();

        assertEquals(HudRelay.State.REMOTE, relay.state());
        assertEquals(Arrays.asList("hand over"), own.calls);
        assertEquals(0, abandoned);

        clock.now = 600;
        hostAnswers = true;
        relay.flush();

        assertEquals(Arrays.asList(
                "withdraw MODULE",
                "layer MODULE map MAP 0,0 300×180 300x180 1.0 ttl=900",
                "fields MODULE MANEUVER 9=200 ttl=1400"), played(sent.get(sent.size() - 1)));
        // Taken again: the next batch is only what changed.
        hint(150);
        relay.flush();
        assertEquals(Arrays.asList("fields MODULE MANEUVER 9=150 ttl=1500"), played(sent.get(sent.size() - 1)));
    }

    @Test
    public void aHostThatTakesNothingIsGivenUpOnlyOnceWhatItShowedHasDiedAndBeenCleared() {
        relay.attach(host);
        hint(250);
        relay.flush(); // the host shows this until 1500
        hostAnswers = false;
        for (long time = 100; time < 1_500 + HudRelay.HOST_CLEAR_MS; time += 100) {
            clock.now = time;
            hint(200);
            relay.flush();
            assertEquals("still the host's at " + time, HudRelay.State.REMOTE, relay.state());
        }
        clock.now = 1_500 + HudRelay.HOST_CLEAR_MS;
        hint(100);
        relay.flush();

        assertEquals(HudRelay.State.LOCAL, relay.state());
        assertEquals(1, abandoned);
        assertEquals(Arrays.asList("hand over", "fields MODULE MANEUVER 9=100 ttl=1500"), own.calls);
    }

    @Test
    public void aHostThatTookNothingYetIsGivenUpAtOnce() {
        hint(250);
        hostAnswers = false;

        relay.attach(host);

        assertEquals(HudRelay.State.LOCAL, relay.state());
        assertEquals(1, abandoned);
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
