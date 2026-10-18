package dev.denza.hudlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.denza.hudlink.HudProtocol.Road;
import java.util.Arrays;
import org.junit.Test;

/** What a source in another app sends the host, played back the way the host plays it. */
public final class HudHostProtocolTest {

    @Test
    public void everyCallComesOutInOrderAsTheSourceTheHostChose() {
        HudHostProtocol.Batch batch = new HudHostProtocol.Batch();
        assertTrue(batch.isEmpty());
        // The source the caller names is not sent: a caller cannot publish as another source.
        batch.layer(HudSource.CAPTURE, "map", HudRole.MAP, HudRect.whole(HudWindow.MAP),
                Pngs.image(300, 180), 1f, 1_500L);
        batch.fields(HudSource.CAPTURE, HudRole.MANEUVER, new HudFields.Builder()
                .number(Road.MANEUVER_METERS, 250)
                .text(Road.ROAD_NAME, "Тверская")
                .bytes(Road.MANEUVER_PNG, new byte[] {1, 2})
                .decimal(Road.HEADING, 87.5)
                .build(), 1_500L);
        batch.removeLayer(HudSource.CAPTURE, "map");
        batch.withdraw(HudSource.CAPTURE, HudRole.LANES);
        batch.withdraw(HudSource.CAPTURE);
        assertFalse(batch.isEmpty());

        RecordingSink host = new RecordingSink();
        assertTrue(HudHostProtocol.replay(batch.toByteArray(), HudSource.MODULE, host));

        assertEquals(Arrays.asList(
                "layer MODULE map MAP 0,0 300×180 300x180 1.0 ttl=1500",
                "fields MODULE MANEUVER 8=0102 9=250 10=Тверская 32=87.5 ttl=1500",
                "remove MODULE map",
                "withdraw MODULE LANES",
                "withdraw MODULE"), host.calls);
    }

    @Test
    public void aBatchOfAnotherVersionOrCutShortPlaysNothingMore() {
        HudHostProtocol.Batch batch = new HudHostProtocol.Batch();
        batch.withdraw(HudSource.MODULE);
        byte[] bytes = batch.toByteArray();

        byte[] otherVersion = bytes.clone();
        otherVersion[3] = (byte) (HudHostProtocol.VERSION + 1);
        RecordingSink host = new RecordingSink();
        assertFalse(HudHostProtocol.replay(otherVersion, HudSource.MODULE, host));
        assertTrue(host.calls.isEmpty());

        HudHostProtocol.Batch layer = new HudHostProtocol.Batch();
        layer.layer(HudSource.MODULE, "map", HudRole.MAP, HudRect.whole(HudWindow.MAP),
                Pngs.image(300, 180), 1f, 1_500L);
        byte[] whole = layer.toByteArray();
        assertFalse(HudHostProtocol.replay(Arrays.copyOf(whole, whole.length - 3), HudSource.MODULE, host));
        assertTrue(host.calls.isEmpty());
    }

    @Test
    public void aLayerForARoleOfFieldsAndFieldsForARoleOfLayersAreDropped() {
        HudHostProtocol.Batch batch = new HudHostProtocol.Batch();
        batch.layer(HudSource.MODULE, "x", HudRole.MANEUVER, new HudRect(0, 0, 80, 80),
                Pngs.image(80, 80), 1f, 1_000L);
        batch.fields(HudSource.MODULE, HudRole.MAP, new HudFields.Builder().number(9, 1).build(), 1_000L);

        RecordingSink host = new RecordingSink();
        assertTrue(HudHostProtocol.replay(batch.toByteArray(), HudSource.MODULE, host));
        assertTrue(host.calls.isEmpty());
    }
}
